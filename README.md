# TradeCore foundation

TradeCore is a virtual trading and portfolio learning platform. This repository currently contains the Phase 1 application foundation only; trading behavior is not implemented yet.

## Requirements

- JDK 21. The IntelliJ project SDK is `ms-21`; the backend build also targets Java 21.
- Maven 3.6.3 or later.
- Node.js 20.9 or later and npm.
- Docker Compose for the local PostgreSQL and Redis services.

On Windows, select the installed JDK 21 in IntelliJ or set `JAVA_HOME` to its JDK directory before running Maven. The backend build fails fast when Maven runs under another Java major version, including Java 25.

## Local configuration

From the repository root, copy `.env.example` to `.env`, then replace the placeholder database and security passwords with local values. `.env` is ignored by Git. The backend loads this root file when started with `backend/` as its working directory. Do not put production credentials in this file or commit it. The frontend has a separate optional `frontend/.env.local`; its example only contains the public backend URL.

Start local dependencies from the repository root:

```powershell
docker compose up -d postgres redis
```

## Backend

From `backend/`, run with JDK 21 and Maven installed:

```powershell
mvn spring-boot:run
```

The public health endpoints are `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness`. Readiness includes PostgreSQL and Redis. Other API paths require HTTP Basic credentials from `TRADECORE_SECURITY_USER` and `TRADECORE_SECURITY_PASSWORD`. The in-memory user is foundation-only and must be replaced by the application authentication module in a later phase.

## Frontend

From `frontend/`:

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

The frontend uses Next.js App Router server components. `NEXT_PUBLIC_API_BASE_URL` is reserved for the later frontend/backend integration.

## Foundation scope

Included: Java 21/Spring Boot/Maven, Next.js, PostgreSQL and Redis connection configuration, local Compose services, environment-based secrets, HTTP security baseline, and liveness/readiness health reporting.

Not included: orders, executions, positions, P&L, ledger, risk rules, market-data integration, WebSocket business events, watchlists, notifications, or admin business features.
