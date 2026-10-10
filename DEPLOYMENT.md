# TradeCore deployment (no Docker)

TradeCore is an educational virtual trading platform. NSE quotes are real; accounts, orders, fills, and balances are simulated. It does not connect to a broker or move money.

This guide uses Neon for PostgreSQL, Redis Cloud for cache/rate limits, a Java host such as Railway for the Spring Boot backend, and Vercel for the Next.js frontend. The same environment contract can be used with other hosts. Add secrets in each provider's environment settings; do not commit them.

## 1. Create PostgreSQL and Redis

1. Create a Neon PostgreSQL project and database. In the Neon SQL Editor, run no manual schema scripts: Flyway applies the checked-in migrations at backend startup. Copy the **direct** PostgreSQL connection details from the Neon Connect dialog. Use the direct host for the backend so Flyway can use PostgreSQL session-level migration locks. Build the JDBC URL as `jdbc:postgresql://<host>:5432/<database>?sslmode=require`; encode special characters if present in URL components. The application also configures JDBC SSL mode as `require`.
2. Create a Redis Cloud database with TLS enabled. Record its endpoint host, port, default username (`default`, unless the database says otherwise), and password. Production configuration enables Redis TLS by default; keep `SPRING_DATA_REDIS_SSL_ENABLED=true`.
3. Keep database and Redis credentials only in the backend host's secret/environment settings.

## 2. Build and start the backend

The Maven artifact is `tradecore-backend-0.1.0-SNAPSHOT.jar` for the current `backend/pom.xml` version. From the repository root:

```powershell
cd backend
E:\Maven\apache-maven-3.9.16\bin\mvn.cmd -DskipTests package
java -jar target/tradecore-backend-0.1.0-SNAPSHOT.jar
```

On the Java host, set `SPRING_PROFILES_ACTIVE=production`. The app listens on `PORT` when provided and otherwise on port `8080`. Railway supplies `PORT`; other hosts can set it explicitly. Configure the start command as `java -jar target/tradecore-backend-0.1.0-SNAPSHOT.jar` and the working directory as `backend` (or use the equivalent path from the repository root).

Configure the health check path as `/actuator/health/readiness`. It is allowed without authentication and returns only health status: component names and details are hidden. Readiness checks PostgreSQL and Redis as well as application readiness, so both services must be reachable before startup is considered ready. The production profile exposes only health, info, and metrics; metrics endpoints require ADMIN.

## 3. Configure backend environment

Set the required rows on the Java backend service. `SPRING_PROFILES_ACTIVE` and `PORT` are deployment/runtime settings; the remaining required names are enforced by the production environment validator.

| Name | Required | Placeholder example |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Yes | `production` |
| `PORT` | Host-dependent; default is 8080 | `8080` |
| `SPRING_DATASOURCE_URL` | Yes | `jdbc:postgresql://<neon-direct-host>:5432/<database>?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Yes | `<neon-role>` |
| `SPRING_DATASOURCE_PASSWORD` | Yes | `<neon-password>` |
| `SPRING_DATA_REDIS_HOST` | Yes | `<redis-cloud-host>` |
| `SPRING_DATA_REDIS_PORT` | Yes | `<redis-cloud-port>` |
| `SPRING_DATA_REDIS_USERNAME` | No; defaults to `default` | `default` |
| `SPRING_DATA_REDIS_PASSWORD` | Yes | `<redis-cloud-password>` |
| `SPRING_DATA_REDIS_SSL_ENABLED` | No; production default is `true` | `true` |
| `TRADECORE_CORS_ALLOWED_ORIGINS` | Yes | `https://tradecore.example.com` |
| `TRADECORE_WEBSOCKET_ALLOWED_ORIGINS` | Yes | `https://tradecore.example.com` |
| `TRADECORE_SECURITY_TRUSTED_PROXY_CIDRS` | No; default is empty | `<host-proxy-cidr>` |

Use the exact frontend origin, with scheme and optional port, in both origin settings. For multiple approved frontend origins, use a comma-separated list. Do not use `*`. If the host sits behind a proxy and correct client IP handling is needed, set `TRADECORE_SECURITY_TRUSTED_PROXY_CIDRS` only to the proxy CIDRs documented by that host.

Flyway runs automatically on startup before the application serves traffic. Do not disable it or run modified copies of the migrations. Make a database backup/snapshot before a production release that changes schema.

## 4. Create the first administrator

