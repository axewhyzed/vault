#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

STAGE=initialization
WORK_DIR=""

log() {
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

fail() {
  local rc=$?
  log "backup failed (stage=${STAGE}, exit=${rc})"
  exit "$rc"
}
trap fail ERR

cleanup() {
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    rm -rf -- "$WORK_DIR"
  fi
  flock -u 8 2>/dev/null || true
  exec 8<&- 2>/dev/null || true
}
trap cleanup EXIT

die() {
  log "configuration error: $*"
  exit 2
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || die "${name} is required"
}

validate_inputs() {
  local name
  for name in VAULT_BACKUP_TARGET VAULT_DEPLOYED_COMMIT_SHA PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSSLMODE RESTIC_REPOSITORY RESTIC_PASSWORD_FILE AWS_SHARED_CREDENTIALS_FILE AWS_PROFILE AWS_DEFAULT_REGION; do
    require_env "$name"
  done
  [[ "$VAULT_BACKUP_TARGET" == production || "$VAULT_BACKUP_TARGET" == synthetic ]] || die "VAULT_BACKUP_TARGET must be production or synthetic"
  [[ "$VAULT_DEPLOYED_COMMIT_SHA" =~ ^[0-9a-fA-F]{7,64}$ ]] || die "VAULT_DEPLOYED_COMMIT_SHA must be a 7-64 digit git SHA"
  [[ "$PGPORT" =~ ^[0-9]{1,5}$ ]] && (( PGPORT >= 1 && PGPORT <= 65535 )) || die "PGPORT is invalid"
  [[ -r "$RESTIC_PASSWORD_FILE" ]] || die "restic password secret is not readable"
  [[ -r "$AWS_SHARED_CREDENTIALS_FILE" ]] || die "B2 credentials secret is not readable"
  [[ -d /vault-files && -r /vault-files ]] || die "read-only file-volume mount /vault-files is missing"
  [[ -d /data-protection-keys && -r /data-protection-keys ]] || die "read-only Data Protection key mount /data-protection-keys is missing"
  [[ -d /run/vault-maintenance && -w /run/vault-maintenance ]] || die "shared file maintenance lock volume is missing or not writable"
  [[ -n "$RESTIC_REPOSITORY" ]] || die "RESTIC_REPOSITORY is empty"
}

main() {
  local mode="run"
  if [[ $# -gt 1 ]]; then
    die "usage: vault-backup [--check|--dry-run]"
  elif [[ $# -eq 1 ]]; then
    case "$1" in
      --check) mode="check" ;;
      --dry-run) mode="dry-run" ;;
      *) die "usage: vault-backup [--check|--dry-run]" ;;
    esac
  fi

  validate_inputs
  export PGAPPNAME="vault-backup"
  export AWS_SHARED_CREDENTIALS_FILE AWS_PROFILE AWS_DEFAULT_REGION
  export RESTIC_REPOSITORY RESTIC_PASSWORD_FILE

  # Exclude API file-volume mutations across pg_dump and the Restic volume scan.
  # API upload/complete/delete operations take shared locks; sweep and other
  # backups take exclusive locks on this same persistent volume lock file.
  STAGE=maintenance-lock
  touch /run/vault-maintenance/maintenance.lock
  exec 8<>/run/vault-maintenance/maintenance.lock
  flock -x -w 300 8 || die "timed out waiting 300 seconds for exclusive file maintenance lock"
  log "exclusive file maintenance lock acquired; PostgreSQL and volume will be captured without API file mutations or a concurrent backup"

  STAGE=database-connectivity
  local schema_version
  schema_version="$(psql -XAtqw -v ON_ERROR_STOP=1 -c 'SELECT "MigrationId" FROM "__EFMigrationsHistory" ORDER BY "MigrationId" DESC LIMIT 1')"
  [[ -n "$schema_version" ]] || die "the EF migration history table is empty"
  export VAULT_SCHEMA_VERSION="$schema_version"

  STAGE=repository-connectivity
  restic unlock >/dev/null 2>&1 || true
  restic cat config >/dev/null

  if [[ "$mode" == check ]]; then
    log "config check passed (target=${VAULT_BACKUP_TARGET}, schema=${VAULT_SCHEMA_VERSION}, file_objects=$(find /vault-files -type f -print | wc -l | tr -d ' '), data_protection_keys=$(find /data-protection-keys -type f -print | wc -l | tr -d ' '))"
    return 0
  fi

  if [[ "$mode" == dry-run ]]; then
    log "dry run passed; would dump PostgreSQL database ${PGDATABASE}, back up the read-only file and Data Protection volumes, and write encrypted snapshot metadata (commit=${VAULT_DEPLOYED_COMMIT_SHA}, schema=${VAULT_SCHEMA_VERSION})"
    return 0
  fi

  WORK_DIR="$(mktemp -d /tmp/vault-backup.XXXXXX)"
  mkdir -p "$WORK_DIR/stage"
  local dump="$WORK_DIR/stage/database.dump"

  STAGE=database-dump
  log "dumping PostgreSQL database (target=${VAULT_BACKUP_TARGET}, commit=${VAULT_DEPLOYED_COMMIT_SHA}, schema=${VAULT_SCHEMA_VERSION})"
  pg_dump --no-password --format=custom --no-owner --no-acl --file="$dump"

  STAGE=metadata
  python3 /usr/local/lib/vault-backup/metadata.py "$dump" /vault-files /data-protection-keys "$WORK_DIR/stage/manifest.json"

  STAGE=encrypted-upload
  log "uploading encrypted database and file-volume snapshot (file_objects=$(find /vault-files -type f -print | wc -l | tr -d ' '))"
  restic backup --host "$VAULT_BACKUP_TARGET" --tag vault --tag daily --json "$WORK_DIR/stage" /vault-files /data-protection-keys >/dev/null

  STAGE=repository-check
  restic check >/dev/null
  STAGE=manifest-verification-record
  printf '{"result":"passed","verified_at_utc":"%s","check":"restic check (repository metadata)"}\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" > "$WORK_DIR/stage/verification.json"
  restic backup --host "$VAULT_BACKUP_TARGET" --tag vault --tag verification --json "$WORK_DIR/stage" /vault-files /data-protection-keys >/dev/null
  restic check >/dev/null

  STAGE=retention
  restic forget --keep-daily 14 --keep-weekly 8 --keep-monthly 6 --prune >/dev/null

  STAGE=complete
  log "backup succeeded (target=${VAULT_BACKUP_TARGET}, commit=${VAULT_DEPLOYED_COMMIT_SHA}, schema=${VAULT_SCHEMA_VERSION}, database_sha256=$(sha256sum "$dump" | cut -d ' ' -f 1), file_objects=$(find /vault-files -type f -print | wc -l | tr -d ' '), data_protection_keys=$(find /data-protection-keys -type f -print | wc -l | tr -d ' '), verification=restic-check-passed)"
}

main "$@"
