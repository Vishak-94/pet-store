# admin-office-service — back-office ADMIN console for order approval, status & sales

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8082` · **Package:** `com.petstore.warehouse` · **Legacy origin:** `admin.ear` (back-office console) + `opc.ear` `OPCAdminFacade`

## What it does

The staff-facing **ADMIN console**. It lists, approves, denies, batch-approves, and reports on orders — but it **owns no order data**. Every operation is a delegation to **order-processing-service (the OPC)** over HTTP via the imported `order-processing-client` SDK. It persists nothing, runs no JMS listener, and holds no order/entity/store of its own.

- **Approval console UI** (`/warehouse/orders`, Thymeleaf) — lists PENDING orders with approve/deny; `/warehouse/orders/all` shows every order (newest-received first).
- **Admin JSON API** (`/api/orders/**`, `/api/sales`) — a thin proxy over the OPC facade, including an atomic batch-approval endpoint.
- **Staff login** (`/warehouse/login`) — delegates the credential check to `auth-service`, then drops the RS256 token in a `jwt-warehouse` cookie.

The Java root package is `com.petstore.warehouse` and UI routes live under `/warehouse/**` because the legacy `admin.ear` was the warehouse/back-office console; the Maven artifact and service name are `admin-office-service`. These are legacy-faithful public names — kept intentionally.

## Layout

Flat single-module app (no reactor). Everything under `src/com/petstore/warehouse`.

| Package | Classes |
|---------|---------|
| (root) | `WarehouseServiceApplication` — `@SpringBootApplication` entrypoint |
| `web` | `WarehouseUiController` (`/warehouse/orders` + `/warehouse/orders/all` console, approve/deny), `WarehouseApiController` (`/api/orders/**`, `/api/orders/approvals` batch, `/api/sales`), `WarehouseLoginController` (login/logout, root redirect), `ApiExceptionHandler` (uniform JSON API errors) |
| `security` | `SecurityConfig` — verify-only JWT filter + ADMIN role rules; defines the `OrderProcessingClient` and `AuthClient` beans |
| `config` | `ResilientRestClient` — resilience4j circuit-breaker + bounded retry wrapper around the OPC/auth SDK calls |

Resources: `resources/application.yml` (port 8082, `services.auth.base-url` → :8086, `services.opc.base-url` → :8088, actuator health/info/metrics), `application-dev.yml`, and `resources/templates/{orders,all_orders,login}.html`.

## Build & run

Java 21 required. The build depends on `order-processing-client` (and `auth-client`) being installed in `~/.m2` first.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
# 1) install the OPC client (+ auth-client and other shared libs) first
cd order-processing-service && mvn -q clean install
# 2) then package this leaf app
cd ../admin-office-service && mvn -q clean package
# run it (needs auth-service :8086 and OPC :8088 up)
mvn spring-boot:run
```

`admin-office-service` is a leaf app — packaged by the repo `../build-all.sh` (which installs the shared libs first) and started by `../run-all.sh`. If you see `cannot find symbol com.petstore.opc.client...`, the OPC client was never installed — run step 1.

## API surface / UI

| Surface | Endpoint(s) | Delegates to (OPC) |
|---------|-------------|--------------------|
| UI | `GET /` → redirect to `/warehouse/orders` | — |
| UI | `GET /warehouse/orders` (PENDING console, `orders.html`) | `OrderProcessingClient.ordersByStatus` |
| UI | `GET /warehouse/orders/all` (all orders, `all_orders.html`) | `OrderProcessingClient` (all statuses) |
| UI | `POST /warehouse/orders/{id}/approve` \| `/deny` | `OrderProcessingClient.approve` / `deny` |
| UI | `GET /warehouse/login`, `POST /warehouse/login` \| `/logout` (`login.html`) | `auth-service` via `AuthClient` |
| API | `GET /api/orders`, `GET /api/orders/{id}` | `OrderProcessingClient.ordersByStatus` / `getOrder` |
| API | `POST /api/orders/{id}/approve` \| `/deny` | `OrderProcessingClient.approve` / `deny` |
| API | `POST /api/orders/approvals` (atomic batch) | `OrderProcessingClient.updateOrders` |
| API | `GET /api/sales` (report) | `OrderProcessingClient.sales` |

Controllers do plumbing only (read token → call client → map result). Business rules (approval thresholds, status transitions, sales aggregation) live in the OPC and are not replicated here.

## Events (JMS)

None. This service has no messaging dependency and runs no JMS listener — it is a synchronous HTTP delegator to the OPC.

## Auth / security

- **Verify-only.** Holds only the bundled RS256 **public** key (via `auth-client`) and cannot mint tokens; login delegates to `auth-service`. No credential store here.
- **ADMIN-role gated.** `/warehouse/orders/**`, `/api/orders/**`, `/api/sales/**`, `/warehouse/users/**` require `ROLE_ADMIN`. The acting admin's Bearer token is forwarded to the OPC, which re-enforces ADMIN itself — this console is not the sole gate.
- **Token plumbing differs by surface.** The UI reads the JWT from the **`jwt-warehouse`** cookie (service-specific name + `/warehouse` path + `XSRF-WAREHOUSE` CSRF cookie, so it can't collide with the inventory console on `localhost`); the JSON API reads it from the `Authorization` **header**.
- **Stateless; CSRF DISABLED (local-demo tradeoff).** `SessionCreationPolicy.STATELESS` with `.csrf(csrf -> csrf.disable())`; the `jwt-warehouse` cookie is `SameSite=Strict` in its place. **Re-enable a stable (non-rotating) CSRF token before any non-local deploy** — see the security NOTE in `SecurityConfig.filterChain`.
- The auth entry point returns JSON 401/403 for `/api/**` and redirects to `/warehouse/login` for UI routes.

## See also

- [`CLAUDE.md`](CLAUDE.md) — module guide + invariants (do not add a local order store/entity/listener)
- [`docs/LLD.md`](docs/LLD.md) — class + sequence diagrams and the full endpoint table
- [`.claude/skills/admin-office-service/SKILL.md`](.claude/skills/admin-office-service/SKILL.md) — per-app skill
- [`../.claude/skills/petstore-dev/SKILL.md`](../.claude/skills/petstore-dev/SKILL.md) — repo-wide skill (module map, JMS contract, auth model, build/run)
- [`../DECISIONS.md`](../DECISIONS.md) · [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md)
