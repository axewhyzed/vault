FROM restic/restic:0.19.1@sha256:136600b6ff6843d61d355f7f71f460a166429f35de6fd11b568fece3c9a4d510 AS restic

FROM postgres:18-alpine

COPY --from=restic /usr/bin/restic /usr/local/bin/restic

RUN apk add --no-cache bash coreutils findutils python3 util-linux \
    && mkdir -p /usr/local/lib/vault-backup

COPY infra/backup/backup.sh /usr/local/bin/vault-backup
COPY infra/backup/restore.sh /usr/local/bin/vault-restore
COPY infra/backup/metadata.py /usr/local/lib/vault-backup/metadata.py

RUN chmod 0555 /usr/local/bin/vault-backup /usr/local/bin/vault-restore \
    && chmod 0444 /usr/local/lib/vault-backup/metadata.py

ENTRYPOINT []
