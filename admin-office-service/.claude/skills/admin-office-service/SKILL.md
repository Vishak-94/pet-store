---
name: admin-office-service
description: Conventions and how-to for the migrated Pet Store admin-office-service — the back-office ADMIN console on :8082 (legacy admin.ear, package com.petstore.warehouse). Use when working on the admin order-approval/denial UI, the admin JSON API, batch approval (POST /api/orders/approvals), or the sales report (GET /api/sales) — ALL of which delegate to order-processing-service (the OPC) via OrderProcessingClient. Triggers: admin console, warehouse console, order approval, approve/deny order, batch approve, sales report, getChartInfo, OPCAdminFacade, /warehouse/orders, /api/orders, /api/sales, ADMIN role, admin.ear.
---

# admin-office-service — app skill

The back-office **ADMIN console** (legacy `admin.ear`) on **port 8082**, package root
**`com.petstore.warehouse`**. It owns **no order data**; it delegates every order operation to
**order-processing-service (the OPC)** via `OrderProcessingClient`.

Read the repo skill first: [`petstore-dev`](../../../../.claude/skills/petstore-dev/SKILL.md) (module map,
auth model, JMS contract, build/run). Module design + diagrams:
[`../../../docs/LLD.md`](../../../docs/LLD.md). Module guide: [`../../../CLAUDE.md`](../../../CLAUDE.md).

## Conventions

### 1. Delegate to the OPC — never add a local store
This service has **no JPA/H2/JMS dependency on purpose**. All listing, detail, approve, deny, batch, and
sales operations go through `OrderProcessingClient` to the OPC on :8088. **Never** add an order entity,
repository, database, or JMS listener here. The OPC is the single authoritative owner of order data and
workflow; this console is a thin proxy (legacy `admin.ear` → `OPCAdminFacade`).

### 2. ADMIN-role security, token forwarded
`security/SecurityConfig` gates `/warehouse/orders/**`, `/api/orders/**`, `/api/sales/**`,
`/warehouse/users/**` to `ROLE_ADMIN`; login/logout/actuator are permitAll. Auth is **verify-only** (bundled
RS256 public key via `auth-client` — cannot mint tokens); login is delegated to `auth-service` through
`AuthClient`. The acting admin's Bearer token is **forwarded** to the OPC, which re-enforces ADMIN itself.
Session is STATELESS, CSRF disabled. `/api/**` failures return JSON 401/403; UI routes redirect to
`/warehouse/login`.

### 3. Two token sources
UI controllers read the JWT from the **`jwt` cookie** (`WarehouseUiController.jwt`); API controllers read it
from the **`Authorization` header** (`WarehouseApiController.bearer`). Preserve both when adding endpoints.

### 4. Thin controllers
Controllers do plumbing only: read token → call `OrderProcessingClient` → map result. No business rules
(approval thresholds, status transitions, sales aggregation) — those live in the OPC. Map errors via
`ApiExceptionHandler` (`IllegalStateException`→409, `IllegalArgumentException`→404).

### 5. Package is `com.petstore.warehouse` (legacy-origin)
The legacy warehouse/back-office console became this service, so the package root is
`com.petstore.warehouse` and UI routes are under `/warehouse/**` even though the artifact is
`admin-office-service`. Keep these legacy-faithful names.

## How to add an admin action

1. Add (or reuse) a method on `OrderProcessingClient` in the **`order-processing-client`** module
   (`order-processing-service/client/...`) plus its `OrderProcessingEndpoints` path + `OrderDtos` type —
   the OPC contract is the single source of truth. Implement the endpoint on the OPC (`app`) side.
2. Add a controller method here (`WarehouseApiController` for JSON, `WarehouseUiController` for the console)
   that reads the token (header vs cookie) and delegates to the client.
3. Add the route to the `ROLE_ADMIN` matcher list in `SecurityConfig`.
4. Do **not** add persistence/messaging — delegate only.

## Build & test

Java 21. The OPC client must be installed first:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd order-processing-service && mvn -q clean install    # installs order-processing-client to ~/.m2
cd ../admin-office-service && mvn -q clean package
```

Run the fleet with `../../../../run-all.sh` (needs auth-service :8086 and OPC :8088 up). Parity baseline:
[`../../../../docs/PARITY_AUDIT.md`](../../../../docs/PARITY_AUDIT.md); ADRs:
[`../../../../DECISIONS.md`](../../../../DECISIONS.md) — check before "restoring" any legacy behaviour.
