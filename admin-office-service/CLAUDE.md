# admin-office-service — Claude guide

The back-office **ADMIN console** (legacy `admin.ear`). It owns **NO order data**: it lists,
approves, denies, batch-approves, and reports on orders purely by **delegating to
order-processing-service (the OPC / legacy `OPCAdminFacade`)** via the imported
`order-processing-client` SDK. Runs on **port 8082**.

Read the repo-wide skill first: [`../.claude/skills/petstore-dev/SKILL.md`](../.claude/skills/petstore-dev/SKILL.md)
(module map, JMS contract, hexagonal rules, auth model, build/run). This file only covers what is
**specific to this module** — do not duplicate the shared content.

- Design detail + diagrams: [`docs/LLD.md`](docs/LLD.md)
- Per-app skill: [`.claude/skills/admin-office-service/SKILL.md`](.claude/skills/admin-office-service/SKILL.md)
- Repo rationale (~30 ADRs): [`../DECISIONS.md`](../DECISIONS.md) — **check before "restoring" any legacy behaviour**
- Parity baseline: [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md)

## Why the package is `com.petstore.warehouse`

Historical: the legacy `admin.ear` was the warehouse/back-office console, so the Java root package is
**`com.petstore.warehouse`** and UI routes live under **`/warehouse/**`**. The Maven artifact and the
service name are `admin-office-service`. Do not "rename to match" — the package and route names are the
legacy-faithful public surface. Keep them.

## What it does (all by delegation)

- **Approval console UI** (`/warehouse/orders`, Thymeleaf) — lists PENDING orders and offers approve/deny.
- **Admin JSON API** (`/api/orders/**`, `/api/sales`) — a thin proxy over the OPC facade.
- **Staff login** (`/warehouse/login`) — delegates credential check to `auth-service`; drops the RS256
  token in a `jwt-warehouse` cookie.

It persists nothing and runs no JMS listener. Every order operation is a call through
`OrderProcessingClient` to the OPC on :8088.

## Package layout (`src/com/petstore/warehouse`)

| Package | Classes |
|---------|---------|
| (root) | `WarehouseServiceApplication` (`@SpringBootApplication` entrypoint) |
| `web` | `WarehouseUiController` (`/warehouse/orders` approve/deny console), `WarehouseApiController` (`/api/orders/**`, `/api/orders/approvals` batch, `/api/sales`), `WarehouseLoginController` (login/logout), `ApiExceptionHandler` (uniform API errors) |
| `security` | `SecurityConfig` (verify-only JWT filter + ADMIN role rules; defines the `OrderProcessingClient` and `AuthClient` beans) |

Resources: `resources/application.yml` (port 8082, `services.auth.base-url` :8086, `services.opc.base-url`
:8088), `resources/templates/{orders,login}.html`.

## Build & test THIS module

Java 21 required. The build depends on **`order-processing-client`** (and `auth-client`) being installed
in `~/.m2` first:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
# 1) install the OPC client (and auth-client, catalog/customer/messaging via build-all.sh) first
cd order-processing-service && mvn -q clean install
# 2) then package this module
cd ../admin-office-service && mvn -q clean package
```

`admin-office-service` is a **leaf app** (packaged by `../build-all.sh`, which installs the shared libs
first). If you see "cannot find symbol `com.petstore.opc.client...`", the OPC client was never installed —
run step 1. Run the whole fleet with `../run-all.sh` (needs auth-service :8086 and OPC :8088 up).

## Invariants — do not break

1. **Owns NO order data — DELEGATE to the OPC.** There is intentionally no JPA/H2/JMS dependency here
   (see `pom.xml` comment). Every list/detail/approve/deny/batch/sales operation goes through
   `OrderProcessingClient` to order-processing-service. **Never add a local order store, entity, or
   listener.** New admin capabilities are added by delegating through the client, not by persisting.
2. **Thin console.** Controllers do plumbing only (read token → call client → map result). Business rules
   (approval thresholds, status transitions, sales aggregation) live in the OPC. Do not replicate them here.
3. **ADMIN-role gated.** `/warehouse/orders/**`, `/api/orders/**`, `/api/sales/**`, `/warehouse/users/**`
   require `ROLE_ADMIN`. The acting admin's Bearer token is **forwarded** to the OPC, which re-enforces
   ADMIN itself — this console is not the sole gate.
4. **Verify-only auth.** Holds only the bundled RS256 **public** key (`auth-client`) and cannot mint tokens.
   Login delegates to `auth-service`. No credential store here.
5. **Token plumbing differs by surface.** The UI reads the JWT from the **`jwt-warehouse`** cookie
   (`WarehouseUiController.jwt`, matching `WarehouseLoginController.JWT_COOKIE`); the JSON API reads it
   from the `Authorization` **header** (`WarehouseApiController.bearer`). The cookie is a
   service-specific name (not the shared `jwt`) + `/warehouse` path + `XSRF-WAREHOUSE` CSRF cookie so it
   can't collide with the inventory console when both are open on `localhost`. Keep both when adding endpoints.
6. **Stateless security; CSRF DISABLED (local-demo tradeoff).** `SessionCreationPolicy.STATELESS`;
   `.csrf(csrf -> csrf.disable())`. CSRF was turned off to unblock the multi-console demo — the tokened
   version rotated the XSRF cookie on every request (STATELESS + per-request re-auth →
   `CsrfAuthenticationStrategy` rotation), so the form's token was stale by submit time → 403. The
   `jwt-warehouse` cookie is `SameSite=Strict`, which blocks the classic cross-site POST in its place.
   **Re-enable a stable (non-rotating) CSRF token before any non-local deploy** — see the security NOTE
   in `SecurityConfig.filterChain`. The Bearer-authed JSON `/api/**` surface never used a token. Auth
   entry point returns JSON 401/403 for `/api/**` and redirects to `/warehouse/login` for UI routes —
   preserve that split.

## What it exposes → which service it calls

| Surface | Endpoint(s) | Delegates to (OPC) |
|---------|-------------|--------------------|
| UI | `GET /warehouse/orders`, `POST /warehouse/orders/{id}/approve`\|`/deny` | `OrderProcessingClient.ordersByStatus`/`getOrder`/`approve`/`deny` |
| API | `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders/{id}/approve`\|`/deny` | same client methods |
| API | `POST /api/orders/approvals` (atomic batch) | `OrderProcessingClient.updateOrders` |
| API | `GET /api/sales` (report) | `OrderProcessingClient.sales` |
| Login | `POST /warehouse/login`\|`/logout` | `auth-service` via `AuthClient` |

See [`docs/LLD.md`](docs/LLD.md) for class + sequence diagrams and the full endpoint table.
