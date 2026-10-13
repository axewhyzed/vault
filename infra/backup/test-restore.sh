#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# This exercise uses only locally created Docker objects on an internal-only network.
# It does not read production env files, networks, volumes, or B2 credentials.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ID="$(date -u +%Y%m%d%H%M%S)-${RANDOM}"
NETWORK="vault-backup-test-net-${ID}"
SOURCE="vault-backup-test-source-${ID}"
TARGET="vault-backup-test-target-${ID}"
SOURCE_FILES="vault-backup-test-source-files-${ID}"
SOURCE_DP_KEYS="vault-backup-test-source-dp-keys-${ID}"
TARGET_FILES="vault-backup-test-target-files-${ID}"
TARGET_DP_KEYS="vault-backup-test-target-dp-keys-${ID}"
LOCK_VOLUME="vault-backup-test-lock-${ID}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vault-backup-test-${ID}.XXXXXX")"
IMAGE="vault-backup-test:${ID}"
PYTHON_BIN="${PYTHON:-}"

cleanup() {
  docker rm -f "$SOURCE" "$TARGET" >/dev/null 2>&1 || true
  docker volume rm "$SOURCE_FILES" "$SOURCE_DP_KEYS" "$TARGET_FILES" "$TARGET_DP_KEYS" "$LOCK_VOLUME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  docker image rm "$IMAGE" >/dev/null 2>&1 || true
  if [[ -n "$WORK" && -d "$WORK" && "$WORK" == "${TMPDIR:-/tmp}/vault-backup-test-${ID}."* ]]; then
    rm -rf -- "$WORK"
  fi
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { printf 'docker is required\n' >&2; exit 2; }
if [[ -z "$PYTHON_BIN" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
    PYTHON_BIN=python3
  elif command -v python >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
    PYTHON_BIN=python
  else
    printf 'python 3 is required\n' >&2
    exit 2
  fi
fi

mkdir -p "$WORK/repository"
"$PYTHON_BIN" - "$WORK" <<'PY'
import pathlib, secrets, sys
root = pathlib.Path(sys.argv[1])
(root / "restic-password").write_text(secrets.token_urlsafe(48), encoding="ascii")
(root / "source-password").write_text(secrets.token_urlsafe(36), encoding="ascii")
(root / "target-password").write_text(secrets.token_urlsafe(36), encoding="ascii")
(root / "b2-credentials").write_text(
    "[default]\naws_access_key_id = synthetic-test-only\naws_secret_access_key = synthetic-test-only\n",
    encoding="ascii")
PY
RESTIC_PASSWORD="$(cat "$WORK/restic-password")"
SOURCE_PASSWORD="$(cat "$WORK/source-password")"
TARGET_PASSWORD="$(cat "$WORK/target-password")"

docker build --tag "$IMAGE" --file "$ROOT/infra/backup/Dockerfile" "$ROOT"
DOCKER_WORK="$WORK"
if command -v cygpath >/dev/null 2>&1; then
  DOCKER_WORK="$(cygpath -m "$WORK")"
  export MSYS_NO_PATHCONV=1
fi
docker network create --internal "$NETWORK" >/dev/null
docker volume create "$SOURCE_FILES" >/dev/null
docker volume create "$SOURCE_DP_KEYS" >/dev/null
docker volume create "$TARGET_FILES" >/dev/null
docker volume create "$TARGET_DP_KEYS" >/dev/null
docker volume create "$LOCK_VOLUME" >/dev/null
printf 'Synthetic drill: isolated resources created.\n'

docker run --detach --name "$SOURCE" --network "$NETWORK" --network-alias source-db \
  --env POSTGRES_DB=vault --env POSTGRES_USER=vault --env "POSTGRES_PASSWORD=$SOURCE_PASSWORD" \
  --health-cmd 'pg_isready -U vault -d vault' --health-interval 2s --health-timeout 2s --health-retries 30 \
  postgres:18-alpine >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$SOURCE" pg_isready -U vault -d vault >/dev/null 2>&1; then break; fi
  sleep 1
done
if ! docker exec "$SOURCE" pg_isready -U vault -d vault >/dev/null 2>&1; then
  printf 'Synthetic source database did not become ready.\n' >&2
  docker logs "$SOURCE" >&2 || true
  exit 1
fi
printf 'Synthetic drill: source database is ready.\n'
docker exec "$SOURCE" psql -U vault -d vault -v ON_ERROR_STOP=1 -c \
  "CREATE TABLE \"__EFMigrationsHistory\" (\"MigrationId\" character varying(150) PRIMARY KEY, \"ProductVersion\" character varying(32) NOT NULL); INSERT INTO \"__EFMigrationsHistory\" VALUES ('20261014000000_SyntheticRestore', '10.0.0'); CREATE TABLE backup_smoke (id text PRIMARY KEY, body text NOT NULL); INSERT INTO backup_smoke VALUES ('fixture-1', 'synthetic restore fixture');" >/dev/null
printf 'Synthetic drill: test fixture seeded.\n'
docker run --rm --network none --mount "type=volume,src=$SOURCE_DP_KEYS,dst=/data-protection-keys" \
  alpine:3.22 sh -c 'printf synthetic-key-material > /data-protection-keys/test-key.xml'

docker run --rm --user 0:0 --network "$NETWORK" --mount "type=bind,src=$DOCKER_WORK/repository,dst=/repository" \
  --mount "type=bind,src=$DOCKER_WORK/restic-password,dst=/run/secrets/restic_password,readonly" \
  --mount "type=bind,src=$DOCKER_WORK/b2-credentials,dst=/run/secrets/b2_credentials,readonly" \
  --env RESTIC_REPOSITORY=/repository --env RESTIC_PASSWORD_FILE=/run/secrets/restic_password \
  --env AWS_SHARED_CREDENTIALS_FILE=/run/secrets/b2_credentials --env AWS_PROFILE=default --env AWS_DEFAULT_REGION=us-west-004 \
  "$IMAGE" restic init >/dev/null
printf 'Synthetic drill: local encrypted repository initialized.\n'

docker run --rm --user 0:0 --network "$NETWORK" \
  --mount "type=bind,src=$DOCKER_WORK/repository,dst=/repository" \
  --mount "type=bind,src=$DOCKER_WORK/restic-password,dst=/run/secrets/restic_password,readonly" \
  --mount "type=bind,src=$DOCKER_WORK/b2-credentials,dst=/run/secrets/b2_credentials,readonly" \
  --mount "type=volume,src=$SOURCE_FILES,dst=/vault-files,readonly" \
  --mount "type=volume,src=$SOURCE_DP_KEYS,dst=/data-protection-keys,readonly" \
  --mount "type=volume,src=$LOCK_VOLUME,dst=/run/vault-backup" \
  --env VAULT_BACKUP_TARGET=synthetic --env VAULT_DEPLOYED_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567 \
  --env PGHOST=source-db --env PGPORT=5432 --env PGDATABASE=vault --env PGUSER=vault \
  --env "PGPASSWORD=$SOURCE_PASSWORD" --env PGSSLMODE=disable \
  --env RESTIC_REPOSITORY=/repository --env RESTIC_PASSWORD_FILE=/run/secrets/restic_password \
  --env RESTIC_CACHE_DIR=/tmp/restic-cache --env AWS_SHARED_CREDENTIALS_FILE=/run/secrets/b2_credentials \
  --env AWS_PROFILE=default --env AWS_DEFAULT_REGION=us-west-004 \
  "$IMAGE" /usr/local/bin/vault-backup >/dev/null
printf 'Synthetic drill: PostgreSQL and empty file volume backed up.\n'

SNAPSHOTS="$(docker run --rm --user 0:0 --network none --mount "type=bind,src=$DOCKER_WORK/repository,dst=/repository" \
  --mount "type=bind,src=$DOCKER_WORK/restic-password,dst=/run/secrets/restic_password,readonly" \
  --env RESTIC_REPOSITORY=/repository --env RESTIC_PASSWORD_FILE=/run/secrets/restic_password \
  --env RESTIC_CACHE_DIR=/tmp/restic-cache "$IMAGE" restic snapshots --json)"
SNAPSHOT="$("$PYTHON_BIN" -c 'import json,sys; rows=json.load(sys.stdin); print(rows[-1]["short_id"])' <<<"$SNAPSHOTS")"
[[ "$SNAPSHOT" =~ ^[0-9a-fA-F]{8,64}$ ]] || { printf 'No restic snapshot was created\n' >&2; exit 1; }
printf 'Synthetic drill: backup snapshot selected.\n'

docker run --detach --name "$TARGET" --network "$NETWORK" --network-alias restore-db \
  --env POSTGRES_DB=vault_restore --env POSTGRES_USER=vault_restore --env "POSTGRES_PASSWORD=$TARGET_PASSWORD" \
  --health-cmd 'pg_isready -U vault_restore -d vault_restore' --health-interval 2s --health-timeout 2s --health-retries 30 \
  postgres:18-alpine >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$TARGET" pg_isready -U vault_restore -d vault_restore >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$TARGET" pg_isready -U vault_restore -d vault_restore >/dev/null
printf 'Synthetic drill: isolated restore database is ready.\n'

RESTORE_OUTPUT="$(docker run --rm --user 0:0 --network "$NETWORK" \
  --mount "type=bind,src=$DOCKER_WORK/repository,dst=/repository" \
  --mount "type=bind,src=$DOCKER_WORK/restic-password,dst=/run/secrets/restic_password,readonly" \
  --mount "type=bind,src=$DOCKER_WORK/b2-credentials,dst=/run/secrets/b2_credentials,readonly" \
  --mount "type=bind,src=$DOCKER_WORK/target-password,dst=/run/secrets/restore_db_password,readonly" \
  --mount "type=volume,src=$TARGET_FILES,dst=/restore-files" \
  --mount "type=volume,src=$TARGET_DP_KEYS,dst=/restore-data-protection-keys" \
  --env RESTORE_TARGET_ID="synthetic-$ID" --env RESTORE_CONFIRM_ISOLATED=yes \
  --env RESTORE_DB_PASSWORD_FILE=/run/secrets/restore_db_password --env RESTORE_FILE_UID=1654 --env RESTORE_FILE_GID=1654 \
  --env PGHOST=restore-db --env PGPORT=5432 --env PGDATABASE=vault_restore --env PGUSER=vault_restore \
  --env RESTIC_REPOSITORY=/repository --env RESTIC_PASSWORD_FILE=/run/secrets/restic_password \
  --env RESTIC_CACHE_DIR=/tmp/restic-cache --env AWS_SHARED_CREDENTIALS_FILE=/run/secrets/b2_credentials \
  --env AWS_PROFILE=default --env AWS_DEFAULT_REGION=us-west-004 \
  "$IMAGE" /usr/local/bin/vault-restore "$SNAPSHOT")"
printf 'Synthetic drill: isolated restore script completed.\n'

ACTUAL="$(docker exec "$TARGET" psql -U vault_restore -d vault_restore -XAtq -v ON_ERROR_STOP=1 \
  -c "SELECT body FROM backup_smoke WHERE id='fixture-1'")"
[[ "$ACTUAL" == 'synthetic restore fixture' ]] || { printf 'Synthetic restored data mismatch\n' >&2; exit 1; }
[[ "$RESTORE_OUTPUT" == *"file_objects=0"* ]] || { printf 'Restore did not report the expected empty future file volume\n' >&2; exit 1; }
DP_KEY="$(docker run --rm --network none --mount "type=volume,src=$TARGET_DP_KEYS,dst=/restore-data-protection-keys,readonly" alpine:3.22 cat /restore-data-protection-keys/test-key.xml)"
[[ "$DP_KEY" == 'synthetic-key-material' ]] || { printf 'Synthetic Data Protection key restore mismatch\n' >&2; exit 1; }
printf 'Synthetic isolated restore passed (snapshot=%s, fixture row verified, future file volume empty).\n' "$SNAPSHOT"
