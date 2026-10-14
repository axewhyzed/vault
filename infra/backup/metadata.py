#!/usr/bin/env python3
"""Write a content-free backup manifest without shell interpolation."""

import hashlib
import json
import os
import pathlib
import sys
from datetime import datetime, timezone


def main() -> int:
    if len(sys.argv) != 5:
        print("usage: metadata.py DUMP_PATH FILE_ROOT DATA_PROTECTION_ROOT OUTPUT_PATH", file=sys.stderr)
        return 2

    dump_path = pathlib.Path(sys.argv[1])
    file_root = pathlib.Path(sys.argv[2])
    data_protection_root = pathlib.Path(sys.argv[3])
    output_path = pathlib.Path(sys.argv[4])
    commit = os.environ["VAULT_DEPLOYED_COMMIT_SHA"]
    target = os.environ["VAULT_BACKUP_TARGET"]
    schema_version = os.environ["VAULT_SCHEMA_VERSION"]

    digest = hashlib.sha256()
    with dump_path.open("rb") as dump:
        for block in iter(lambda: dump.read(1024 * 1024), b""):
            digest.update(block)

    file_count = 0
    file_bytes = 0
    for path in file_root.rglob("*"):
        if path.is_file():
            file_count += 1
            file_bytes += path.stat().st_size

    dp_count = 0
    dp_bytes = 0
    for path in data_protection_root.rglob("*"):
        if path.is_file():
            dp_count += 1
            dp_bytes += path.stat().st_size

    manifest = {
        "format": "vault-backup-manifest/v1",
        "created_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "backup_target": target,
        "deployed_commit_sha": commit,
        "schema_version": schema_version,
        "database": {
            "artifact": "database.dump",
            "format": "postgres-custom",
            "sha256": digest.hexdigest(),
            "bytes": dump_path.stat().st_size,
        },
        "files": {
            "volume_path": "/var/lib/vault/files",
            "artifact": "mounted-volume:/vault-files",
            "object_count": file_count,
            "bytes": file_bytes,
            "status": "present" if file_count else "empty",
        },
        "data_protection_keys": {
            "volume_path": "/home/app/.aspnet/DataProtection-Keys",
            "artifact": "mounted-volume:/data-protection-keys",
            "file_count": dp_count,
            "bytes": dp_bytes,
            "status": "present" if dp_count else "empty",
        },
        "verification": {
            "result": "recorded-in-verification-snapshot",
            "note": "A successful repository check is recorded as verification.json in a follow-up snapshot.",
        },
    }
    output_path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
