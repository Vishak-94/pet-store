# Per-Module LLD & Schema Diagrams — Index

UI-heavy low-level design for every module of the migrated Java Pet Store. Each module has
**two rendered diagrams** plus an enriched `LLD.md` that explains the working, the class design,
and how the code is reused and extended.

All diagrams share one house style, defined once in [`lld_style.py`](lld_style.py) — a reusable
graphviz helper library imported by every per-module generator (itself a live example of the
reusability the LLDs describe). Regenerate any diagram with:

```bash
cd <module>/docs && python3 <module>_lld.py     # needs python `graphviz` + the `dot` binary
```

| Module | What it is | Class diagram | Schema / data-model diagram | Enriched doc |
|--------|-----------|---------------|-----------------------------|--------------|
| **cart-lib** | Embeddable in-process cart library | [class](../cart-lib/docs/cart-lib_class.png) | [data model](../cart-lib/docs/cart-lib_schema.png) | [LLD](../cart-lib/docs/LLD.md) |
| **petstore-messaging** | Shared JMS envelope + event/destination registry | [class](../petstore-messaging/docs/petstore-messaging_class.png) | [message schema](../petstore-messaging/docs/petstore-messaging_schema.png) | [LLD](../petstore-messaging/docs/LLD.md) |
| **auth-service** | JWT issuer/verifier (RS256) + client SDK | [class](../auth-service/docs/auth-service_class.png) | [schema](../auth-service/docs/auth-service_schema.png) | [LLD](../auth-service/docs/LLD.md) |
| **catalog-service** | Read catalog; ISP 2 ports, 2 `@Profile` adapters | [class](../catalog-service/docs/catalog-service_class.png) | [locale-split ER](../catalog-service/docs/catalog-service_schema.png) | [LLD](../catalog-service/docs/LLD.md) |
| **customer-service** | Customer profile CRUD + client SDK | [class](../customer-service/docs/customer-service_class.png) | [schema](../customer-service/docs/customer-service_schema.png) | [LLD](../customer-service/docs/LLD.md) |
| **inventory-service** | Stock reserve / fulfil; JMS in→out | [class](../inventory-service/docs/inventory-service_class.png) | [schema](../inventory-service/docs/inventory-service_schema.png) | [LLD](../inventory-service/docs/LLD.md) |
| **order-processing-service** | Order/workflow owner; transactional outbox | [class](../order-processing-service/docs/order-processing-service_class.png) | [H2+Mongo schema](../order-processing-service/docs/order-processing-service_schema.png) | [LLD](../order-processing-service/docs/LLD.md) |
| **notification-service** | Pure JMS observer → mailer | [class](../notification-service/docs/notification-service_class.png) | [consumed events](../notification-service/docs/notification-service_schema.png) | [LLD](../notification-service/docs/LLD.md) |
| **admin-office-service** | Thymeleaf back-office; aggregates via client SDKs | [class](../admin-office-service/docs/admin-office-service_class.png) | [SDK aggregation](../admin-office-service/docs/admin-office-service_schema.png) | [LLD](../admin-office-service/docs/LLD.md) |
| **petstore-app-v1** | Storefront UI; composes cart-lib + 4 client SDKs | [class](../petstore-app-v1/docs/petstore-app-v1_class.png) | [session/compose model](../petstore-app-v1/docs/petstore-app-v1_schema.png) | [LLD](../petstore-app-v1/docs/LLD.md) |

Each diagram is rendered as both `.png` (embed/preview) and `.svg` (zoom without pixelation);
the table links the PNGs — swap the extension for the vector version.

## How to read the diagrams

- **Colour = layer** (see each diagram's legend): web/REST, application service, framework-free
  domain, **port (interface seam)**, adapter, entity/document, client SDK, config/security, messaging.
- **Dashed hollow arrow = "realizes a port"** (the extensibility seam — a new adapter plugs in here).
- **Dotted green arrow = asynchronous JMS** (event flow between services).
- **Reusability** shows up as: the shared `petstore-messaging` and `cart-lib` libraries, the per-service
  `*-client` SDK jars reused by callers, and single interfaces with multiple `@Profile` adapters
  (H2 ↔ MongoDB) swapped without touching the layers above the port.
