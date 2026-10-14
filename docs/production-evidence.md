# Production evidence

Production evidence is owner-maintained. Repository code and local tests are not evidence that a VPS deployment, internet exposure, backup, restore, or real-use gate has passed. No Slice 5 rollout action was performed by this change.

## Current status

| Gate | Status | Evidence needed from owner |
|---|---|---|
| VPS deployment and DNS/TLS | Pending | Deployed immutable commit/image, hostname and TLS check, private API/database routing, host firewall review |
| Encrypted off-site backup | Pending | Configured destination and schedule covering PostgreSQL, `vault_file_data`, and Data Protection keys |
| Isolated production restore | Pending | Matching recovery set restored into disposable isolated DB and file volume; validate migrations and synthetic file hash |
| D5 before real use | Pending | Sign-in rate limiting, item/file quotas, backup, and isolated restore verified before real content |
| Internet phone/PC flow | Pending | Owner-run browser checks on real devices over the internet |
| MVP exit gate and six-week product gate | Not established | Complete the Slice 6/7 and recovery/security criteria in the source plan |

The repository provides a synthetic local backup/restore test. It creates a local encrypted Restic repository and verifies a synthetic PostgreSQL row, synthetic file bytes, and synthetic Data Protection key. It does not use production volumes or credentials and does not satisfy the production backup or restore rows above.

## Owner restore record

Copy and complete this section only after an isolated production restore. Do not include passwords, tokens, real file names, or file contents.

- Date and operator:
- Backup snapshot reference and UTC timestamp:
- Deployed commit and EF migration version:
- Isolated target identifier:
- Database checks:
- Synthetic file hash and restored-volume check:
- Data Protection key check:
- Start/end time and measured RTO:
- Expected/observed recovery point and data loss:
- Production and neighboring services confirmed untouched:
- Outcome and follow-up:
