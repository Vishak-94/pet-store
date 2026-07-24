---
name: petstore-messaging
description: Conventions for the shared JMS contract library petstore-messaging (com.petstore.messaging). Use when working with JMS destinations (PurchaseOrderQueue, ApprovedOrderQueue, InvoiceTopic, OrderStatusTopic), the event schema/records (PurchaseOrderEvent incl. Line + ContactInfo, OrderApprovedEvent, InvoiceEvent, OrderStatusEvent), the EventMeta envelope, the ONE shared MessagingConfig / queueFactory / topicFactory, adding or changing an event, the TYPE_IDS "_type" header type-id routing map, or the EventSerializationTest wire-format contract. Trigger terms: JMS, queue, topic, destination, event, _type, TYPE_IDS, MessagingConfig, MessagePublisher, envelope, MappingJackson2MessageConverter, publish/consume, backward compatibility.
---

# petstore-messaging — module skill

The shared **JMS contract library** — no port, imported by every service. It is the single
source of truth for destination names + kind, the event envelope + records, and the `_type`
id → class routing map. Package `com.petstore.messaging`, flat layout (`src/`, `test/`).

Read first: this module's `../../../CLAUDE.md` and `../../../docs/LLD.md` (class + destinations
table + sequence). Shared platform conventions (build/run, hexagonal layering, the JMS
contract section, parity rule) are in the repo skill `petstore-dev`
(`../../../../.claude/skills/petstore-dev/SKILL.md`). Rationale: `../../../../DECISIONS.md`.

## What lives here

- `Destinations` — the four `Destination` constants (`PURCHASE_ORDER`, `APPROVED_ORDER` are
  queues; `INVOICE`, `ORDER_STATUS` are topics). `Destination(name, boolean topic)` with
  `queue()`/`topic()` factories.
- `EventMeta` (envelope: `eventId`, `type`, `occurredAt`, `correlationId`) + `Events.meta(...)`
  factory.
- `events/` — `PurchaseOrderEvent` (nested `Line`, `ContactInfo`), `OrderApprovedEvent`
  (nested `Line`), `InvoiceEvent`, `OrderStatusEvent`; each has a `TYPE` constant.
- `MessagingConfig` — the ONE `@Configuration`: `TYPE_IDS` map, `jacksonJmsMessageConverter`,
  `queueFactory` (pubSub=false), `topicFactory` (pubSub=true).
- `MessagePublisher` — thin `publish(Destination, event)`.

## How to add a new event (the checklist)

Do all of these in one change, or producers/consumers will silently drift:

1. **Create the record** in `events/`, embedding `EventMeta meta` as the first component, plus
   the domain payload. Use plain `record`s (no Spring/JPA/Jackson annotations).
2. **Add a `TYPE` constant** — a short, stable logical id string (e.g. `"OrderStatus"`). This
   is both the logical type and the JMS `_type` header value; never rename it once shipped.
3. **Register it in `MessagingConfig.TYPE_IDS`** — `Xxx.TYPE, Xxx.class`. This map is the
   routing source of truth; an unregistered event cannot be deserialized on receive.
4. **Add a destination** to `Destinations` if it needs a new one — choose queue vs topic
   (below).
5. **Extend `EventSerializationTest`** — a round-trip assertion for the new record and, if you
   added a type, keep `typeIdMap_coversAllEvents` covering it.
6. **Nullable fields** — see the backward-compat rule below.

## Queue vs topic — how to choose

- **Queue** (`Destination.queue(...)`, point-to-point, exactly one consumer): a **command** —
  "do this once". `PurchaseOrderQueue`, `ApprovedOrderQueue`.
- **Topic** (`Destination.topic(...)`, pub/sub, fan-out to every subscriber): a broadcast
  **fact** — "this happened, whoever cares reacts". `InvoiceTopic`, `OrderStatusTopic`.

The kind is stored on the `Destination` record; `MessagePublisher` and the listener factories
read `Destination.topic()` to route. Consumers wire the matching factory:
`@JmsListener(destination = "...", containerFactory = "queueFactory")` for queues,
`"topicFactory"` for topics.

## The envelope

Every event embeds `EventMeta` (no generic wrapper — Jackson needs concrete types). Build it
with `Events.meta(TYPE, correlationId)` (pass the request's `X-Correlation-Id`/MDC so one
trace spans HTTP → JMS) or `Events.meta(TYPE)` when there is no correlation id. `eventId` is
unique per message so consumers can dedup at-least-once redelivery.

## Why there is ONE MessagingConfig

Services must **not** hand-roll a per-service `JmsConfig`. Import this module's
`MessagingConfig` (component scan or `@Import`) to get the JSON converter + `queueFactory`/
`topicFactory`. One config means one `TYPE_IDS` map shared by producers and consumers, so a
`_type` id can never mean different things on two sides. Per-service copies were the drift bug
this library removes (repo skill, hexagonal rule 3).

## The backward-compat rule

JMS is at-least-once and messages can be in-flight across a rolling deploy, so an older
producer's JSON must still deserialize on a newer consumer. Therefore: **add new event fields
as nullable; never reorder, rename, or remove existing fields, and never rename a `TYPE`.**
Example pattern: `PurchaseOrderEvent.shipTo`/`billTo` and `ContactInfo.streetName2` are
nullable by design.

## After-commit + idempotency (not enforced here)

`MessagePublisher` sends immediately. The after-commit discipline (only publish once the DB
transaction commits) lives in each producing service's **gateway** via a
`TransactionSynchronization` — do not add transactional coupling into this library. Consumers
must be **idempotent**, deduping on `EventMeta.eventId`.

## Build & the rebuild-dependents rule

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd petstore-messaging && mvn -q clean install   # install: every service imports 1.0.0 from ~/.m2
mvn -q test                                      # EventSerializationTest
```

**Changing this library requires rebuilding every dependent** (all 8 services import it) — a
stale `~/.m2` copy is the usual cause of "producer and consumer disagree on the JSON".
`build-all.sh` installs it first.

## See also

- Module guide: `../../../CLAUDE.md`
- LLD (class + destinations table + sequence): `../../../docs/LLD.md`
- Repo skill (JMS contract section): `../../../../.claude/skills/petstore-dev/SKILL.md`
- Parity baseline: `../../../../docs/PARITY_AUDIT.md` — ADRs: `../../../../DECISIONS.md`