1. Deploy the backend and frontend, then register an account through the frontend.
2. In the Neon SQL Editor, promote only the intended account (replace the email):

   ```sql
   UPDATE app_user
   SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP
   WHERE lower(email) = lower('admin@example.com');
   ```

3. Confirm the query updated exactly one row. If it updated zero rows, verify the registered email. Never grant ADMIN to an unverified account. Sign out and back in, then open the admin pages.

## 5. Configure and deploy the frontend

1. Create a Vercel project from the repository and set its Root Directory to `frontend`.
2. Add the environment values below for Production (and Preview only if Preview should access the same backend). `NEXT_PUBLIC_*` values are embedded into browser code during build, so set them before building or redeploy after changing them.
3. Deploy the frontend and use its canonical custom domain. Add that exact `https://` origin to both backend allowed-origin settings, then redeploy the backend if those values changed.

| Name | Required | Placeholder example |
| --- | --- | --- |
| `TRADECORE_BACKEND_URL` | Yes | `https://tradecore-api.example.com` |
| `SESSION_SECRET` | Yes | `<long-random-secret-generated-for-this-environment>` |
| `NEXT_PUBLIC_API_BASE_URL` | For a non-local backend; used as the WebSocket fallback base | `https://tradecore-api.example.com` |
| `NEXT_PUBLIC_WEBSOCKET_URL` | Optional; set when the WebSocket endpoint differs from the API host | `wss://tradecore-api.example.com/ws/market-quotes` |

The frontend API proxy uses `TRADECORE_BACKEND_URL`. Quote pages use `NEXT_PUBLIC_WEBSOCKET_URL` when set; otherwise they use `NEXT_PUBLIC_API_BASE_URL` and append `/ws/market-quotes`. They select `wss:` on HTTPS pages and `ws:` on HTTP pages. Set `SESSION_SECRET` to a long, randomly generated value and keep it identical across frontend instances for that environment; it encrypts the httpOnly login-session cookie and is never exposed to the browser.

## 6. Verify the deployment

- `GET https://<backend-host>/actuator/health/readiness` returns HTTP 200 with status `UP`; the response contains no component details.
- Register, sign in, refresh the page, and confirm the session remains active. Sign out and confirm the session is cleared.
- Confirm a company quote loads and updates during a legitimate live market session. Outside market hours, a live change is **NOT VERIFIED**; do not seed fake quotes to test it.
- In browser developer tools, confirm the quote socket connects to `wss://.../ws/market-quotes` on HTTPS and receives quote updates when available.
- Confirm scheduled market-data refresh and simulated execution jobs are enabled and their status is visible in the admin job status page. Schedulers may be idle outside their configured market windows.
- Promote and verify the first admin account, then check the admin pages and reconciliation/status views.
- Check backend logs for successful Flyway startup and clean Redis/PostgreSQL connectivity. Confirm no secrets are present in logs.

The backend host must allow outbound HTTPS requests to the configured NSE MCP endpoints (`mcp.nseindia.in`) for live market data and bhavcopy requests. No inbound public connection to NSE is needed. Provider/network failure must remain visible as unavailable or stale data; never treat it as a verified live quote.

## 7. Rotate secrets and recover

- Rotate Neon and Redis credentials in their provider consoles, update the corresponding backend environment variables, then restart/redeploy the backend. Verify readiness and database/cache access before considering rotation complete.
- To rotate `SESSION_SECRET`, set a new secret in Vercel and redeploy. Existing login cookies become unreadable, so users will need to sign in again. Keep the new secret stable across all frontend instances.
- Keep a known-good application deployment and database backup before changing schema. For a failed backend release, roll back to the previous backend artifact/deployment and inspect startup, Flyway, and health-check logs. Roll back the Vercel deployment separately if the frontend release caused the issue.
- Flyway migrations are forward-only in this project. Do not manually edit migration history or assume an application rollback reverses a schema change. If a migration is incompatible with the previous release, restore the database snapshot or prepare a reviewed forward-fix before rolling application code back.

Provider references: [Neon connection guide](https://neon.com/docs/connect/connect-intro), [Redis Cloud TLS](https://redis.io/docs/latest/operate/rc/security/database-security/tls-ssl/), [Railway health checks](https://docs.railway.com/deployments/healthchecks), [Railway WebSocket guide](https://docs.railway.com/guides/sse-vs-websockets), and [Vercel environment variables](https://vercel.com/docs/environment-variables).
