# notification-service — Claude guide

Customer **email notifications** for the migrated Pet Store. Port **8087**, package
`com.petstore.notification`. It is a **pure topic subscriber**: it consumes order events
off the shared broker and "sends" the customer an email. It **owns no data, exposes no
business API, and publishes nothing** — a consumer-only leaf. It restores the legacy
customer-relations mailer MDBs (`MailInvoiceMDB`, `MailOrderApprovalMDB`,
`MailCompletedOrderMDB`).

> Shared conventions (build/run, hexagonal layering, the JMS event contract, ports, the
> parity rule) live in the repo skill `../.claude/skills/petstore-dev/SKILL.md`. This file
> only covers what is specific to notification-service. See also `docs/LLD.md` (class +
> sequence design), root `../DECISIONS.md` (ADRs), and root `../docs/PARITY_AUDIT.md`
> (legacy behavioural baseline — this service closes gaps H3, H4, L7).

## Layout

Flat module (no reactor, no sub-modules): `src/`, `resources/`, and `test/` (currently
empty). Everything is under `com.petstore.notification`.

```
notification-service/
  pom.xml                 Spring Boot 3.3.5 app; deps: web, actuator, artemis, petstore-messaging
  resources/
    application.yml       port 8087; artemis broker-url tcp://localhost:61616; actuator health/info/metrics
  src/com/petstore/notification/
    NotificationServiceApplication.java   @SpringBootApplication (scans notification + messaging)
    InvoiceNotificationListener.java      @JmsListener InvoiceTopic  → composer.fromInvoice → send
    OrderStatusNotificationListener.java  @JmsListener OrderStatusTopic → composer.fromStatus → send
    mail/
      MailSender.java          PORT — void send(Email)
      LoggingMailSender.java   default dev adapter (logs the email; @ConditionalOnMissingBean smtpMailSender)
      OrderMailComposer.java   builds Email from events; holds the legacy subject strings + fromStatus()
      Email.java               record(to, subject, body) — legacy mailer.ejb.Mail value object
```

There is no controller and no persistence layer here by design — the two `@JmsListener`
beans are the only entry points, `actuator` is the only HTTP surface.

## Build & test (this module only)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
# petstore-messaging:1.0.0 must be in ~/.m2 first (build-all.sh installs it):
cd notification-service && mvn -q clean package
mvn -q test          # test/ is present but currently has no tests
```

`package` (not `install`) is enough — nothing depends on this module. Run it with
`mvn spring-boot:run` or the repo `./run-all.sh` (start `petstore-app-v1` first: it hosts
the embedded Artemis broker this service connects to). It is in `build-all.sh`'s leaf-app
list.

## Events consumed (consumes only — see the JMS contract in the repo skill)

| Topic | Event | Fields used |
|-------|-------|-------------|
| `InvoiceTopic` | `InvoiceEvent` | `orderId`, `emailId`, `userId`, `shipped`, `totalPrice`, `meta.correlationId` |
| `OrderStatusTopic` | `OrderStatusEvent` | `orderId`, `emailId`, `userId`, `status` (APPROVED/DENIED/COMPLETED), `totalPrice` |

Both are **topics (pub/sub)**: this service gets its **own copy** independently of the
other subscribers (`InvoiceTopic` also feeds order-processing's `InvoiceListener`).

## Invariants (do not break)

1. **Topic subscriber, not queue consumer.** Both listeners MUST use
   `containerFactory = "topicFactory"` (from the shared `MessagingConfig`). Using the
   `queueFactory` would break pub/sub fan-out and steal messages from the sibling
   subscriber. Never hand-roll JMS config here — it comes from `petstore-messaging`.
2. **`MailSender` is a PORT; `LoggingMailSender` is the dev adapter.** Notification logic
   depends only on the `MailSender` interface. The default adapter just logs the email
   (no SMTP/infra). To send real email, add a JavaMailSender-backed bean **named**
   `smtpMailSender` — `LoggingMailSender` backs off automatically via
   `@ConditionalOnMissingBean(name = "smtpMailSender")`. Do not put transport code in the
   listeners or the composer.
3. **Email subject strings must match the legacy MDBs exactly** (parity — `OrderMailComposer`):
   - shipped → `"Java Pet Store Order Shipped: " + orderId`  (legacy `MailInvoiceMDB`)
   - backorder (`shipped=false`) → `"Java Pet Store Order Delayed: " + orderId`
   - APPROVED / DENIED → `"Java Pet Store Order Status: " + orderId`  (legacy `MailOrderApprovalMDB`)
   - COMPLETED → `"Java Pet Store Order COMPLETED: " + orderId`  (legacy `MailCompletedOrderMDB`)
   The COMPLETED subject is distinct on purpose (parity gaps H4/L7 in `../docs/PARITY_AUDIT.md`).
   Do not "simplify" these strings — they are the observable behaviour under test.
4. **Idempotent.** JMS is at-least-once, so a redelivered event re-sends the email; keep
   handlers side-effect-safe (composing + logging is naturally idempotent). If a real
   sender is added, dedupe on `meta.eventId`/`orderId`.
5. **Consumes only; publishes nothing.** This service must never publish to any
   destination. It is a terminal notification sink.
6. **Missing address is tolerated.** `OrderMailComposer.recipient` falls back to
   `<userId>@petstore.invalid` when `emailId` is blank (legacy also tolerated a missing
   address) — never throw on a missing email.

## Adding a new notification type

Event (in `petstore-messaging`) → `@JmsListener` (topic + `topicFactory`) →
`OrderMailComposer` method producing an `Email` with a parity subject → `mailSender.send`.
Keep composition (testable, no transport) separate from sending. See `docs/LLD.md` and the
per-app skill under `.claude/skills/notification-service/`.
