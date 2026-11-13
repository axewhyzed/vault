# Production validation checklist

This tracked file is a reusable checklist, not a live status report. Keep completed results and live hostnames, addresses, service IDs, backup locations, and credentials in a private operations record. Do not commit credentials, pairing secrets, or real user data.

| Check | Evidence to record privately |
|---|---|
| Deployment and DNS/TLS | Deployed commit and immutable image references; TLS, firewall, and routing checks |
| Private service routing | Confirm the API and database are not published on host ports |
| Encrypted off-site backup | Destination and daily schedule cover PostgreSQL, `vault_file_data`, and Data Protection keys |
| Isolated restore | Restore a matching snapshot into isolated resources; verify migrations and a synthetic file hash |
| Readiness before real data | Verify sign-in protections, item/file quotas, backup, and isolated restore |
| Internet phone/PC flow | Run the end-to-end flow over HTTPS using synthetic content |
| Android physical acceptance | Complete the [Slice 6](slice6-verification.md) and [Slice 7](slice7-verification.md) device matrices |

The local synthetic backup/restore test uses no production resources or credentials and does not replace an isolated production restore.

For a private record, capture the date, operator, deployed commit and migration, snapshot reference, isolated target, checks, recovery time/objective, cleanup, and outcome. Never include secrets or real user content.
