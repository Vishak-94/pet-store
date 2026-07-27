---
name: notification-service
description: Conventions and how-to for the migrated Pet Store notification-service (port 8087, com.petstore.notification) — the consumer-only service that emails customers. Use when working on customer email notifications, the InvoiceTopic/OrderStatusTopic subscribers, order shipped/delayed/status/completed emails, the OrderMailComposer or MailSender/LoggingMailSender, adding a new notification type, or wiring a real SMTP mail sender. Trigger terms: notification, email, mail, customer notification, invoice email, order status email, order shipped, order completed, MailSender, OrderMailComposer, InvoiceNotificationListener, OrderStatusNotificationListener.
---

# notification-service — developer skill

Consumer-only leaf service (port **8087**, package `com.petstore.notification`) that turns
order events into customer emails. Two JMS **topic** subscribers → `OrderMailComposer` →
`MailSender`. It owns no data, exposes no business API, and **publishes nothing**. Restores
the legacy customer-relations mailers `MailInvoiceMDB` / `MailOrderApprovalMDB` /
`MailCompletedOrderMDB`.

> Repo-wide conventions (build/run, hexagonal layering, the JMS contract, the parity rule)
> are in the repo skill **`petstore-dev`** (`../../../../.claude/skills/petstore-dev/SKILL.md`).
> Module design + diagrams: `../../../docs/LLD.md`. Module Claude guide:
> `../../../CLAUDE.md`. Parity baseline: repo `docs/PARITY_AUDIT.md` (gaps H3/H4/L7).

## Conventions

### Topic (pub/sub) subscription
Both listeners subscribe with `@JmsListener(destination = "...", containerFactory =
"topicFactory")`. `topicFactory` comes from the shared `com.petstore.messaging.MessagingConfig`
(imported via `petstore-messaging` + scanned by `NotificationServiceApplication`). Never use
`queueFactory` and never hand-roll a `ConnectionFactory`/`JmsListenerContainerFactory` here —
topic delivery gives this service its **own copy** of each event, independent of the other
subscribers on the same topic.

### MailSender is a PORT
Listeners depend on the `MailSender` interface (`void send(Email)`), never on a concrete
sender. The default adapter is `LoggingMailSender`, which just logs the composed email (no
SMTP, no infra) and is annotated `@ConditionalOnMissingBean(name = "smtpMailSender")`.

**To add a real mail adapter:** create a `MailSender` bean **named** `smtpMailSender` (e.g. a
`SmtpMailSender` wrapping Spring's `JavaMailSender`, guarded by config), add the mail starter
dep, and configure SMTP in `resources/application.yml`. `LoggingMailSender` backs off
automatically — no change to listeners or composer. Keep all transport code in the adapter.

### Adding a new notification type
Follow the pipeline: **event → listener → composer subject → send**.
1. Add/extend the event record in `petstore-messaging` (update `MessagingConfig.TYPE_IDS`
   and the serialization round-trip test — see the repo skill).
2. Add a `@JmsListener` bean (topic + `topicFactory`) here that calls the composer, then
   `mailSender.send(...)`.
3. Add an `OrderMailComposer` method returning an `Email(to, subject, body)`; resolve the
   recipient via the existing `recipient(emailId, userId)` fallback.
4. Keep composition free of transport so it stays unit-testable; keep handlers idempotent
   (JMS is at-least-once — a redelivered event must be safe to re-process).

### Legacy subject-string parity (do not change)
The subject prefixes in `OrderMailComposer` are copied verbatim from the legacy MDBs and are
observable behaviour under parity:

| Email | Subject prefix | Event / condition |
|-------|----------------|-------------------|
| Shipped | `Java Pet Store Order Shipped: ` | `InvoiceEvent` `shipped=true` |
| Delayed | `Java Pet Store Order Delayed: ` | `InvoiceEvent` `shipped=false` |
| Status | `Java Pet Store Order Status: ` | `OrderStatusEvent` APPROVED/DENIED |
| Completed | `Java Pet Store Order COMPLETED: ` | `OrderStatusEvent` COMPLETED |

The distinct COMPLETED subject is intentional (parity gaps H4/L7) — do not fold it back into
the shipped/status subjects.

## Build & run

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
# needs petstore-messaging:1.0.0 in ~/.m2 (build-all.sh installs it; broker is a standalone container)
cd notification-service && mvn -q clean package
mvn spring-boot:run        # or the repo ./run-all.sh (start the Artemis broker container first: docker compose up -d broker)
```

Do not modify legacy `petstore1.3.1_02/` (read-only spec) or other modules. This module is
flat: `src/`, `resources/`, `test/`.
