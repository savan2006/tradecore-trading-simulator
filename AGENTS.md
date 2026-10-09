# TradeCore - instructions for the coding agent (read this first)

TradeCore is an educational VIRTUAL trading platform: REAL NSE market data,
SIMULATED money, orders and execution. It never touches real money or a real broker.
Backend: `backend/` (Spring Boot, Maven, PostgreSQL, Redis). Frontend: `frontend/`
(Next.js App Router, TypeScript). More background: `docs/PROJECT_CONTEXT.md`
(read only when you need it). Progress of all planned work: `docs/PROGRESS.md`.

## Start of every session (cheap, do it first)
1. Read `docs/PROGRESS.md`.
2. Run `git status` and `git log --oneline -5`.
3. If the user pasted a task, do that task. If the user only says "next",
   "continue" or "next task": take the first task in the "Order" list of
   PROGRESS.md whose status is IN_PROGRESS, then BLOCKED, then NOT_STARTED
   (skip rows marked OPTIONAL unless the user asks), read its instructions in
   `docs/prompts/<ID>.md`, and do exactly that task.
4. Never start from zero if work is partly done: IN_PROGRESS plus uncommitted
   changes means a previous session was interrupted (usage limit). Read
   `git diff`, keep the correct partial work, finish only what is missing.
5. If there are uncommitted changes that do NOT belong to the task, stop and
   ask the user to commit or stash them first. Do not mix tasks in one commit.

## Finish of every task (always)
1. The task's tests/build must pass (backend and/or frontend as the task says).
   If they cannot pass, do not commit: mark the row BLOCKED in PROGRESS.md with
   the reason and stop.
2. Update ONLY your own row in `docs/PROGRESS.md`: status DONE, date,
   one-line summary, test count. If the task was already satisfied or blocked,
   still update the row and commit only PROGRESS.md.
3. Stage only the files you changed for this task plus `docs/PROGRESS.md`.
   NEVER stage `.env*` (except `.env.example`), secrets, `target/`,
   `node_modules/`, `.next/`, `*.tsbuildinfo`. Check `git status` before committing.
4. `git commit -m "<the task's commit message>"` (simple, natural wording).
5. Do NOT push. The user pushes.
6. Final report to the user: max 10 lines.

## Commands (Windows)
- Backend tests: `set JAVA_HOME=C:\Users\DELL\.jdks\ms-21.0.12.1 && cd backend && E:\Maven\apache-maven-3.9.16\bin\mvn.cmd -q test`
  (add `-Dtest=ClassName` for one class; use the JDK the pom requires if newer than 21).
- Frontend: `cd frontend && npx tsc --noEmit && npm run build`.

## Non-negotiable rules
- PostgreSQL is the only financial source of truth. Redis is cache/rate-limit only.
- Money and prices are BigDecimal. Never double.
- Never edit an existing Flyway migration. If a schema change is essential, add a
  NEW migration with the next free version number.
- Complete fills only. No partial fills. No short selling. No real money or payments.
- Never bypass: stale-quote check (data LIVE and provider timestamp <= 10 minutes),
  market-session check (MarketHoursPolicy), risk limits, ownership checks.
- Never insert fake quotes into a real database. Report NOT VERIFIED when something
  cannot be verified legitimately (market closed, no network, no credentials);
  never weaken a check to make a test pass.
- NSE data only through the MarketDataProvider adapter (`market/nse`).
- Keep feature-based packages. No `common/`, `shared/`, `utils/` packages.
- Financial lock order: order -> account -> position.
- Audit and notification failures must never fail a financial operation.
- Frontend stays lightweight: no Redux/Zustand/UI libraries.
- No new dependency, file or abstraction unless the task asks for it.
- NEVER open or print `.env` or `.env.local` files. `.env.example` is fine.
- Read only the files the task needs. No repo-wide re-audit. No unrelated refactors.
- Check the real framework versions in `backend/pom.xml` and `frontend/package.json`
  and write code valid for them. Do not upgrade versions unless the task says so.
