# petstore-messaging — Claude guide

Shared **JMS contract library** for the migrated Pet Store. Spring Boot 3.3.5 / Java 21,
package root `com.petstore.messaging`. **No port — this is a library, not a service.** It is
imported by every service that produces or consumes an event and is the *single source of
truth* for destination names, the event envelope + schema, and `_type` id routing.

> Shared conventions (build/run, hexagonal layering, the JMS event contract, ports, parity
> rule) live in the repo skill `../.claude/skills/petstore-dev/SKILL.md`. This file only
> covers what is specific to petstore-messaging. See also `docs/LLD.md` (class + sequence
> design), root `../DECISIONS.md` (ADRs), and root `../docs/PARITY_AUDIT.md` (legacy
> behavioural baseline).

## Purpose & boundary

- **Owns:** the JMS *contract* — destination names + kind (`Destinations`/`Destination`),
  the envelope (`EventMeta` + `Events` factory), the four event records (`events/`), the
  `_type` id map + JSON converter + listener factories (`MessagingConfig`), and a thin
  transport-agnostic publisher (`MessagePublisher`).
- **Does NOT own:** the broker (embedded Artemis lives in `petstore-app-v1` on `:61616`),
  the `ConnectionFactory` (Spring Boot autoconfigures it per importing service), or the
  after-commit publishing discipline (each service's *gateway* registers the
  `TransactionSynchronization` — this library only sends). No business logic lives here.

## Package layout (flat: `src/`, `test/`)

```
src/com/petstore/messaging/
  Destination.java        record(name, boolean topic) + queue()/topic() factories
  Destinations.java       the ONE registry of names: PURCHASE_ORDER, APPROVED_ORDER (queues),
                          INVOICE, ORDER_STATUS (topics)
  EventMeta.java          envelope record: eventId, type, occurredAt, correlationId
  Events.java             EventMeta factory: meta(type, correlationId) / meta(type)
  MessagingConfig.java    @Configuration @EnableJms — TYPE_IDS map, jacksonJmsMessageConverter,
                          queueFactory (pubSub=false), topicFactory (pubSub=true)
  MessagePublisher.java   @Component — publish(Destination, event); stamps _type; queue/topic templates
  events/
    PurchaseOrderEvent.java   TYPE="PurchaseOrder"; nested records Line + ContactInfo
    OrderApprovedEvent.java   TYPE="OrderApproved"; nested record Line
    InvoiceEvent.java         TYPE="Invoice"
    OrderStatusEvent.java     TYPE="OrderStatus"
test/com/petstore/messaging/
  EventSerializationTest.java   wire-format contract test (round-trip + type-id map + kinds)
```

## Build & test (this module only)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd petstore-messaging && mvn -q clean install    # install (not just package): every service imports this
mvn -q test                                       # runs EventSerializationTest
```

`install` is required — `petstore-messaging:1.0.0` is pulled from `~/.m2` by every service.
**Changing this library means rebuilding every dependent** (all 8 services import it); a stale
`~/.m2` copy is the usual cause of "producer and consumer disagree on JSON". `build-all.sh`
installs it first for this reason.

## Invariants (do not break)

1. **One `MessagingConfig` for the whole fleet.** Do NOT hand-roll a per-service `JmsConfig`.
   Services import this config (component scan or `@Import`) to get the JSON converter +
   `queueFactory`/`topicFactory`. Per-service copies drift and cause silent routing bugs —
   that drift is exactly what this library exists to eliminate (see repo skill, rule 3).
2. **`TYPE_IDS` is the source of truth for routing.** The `_type` header → class map in
   `MessagingConfig.TYPE_IDS` must contain **every** event type. When you add or change an
   event, update `TYPE_IDS` **and** `EventSerializationTest` in the same change. Producers
   and consumers share this one map, so they can never disagree on what a `_type` id means.
3. **New event fields must be nullable / additive.** JMS is at-least-once and messages can be
   in-flight across a deploy, so an older producer's JSON must still deserialize on a newer
   consumer. Add fields as nullable (never reorder/rename/remove); `PurchaseOrderEvent.shipTo`
   /`billTo` and `ContactInfo.streetName2` are the pattern to follow.
4. **Queue vs topic semantics are encoded in `Destination`.** Queue = point-to-point, exactly
   one consumer (commands: `PurchaseOrderQueue`, `ApprovedOrderQueue`). Topic = pub/sub,
   fan-out to every subscriber (broadcast facts: `InvoiceTopic`, `OrderStatusTopic`). The
   publisher and listener factories read `Destination.topic()` to pick pub/sub — pick the
   right kind when adding a destination; do not send a command over a topic.
5. **After-commit publishing is enforced by the SERVICES, not here.** `MessagePublisher`
   sends immediately when called. Each producing service's gateway wraps the call in a
   `TransactionSynchronization` so a rolled-back transaction never publishes. Do not try to
   add transactional coupling into this library — it is intentionally transport-only.
6. **Consumers must be idempotent.** `EventMeta.eventId` is unique per message so consumers
   can dedup at-least-once redelivery. This library provides the id; the dedup lives in
   consumers.

## Who consumes it

Every service in the fleet imports `petstore-messaging:1.0.0`. Producer→consumer flows (full
table in `docs/LLD.md`): storefront checkout (`petstore-app-v1`) → `PurchaseOrderQueue` →
order-processing-service → `ApprovedOrderQueue` → inventory-service → `InvoiceTopic` →
{order-processing-service, notification-service}; order-processing-service → `OrderStatusTopic`
→ notification-service.

## See also

- Design + diagrams (class, destinations table, sequence): `docs/LLD.md`
- Per-module conventions skill: `.claude/skills/petstore-messaging/SKILL.md`
- Repo skill (JMS contract section): `../.claude/skills/petstore-dev/SKILL.md`
- Parity baseline: `../docs/PARITY_AUDIT.md`
- Architecture rationale / ADRs: `../DECISIONS.md`
