#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

STAGE=initialization
WORK_DIR=""
PASS_FILE=""

log() {
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

fail() {
  local rc=$?
  log "isolated restore failed (stage=${STAGE}, exit=${rc})"
  exit "$rc"
}
trap fail ERR

cleanup() {
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    rm -rf -- "$WORK_DIR"
  fi
  if [[ -n "$PASS_FILE" && -f "$PASS_FILE" ]]; then
    rm -f -- "$PASS_FILE"
  fi
}
trap cleanup EXIT

die() {
  log "refusing restore: $*"
  exit 2
}

main() {
  [[ $# -eq 1 ]] || die "usage: vault-restore <explicit-restic-snapshot-id>"
  local snapshot="$1"
  local target_id="${RESTORE_TARGET_ID:-}"
  [[ "$target_id" =~ ^[a-zA-Z0-9][a-zA-Z0-9-]{2,40}$ ]] || die "RESTORE_TARGET_ID must explicitly identify an isolated target (3-41 letters, digits, or hyphens)"
  [[ "${RESTORE_CONFIRM_ISOLATED:-}" == yes ]] || die "set RESTORE_CONFIRM_ISOLATED=yes only for the disposable isolated restore stack"
  [[ "$snapshot" =~ ^[0-9a-fA-F]{8,64}$ ]] || die "an explicit restic snapshot ID is required; 'latest' is not accepted"
  [[ "${PGHOST:-}" == restore-db && "${PGPORT:-5432}" == 5432 ]] || die "restore target must be the internal Compose service restore-db:5432"
  [[ -n "${PGDATABASE:-}" && -n "${PGUSER:-}" && -n "${RESTORE_DB_PASSWORD_FILE:-}" ]] || die "isolated restore database credentials are incomplete"
  [[ -r "$RESTORE_DB_PASSWORD_FILE" ]] || die "isolated restore database secret is not readable"
  [[ -d /restore-files && -w /restore-files ]] || die "isolated restore file volume is not writable"
  [[ -d /restore-data-protection-keys && -w /restore-data-protection-keys ]] || die "isolated restore Data Protection key volume is not writable"
  [[ -r "$RESTIC_PASSWORD_FILE" && -r "$AWS_SHARED_CREDENTIALS_FILE" ]] || die "restic/B2 secrets are not readable"
  [[ -n "$RESTIC_REPOSITORY" ]] || die "RESTIC_REPOSITORY is required"
  export AWS_SHARED_CREDENTIALS_FILE AWS_PROFILE AWS_DEFAULT_REGION RESTIC_REPOSITORY RESTIC_PASSWORD_FILE

  STAGE=isolated-database-check
  PASS_FILE="$(mktemp /tmp/vault-restore-pgpass.XXXXXX)"
  chmod 0600 "$PASS_FILE"
  printf 'restore-db:%s:%s:%s:%s\n' "$PGPORT" "$PGDATABASE" "$PGUSER" "$(cat "$RESTORE_DB_PASSWORD_FILE")" > "$PASS_FILE"
  export PGPASSFILE="$PASS_FILE" PGAPPNAME="vault-isolated-restore"
  local user_tables
  user_tables="$(psql -w -h restore-db -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -XAtq -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')")"
  [[ "$user_tables" == 0 ]] || die "restore database is not empty"
  [[ -z "$(find /restore-files -mindepth 1 -maxdepth 1 -print -quit)" ]] || die "restore file volume is not empty"
  [[ -z "$(find /restore-data-protection-keys -mindepth 1 -maxdepth 1 -print -quit)" ]] || die "restore Data Protection key volume is not empty"
  restic cat snapshot "$snapshot" >/dev/null

  WORK_DIR="$(mktemp -d /tmp/vault-restore.XXXXXX)"
  STAGE=snapshot-restore
  log "restoring explicit snapshot into isolated target ${target_id}"
  restic restore "$snapshot" --target "$WORK_DIR" >/dev/null
  local dump
  dump="$(find "$WORK_DIR" -type f -name database.dump -print -quit)"
  [[ -n "$dump" ]] || die "snapshot does not contain database.dump"
  python3 - "$dump" <<'PY'
import hashlib, json, pathlib, sys
dump = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[1]).with_name("manifest.json")
data = json.loads(manifest.read_text(encoding="utf-8"))
if data.get("format") != "vault-backup-manifest/v1":
    raise SystemExit("snapshot manifest format is unsupported")
if not data.get("deployed_commit_sha") or not data.get("schema_version"):
    raise SystemExit("snapshot manifest is missing deployment/schema metadata")
hasher = hashlib.sha256()
with dump.open("rb") as source:
    for block in iter(lambda: source.read(1024 * 1024), b""):
        hasher.update(block)
digest = hasher.hexdigest()
if digest != data.get("database", {}).get("sha256"):
    raise SystemExit("database dump checksum does not match the snapshot manifest")
print("snapshot metadata: commit=%s schema=%s file_objects=%s" % (
    data["deployed_commit_sha"], data["schema_version"], data["files"]["object_count"]))
PY
  local restored_files
  restored_files="$(find "$WORK_DIR" -type d -name vault-files -print -quit)"
  local restored_data_protection_keys
  restored_data_protection_keys="$(find "$WORK_DIR" -type d -name data-protection-keys -print -quit)"

  STAGE=database-restore
  pg_restore --no-password --exit-on-error --no-owner --no-acl --dbname="$PGDATABASE" --host=restore-db --port="$PGPORT" --username="$PGUSER" "$dump"

  if [[ -n "$restored_files" ]]; then
    STAGE=file-volume-restore
    cp -a "$restored_files"/. /restore-files/
    chown -R "${RESTORE_FILE_UID:-1654}:${RESTORE_FILE_GID:-1654}" /restore-files
  fi
  if [[ -n "$restored_data_protection_keys" ]]; then
    STAGE=data-protection-keys-restore
    cp -a "$restored_data_protection_keys"/. /restore-data-protection-keys/
    chown -R "${RESTORE_FILE_UID:-1654}:${RESTORE_FILE_GID:-1654}" /restore-data-protection-keys
  fi

  STAGE=reference-check
  local migration_count
  migration_count="$(psql -w -h restore-db -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -XAtq -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM "__EFMigrationsHistory"')"
  [[ "$migration_count" =~ ^[1-9][0-9]*$ ]] || die "restored database has no EF migrations"
  log "isolated restore succeeded (target=${target_id}, snapshot=${snapshot}, restored_migrations=${migration_count}, file_objects=$(find /restore-files -type f -print | wc -l | tr -d ' '), data_protection_keys=$(find /restore-data-protection-keys -type f -print | wc -l | tr -d ' '))"
}

main "$@"
