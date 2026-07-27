# order-processing-client — order-processing-service client SDK

> Importable SDK jar for order-processing-service. Part of the Pet Store migration ([repo README](../../README.md)).

## What it provides

A thin, typed HTTP client for the **order-processing-service admin facade** (the modern `OPCAdminFacade`
proxy) — plus the shared wire DTOs and endpoint path constants. admin-office-service (the admin console)
imports it to list orders and submit approve/deny/batch, exactly as the legacy `admin.ear` called
`OPCAdminFacade`; the OPC `app` itself imports it too, so the HTTP contract (paths + DTOs) is **single-sourced**
and the two sides cannot drift.

The acting caller's Bearer token is forwarded on every call so OPC enforces authorization itself (ADMIN for
the admin methods; the customer role for `checkout`). The client is transport only — it holds no credentials.

## Maven coordinates

```xml
<dependency>
    <groupId>com.petstore</groupId>
    <artifactId>order-processing-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

Java 21. Depends only on `spring-web` + `spring-context` (6.1.14) and `jakarta.validation-api` (constraint
annotations on the wire DTOs); the server brings the Hibernate Validator provider. `mvn -q clean install` from
the parent installs this jar to `~/.m2` before building `app`.

## Key classes

- **`OrderProcessingClient`** — the client. Backed by a `RestClient` with bounded connect (2s) / read (5s)
  timeouts so a hung OPC can't tie up the admin console's threads. Methods: `checkout`, `ordersByStatus`,
  `allOrders`, `getOrder` (404 → `Optional.empty()`), `approve`, `deny`, `updateOrders` (atomic batch),
  `sales`. Null server bodies are normalized to empty. Construct with a base URL, the default
  (`http://localhost:8088`), or a preconfigured `RestClient`.
- **`OrderDtos`** — the wire records: `CheckoutRequest`/`CheckoutResponse`, `OrderView`, `OrderSummaryDto`,
  `OrdersByStatus`, `StatusView`, `OrderStatusChangeDto`, `OrderApprovalDto`, `SalesReportDto` (+`SalesBucketDto`),
  `LineDto`, `ContactInfoDto`. Bean-validation constraints (`@NotBlank`/`@NotEmpty`/`@Valid`) enforced server-side.
- **`OrderProcessingEndpoints`** — path + query-param constants shared by client and server
  (`ORDER_INTAKE`, `ORDERS`, `ORDERS_ALL`, `ORDER_BY_ID`, `ORDER_STATUS`, `ORDER_APPROVE`, `ORDER_DENY`,
  `ORDER_APPROVALS`, `SALES`, `DEFAULT_BASE_URL`), so the mapped routes and the called routes stay in lockstep.

## Usage

```java
OrderProcessingClient client = new OrderProcessingClient();   // defaults to http://localhost:8088

// list pending orders (admin token)
OrdersByStatus pending = client.ordersByStatus("PENDING", bearer);

// approve one, or apply a batch atomically
client.approve("1002", bearer);
client.updateOrders(new OrderApprovalDto(List.of(
        new OrderStatusChangeDto("1002", "APPROVED"),
        new OrderStatusChangeDto("1003", "DENIED"))), bearer);

// sales aggregation over a date range (null category → group by category)
SalesReportDto report = client.sales("2026-07-01", "2026-07-31", null, bearer);
```

Illegal transitions and version conflicts surface as `HttpClientErrorException.Conflict` (409); invalid
payloads / unknown statuses as `HttpClientErrorException.BadRequest` (400).

## See also

- [../README.md](../README.md) — the order-processing-service module
- [../CLAUDE.md](../CLAUDE.md) — client contract + invariants
- [repo README](../../README.md)
