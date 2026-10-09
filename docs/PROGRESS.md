# TradeCore progress

This file is the memory of the project across Codex sessions and accounts.
Each task edits ONLY its own row. Status values: NOT_STARTED, IN_PROGRESS, BLOCKED, DONE.
Task instructions: `docs/prompts/<ID>.md`. Rules and protocol: `AGENTS.md`.

## How to continue in a new session or new Codex account
Type: `Continue with the next TradeCore task.`
The agent reads this file, picks the first IN_PROGRESS / BLOCKED / NOT_STARTED row in
the table order below (skipping OPTIONAL rows unless you ask), reads
`docs/prompts/<ID>.md` and does it. If a session was interrupted by a usage limit, the
row is still IN_PROGRESS and the partial work is in `git diff`; the new session finishes it.

## Baseline (audit on 2026-10-09)
Spring Boot 3.5.x, Java 21, Next.js 16, Flyway V1-V12, about 235 backend tests, branch main
with one unpushed commit at audit time. Known defects: see `docs/PROJECT_CONTEXT.md` section 9.

## Tasks (recommended order, top to bottom)
| ID | Task | Size | Status | Date | Summary / tests | Notes |
|----|------|------|--------|------|-----------------|-------|
| U1 | Upgrade backend to Spring Boot 4.1.1 | L | DONE | 2026-10-09 | Upgraded to Boot 4.1.1 and Jackson 3; backend tests 235 (0 failures, 3 skipped). |  |
| U2 | Refresh other backend dependencies and build plugins | S | DONE | 2026-10-09 | Updated Enforcer; confirmed MCP SDK 2.0.1 is current. Backend tests 235 (0 failures, 3 skipped). |  |
| U3 | Upgrade frontend to the latest stable Next.js / React / TypeScript | M | DONE | 2026-10-09 | Updated Next/React/TypeScript and Node engines; typecheck/build pass, production audit clean. Tests: n/a. |  |
| C1 | CRITICAL FIX: real quotes can never execute orders | M | DONE | 2026-10-09 | Session eligibility now uses MarketHoursPolicy; UNKNOWN live ingested quotes can fill. Backend tests: 236 (0 failures, 3 skipped). |  |
| C2 | Fix execution starvation + intraday square-off timing | M | NOT_STARTED |  |  |  |
| C4 | Financial invariants test + read-only reconciliation endpoint | M | NOT_STARTED |  |  |  |
| C3 | Market-data robustness + 80-company completeness report | M | NOT_STARTED |  |  |  |
| C5 | Operational status tracking for background jobs | S | NOT_STARTED |  |  |  |
| S1 | Security hardening (backend + frontend headers) | M | NOT_STARTED |  |  |  |
| S2 | Production configuration, proxy-safe client IP, logging | M | NOT_STARTED |  |  |  |
| S3 | Real login session (httpOnly cookie, survives refresh) | M | NOT_STARTED |  |  |  |
| F1 | Frontend bug fixes found in the code | S | NOT_STARTED |  |  |  |
| F2 | Order timeline, fill notifications, market-session endpoint + badge | M | NOT_STARTED |  |  |  |
| F3 | Frontend final QA: states, mobile, accessibility basics | M | NOT_STARTED |  |  |  |
| Q1 | OpenAPI / Swagger documentation | M | NOT_STARTED |  |  |  |
| Q2 | Performance review + controlled performance tests | M | NOT_STARTED |  |  |  |
| Q3 | Repeatable live smoke-test script (PowerShell) | S | NOT_STARTED |  |  |  |
| Q4 | CI pipeline (GitHub Actions) | S | NOT_STARTED |  |  |  |
| Q5 | Deployment readiness (no Docker) | M | NOT_STARTED |  |  |  |
| Q6 | Final cleanup, README, architecture docs, data-cleanup script | M | NOT_STARTED |  |  |  |
| Q7 | FINAL RELEASE AUDIT (the 'is it really done?' gate) | M | NOT_STARTED |  |  |  |
| U4 | OPTIONAL: move backend to Java 25 LTS (OPTIONAL) | S | NOT_STARTED |  |  |  |
| Q5b | OPTIONAL: Dockerfile (only if your host requires Docker) (OPTIONAL) | S | NOT_STARTED |  |  |  |

## Manual steps (done by the user, not by Codex)
- [ ] M0 Rotate Neon + Redis passwords; push; create branch `upgrade-and-hardening`
- [ ] M1 Run historical backfill for all 80 companies; check completeness endpoint (after C3)
- [ ] M2 Live verification in market hours: smoke script, WebSocket number change, STOP_MARKET fill, intraday square-off, reconciliation (after C1, C2, Q3)
- [ ] M3 Deploy (after Q5)
- [ ] M4 Add Muhurat session (Sun 2026-11-08) when NSE publishes times; clean synthetic data in cloud DB; fill final test matrix; merge and tag v1.0.0

## Session log (add one line when something notable happens)
- {today}: pack created; no task started.
