# Production evidence ledger

This ledger separates repository/configuration facts from production facts. **Pending** means no evidence has yet been recorded here; it is not a pass. Attach sanitized command output, screenshots, or checksums by reference. Never include secret values, cookies, access tokens, item text, or URL query content.

## Slice 4 D5 and exposure gate

| Check | Status | Evidence to record |
|---|---|---|
| Chosen hostname and DNS | Pending | `vault.axewhyzedlabs.co.in` DNS answer, check time, and confirmation existing records were not changed |
| TLS and routing | Pending | Public HTTPS certificate/hostname, HTTP redirect, web health, and confirmation only web is routed through Traefik |
| Host and cloud firewall | Pending | Dated external port scan: SSH/80/443 as intended; 5432, 8080, Coolify management ports, and other non-web ports closed |
| Existing app preservation | Pending | Health/status of existing Coolify applications before and after Vault deploy |
| Private API and PostgreSQL | Pending | Coolify network/resource configuration showing API and DB have no published host ports; no public database/API route |
| Dedicated PostgreSQL isolation | Pending | Sanitized database/role names and grants; verification the Vault role cannot access other app DBs; no passwords |
| Trusted client IP | Pending | Observed Traefik source address at web/Nginx; configured exact `VAULT_TRAEFIK_TRUSTED_IP`; confirmed `VAULT_WEB_IP` and matching API `TrustedProxyIp`; test showing sign-in limiter keys on the true client address and not a client-spoofed forwarding header |
| Sign-in rate limit | Pending | Dated smoke result and server behavior for configured D3 thresholds through public proxy |
| Text/URL item bounds | Pending | Dated synthetic smoke results for text/URL validation, owner scoping, seven-day cap, quota checks, and anonymous rejection |
| Encrypted off-site PostgreSQL backup | Pending | Provider/bucket reference, schedule/retention, encrypted artifact timestamp and checksum; no credentials |
| Data Protection key backup | Pending | Matching persistent key volume is included in encrypted recovery set; key identifier/checksum only, never key bytes |
| One isolated restore (D5) | Pending | Restore reference, matching backup timestamp, checksum validation, migration/read/health checks, observed RPO and RTO, cleanup confirmation |
| D5 real-use prerequisite | Pending | Mark satisfied only when all applicable D5 controls above, including isolated restore, have evidence |

## Slice 4 deployment record

- Deployment date/time (UTC): Pending
- Vault commit SHA: Pending
- API/web image identifiers: Pending
- EF migration level: Pending
- Coolify deployment result and health: Pending
- Smoke flow result (synthetic text and URL, sign-in, list/read/delete): Pending
- External checks and operator: Pending
- Issues/follow-up: Pending

## Product and MVP gates

- Slice 4 scope demonstrated: Pending. Record phone browser to PC browser text/URL behavior only if it is actually completed over the internet.
- MVP exit gate: **Not established by Slice 4.** Android send/receive, file flows, reliability scenarios, complete security floor, and recovery floor require their own evidence.
- Six-week product gate start: **Not started by this deployment record.** Enter a start date only after the owner confirms stable production use and begins the planned metadata-only measurement. Record weekly review references; never collect item content.
- File storage / backup of file bytes: **Not in current Slice 4 scope.** Add its storage volume and consistency/checksum evidence before enabling file exposure.

## Restore event template

Copy for every isolated or incident restore:

- Date/time started and ended (UTC):
- Operator / environment reference:
- Backup timestamp and artifact reference:
- PostgreSQL artifact checksum result:
- Matching Data Protection key set reference/checksum result:
- Restored commit/image and migration level:
- Health, owner-scoped synthetic retrieval, and anonymous rejection:
- Observed RPO / RTO:
- Failures and corrective actions:
- Isolated environment cleanup confirmed:
