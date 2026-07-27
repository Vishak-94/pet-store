# notification-service — stateless observer that emails customers on order events

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8087` · **Package:** `com.petstore.notification` · **Legacy origin:** `opc.ear` customer-relations mailer MDBs (`MailInvoiceMDB`, `MailOrderApprovalMDB`, `MailCompletedOrderMDB`)

## What it does

Customer **email notifications** for the migrated Pet Store. It is a **pure consumer**: it subscribes to order events off the shared broker and "sends" the customer an email. It **owns no data, exposes no business API, and publishes nothing** — a consumer-only leaf.

- Consumes `InvoiceTopic` and `OrderStatusTopic` (pub/sub) and composes a customer email per event, restoring the legacy mailer MDBs.
- Also hosts the fleet's **DLQ/ExpiryQueue observer** (`DlqListener`) — a natural fit since this service is already a JMS observer with no business state to corrupt. It logs quarantined (poison) messages at **ERROR** and never converts or re-publishes them.

There is no controller and no persistence layer by design — the `@JmsListener` beans are the only entry points; `actuator` is the only HTTP surface.

## Layout

Flat single-module app (no reactor). Everything under `src/com/petstore/notification`.

| Package | Classes |
|---------|---------|
| (root) | `NotificationServiceApplication` (`@SpringBootApplication`, scans notification + messaging), `InvoiceNotificationListener` (`@JmsListener` InvoiceTopic → `composer.fromInvoice` → send), `OrderStatusNotificationListener` (`@JmsListener` OrderStatusTopic → `composer.fromStatus` → send), `DlqListener` (`@JmsListener` DLQ + ExpiryQueue via `queueFactory` → log ERROR on raw `Message`) |
| `mail` | `MailSender` (PORT — `void send(Email)`), `LoggingMailSender` (default dev adapter; logs the email, `@ConditionalOnMissingBean(name="smtpMailSender")`), `OrderMailComposer` (builds `Email` from events; holds the legacy subject strings + `fromStatus()`), `Email` (record `to`/`subject`/`body` — legacy `mailer.ejb.Mail` value object) |

Resources: `resources/application.yml` (port 8087, Artemis `broker-url tcp://localhost:61616`, actuator health/info/metrics). `test/` is present but currently has no tests.

## Build & run

Java 21 required. `petstore-messaging:1.0.0` must be in `~/.m2` first.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
# petstore-messaging is installed by the repo build-all.sh
cd notification-service && mvn -q clean package
mvn -q test        # test/ exists but currently has no tests
# run it (start the standalone Artemis broker first: `docker compose up -d broker`, or use ../run-all.sh)
mvn spring-boot:run
```

`package` (not `install`) is enough — nothing depends on this module. It is a leaf app in `../build-all.sh` and started by `../run-all.sh`.

## API surface / UI

None beyond actuator. The exposed HTTP endpoints are `health`, `info`, and `metrics` under `/actuator`. There is no controller and no business API.

## Events (JMS)

Consumes only — publishes to nothing. It is a terminal notification sink.

| Destination | Kind | Event / payload | Fields used |
|-------------|------|-----------------|-------------|
| `InvoiceTopic` | topic (pub/sub, `topicFactory`) | `InvoiceEvent` | `orderId`, `emailId`, `userId`, `shipped`, `totalPrice`, `meta.correlationId` |
| `OrderStatusTopic` | topic (pub/sub, `topicFactory`) | `OrderStatusEvent` | `orderId`, `emailId`, `userId`, `status` (APPROVED/DENIED/COMPLETED), `totalPrice` |
| `DLQ`, `ExpiryQueue` | anycast queue (point-to-point, `queueFactory`) | raw `jakarta.jms.Message` | JMS headers/properties only (`_AMQ_ORIG_ADDRESS`, `_type`, `JMSXDeliveryCount`) |

Both mail topics deliver this service its **own copy** independently of other subscribers (`InvoiceTopic` also feeds order-processing's `InvoiceListener`). A message reaches the DLQ/ExpiryQueue because it was un-processable, so `DlqListener` only introspects and logs it at ERROR — it never deserializes the body (which would re-poison the listener).

**Email subjects must match the legacy MDBs exactly** (`OrderMailComposer`, under test):
- shipped → `Java Pet Store Order Shipped: <orderId>` (legacy `MailInvoiceMDB`)
- backorder (`shipped=false`) → `Java Pet Store Order Delayed: <orderId>`
- APPROVED / DENIED → `Java Pet Store Order Status: <orderId>` (legacy `MailOrderApprovalMDB`)
- COMPLETED → `Java Pet Store Order COMPLETED: <orderId>` (legacy `MailCompletedOrderMDB`)

**Key invariants:** mail listeners must use `topicFactory` (pub/sub fan-out); `DlqListener` uses `queueFactory` and must never throw or re-publish; handlers are idempotent (JMS is at-least-once). To send real email, add a `JavaMailSender`-backed bean named `smtpMailSender` — `LoggingMailSender` backs off automatically. `OrderMailComposer.recipient` falls back to `<userId>@petstore.invalid` when `emailId` is blank (never throws on a missing address).

## Auth / security

None. This service has no HTTP business surface and no `spring-security` dependency — it authenticates only to the Artemis broker as a JMS consumer. The mail adapter is a `MailSender` PORT (dev default: log-only, no SMTP/infra).

## See also

- [`CLAUDE.md`](CLAUDE.md) — module guide + invariants
- [`docs/LLD.md`](docs/LLD.md) — class + sequence design
- [`.claude/skills/notification-service/SKILL.md`](.claude/skills/notification-service/SKILL.md) — per-app skill
- [`../.claude/skills/petstore-dev/SKILL.md`](../.claude/skills/petstore-dev/SKILL.md) — repo-wide skill (JMS contract, build/run)
- [`../DECISIONS.md`](../DECISIONS.md) · [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md) (closes gaps H3/H4/L7)
