# customer-service — customer PII / profile / account / credit-card domain service

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8081` · **Package:** `com.petstore.customer` · **Legacy origin:** Pet Store `customer` component (account/contact, profile preferences, credit card)

## What it does

Owns the **customer aggregate** — the customer's domain data only — migrated from the legacy Pet Store customer component:

- `Account` — contact/billing details plus a `status` (`active`/`disabled`)
- `Profile` — preferences (`preferredLanguage`, `favoriteCategory`, `myListPreference`, `bannerPreference`)
- `CreditCard` — card number / type / expiry

The aggregate is keyed by an opaque `userId` (a UUID minted by auth-service) and persisted in a single `customer` table.

It does **not** own credentials or tokens. auth-service (`:8086`) is the only credential store and token issuer. customer-service is **verify-only**: it validates Bearer tokens with a bundled RS256 public key, and at registration it *provisions* a `USER` credential in auth-service via `AuthClient`.

## Layout

Maven reactor (`customer-service-parent`, packaging `pom`, version `1.0.0`) aggregating two single-versioned modules:

| Sub-module | Artifact | Role |
|-----------|----------|------|
| `client/` | `customer-service-client` | Thin importable SDK: endpoint constants + DTOs + `CustomerServiceClient`. Depends only on spring-web/spring-context + jakarta.validation-api (no Boot starter). See its [README](client/README.md). |
| `app/`    | `customer-service` | The Spring Boot service. Depends on `customer-service-client` (reuses its DTOs + endpoint constants so the server can't drift from clients) and `auth-client`. |

The `app` module uses non-standard source roots (`src/`, `test/`, `resources/`). Package layout under `app/src/com/petstore/customer/`:

```
domain/          Account, Profile, CreditCard, Customer   (framework-free value objects)
service/         CustomerService, DuplicateAccountException
repository/      CustomerRepository (port)
repository/jpa/  CustomerEntity (@Entity adapter), JpaCustomerRepository (adapter),
                 SpringDataCustomerRepositories (Spring Data)
web/             CustomerController, RestExceptionHandler
security/        SecurityConfig (verify-only)
observability/   CorrelationIdFilter
CustomerServiceApplication
```

## Build & run

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd customer-service && mvn -q clean install      # builds client then app; install so other modules can import the client
cd customer-service && mvn -q -pl app spring-boot:run   # start the service on :8081
cd customer-service && mvn -q -pl app test       # run app tests only
```

Data is stored in file-based H2 (`jdbc:h2:file:./data/customer`, overridable via `CUSTOMER_DB_PATH`) so PII/profile survives restarts; schema comes from `schema.sql` (`ddl-auto: none`) and `data.sql` re-seeds idempotently via `MERGE`. The `dev` profile (`application-dev.yml`) is the only place the H2 console is enabled.

## API surface

Paths and DTOs are single-sourced from the client SDK (`CustomerServiceEndpoints`, `CustomerDtos`).

| Method & path | Auth | Description |
|---|---|---|
| `POST /register` | public | Validate the required field set, provision a `USER` credential in auth-service, store the aggregate keyed by the returned `userId`. `201 {userId, status:"registered"}`; `400` on missing/invalid fields, `409` on duplicate userName. |
| `GET /customer/{id}` | owner or ADMIN | Fetch the aggregate (`CustomerView`, card masked). `404` when no such customer. |
| `PUT /customer/{id}/account` | owner or ADMIN | Replace the account/contact slice; profile + card preserved. Returns refreshed view. |
| `PUT /customer/{id}/profile` | owner or ADMIN | Replace the profile-preferences slice; account + card preserved. |
| `PUT /customer/{id}/card` | owner or ADMIN | Replace the credit-card slice; account + profile preserved. Returned view is masked. |

Registration required-field set (server guard `requireRegistrationFields`, mirroring legacy `CustomerHTMLAction`): `account` plus `familyName, givenName, streetName1, city, state, zipCode, telephone`; and `creditCard` plus `cardNumber, cardType, expiryDate`. Optional (never required): `email`, `country`, `streetName2`. This is in addition to bean-validation on `RegisterRequest` (`@NotBlank` userName, password `@Size(min=4,max=25)`, `@Email` email).

## Auth / security

- **Verify-only** (`SecurityConfig`): stateless session policy, CSRF disabled, `AuthJwtFilter` (from `auth-client`) validates Bearer tokens against the bundled RS256 public key (`AuthPublicKey.bundled()`).
- `POST /register` and `/actuator/**` are public; every other request requires a valid token.
- **Object-level authorization** (`requireOwnerOrAdmin`): the token's `userId` must equal the path `{id}`, or the caller must hold `ROLE_ADMIN` — an IDOR guard so an authenticated user can't read/overwrite another customer's PII/card by changing the URL id (`403` vs `404` kept distinct).
- **Card masking**: reads return `cardMasked` (`**** **** **** 1111`), never the raw PAN. Storage is plaintext by parity decision (PCI tokenisation is a post-migration improvement — see `../DECISIONS.md`).
- **Observability**: `CorrelationIdFilter` assigns/propagates an `X-Correlation-Id` per request (reusing an inbound header when present), places it in the SLF4J MDC (rendered as `cid=...` in the log pattern), and echoes it back on the response so logs can be stitched across services.

## Data

- Single `customer` table (`schema.sql`); no `app_user` table — credentials live in auth-service.
- `user_id` is the opaque UUID from auth-service and the primary key.
- Framework-free domain objects (`Account/Profile/CreditCard/Customer`) are mapped to storage only by `CustomerEntity` (the `@Entity` adapter); keep persistence annotations out of the domain.
- Invariants: new customers get legacy `Profile.defaults()` (`en_US`, `favoriteCategory=null`, `myListPreference=true`, `bannerPreference=true`); `Account.status` seeds to `active`.

## See also

- Client SDK: [client/README.md](client/README.md)
- Claude guide: [CLAUDE.md](CLAUDE.md)
- Design + diagrams: `docs/LLD.md`
- Parity baseline (H8/M7/M4/M5): [../docs/PARITY_AUDIT.md](../docs/PARITY_AUDIT.md)
- Architecture rationale / ADRs: [../DECISIONS.md](../DECISIONS.md)
