# customer-service — Claude guide

Migrated Pet Store **customer domain** microservice. Owns customer *domain data only*
(account/contact, profile preferences, credit card). Spring Boot 3.3.5 / Java 21,
package root `com.petstore.customer`. Runs on **:8081**.

> Read the repo skill first: `../.claude/skills/petstore-dev/SKILL.md`. This file is
> scoped to customer-service only; do not document or touch other modules.

## Purpose & boundary

- **Owns:** the customer aggregate — `Account` (contact/billing + `status`), `Profile`
  (preferences), `CreditCard` — keyed by an opaque `userId` (a UUID minted by
  auth-service). Persisted in the single `customer` table.
- **Does NOT own:** credentials or tokens. **auth-service (:8086) is the only credential
  store and the only token issuer.** customer-service is **verify-only**: it holds the
  bundled RS256 public key and validates Bearer tokens via `auth-client`'s `AuthJwtFilter`.
  At registration it *provisions* a credential in auth-service (role `USER`) via `AuthClient`.

## Module layout (Maven reactor)

`customer-service-parent` (`pom.xml`, packaging `pom`) aggregates two modules, built and
single-versioned together (`1.0.0`):

| Sub-module | Artifact | Role |
|-----------|----------|------|
| `client/` | `customer-service-client` | Thin importable SDK: endpoint constants + DTOs + `CustomerServiceClient`. **Depends only on spring-web/spring-context + jakarta.validation-api** (no Boot starter). |
| `app/`    | `customer-service` | The Spring Boot service. Depends on `customer-service-client` (reuses its DTOs + endpoint constants so server can't drift from clients) and `auth-client`. |

`app` source lives under non-standard roots (see `app/pom.xml`): `src/`, `test/`,
`resources/`. Package layout inside `app/src/com/petstore/customer/`:

```
domain/       Account, Profile, CreditCard, Customer   (framework-free value objects)
service/      CustomerService, DuplicateAccountException
repository/   CustomerRepository (port)
repository/jpa/  CustomerEntity (@Entity adapter), JpaCustomerRepository (adapter),
                 CustomerJpaRepository (Spring Data)
web/          CustomerController, RestExceptionHandler
security/     SecurityConfig (verify-only)
observability/ CorrelationIdFilter
CustomerServiceApplication
```

## Build & test (this module only)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd customer-service && mvn -q clean install     # builds client then app; install: other modules import the client
cd customer-service && mvn -q -pl app test      # run app tests only
```
Tests: `app/test/.../CustomerServiceTest` (registration smoke — provisions credential,
stores aggregate keyed by returned userId) and `HardeningTest` (@Valid error shape,
duplicate→409, actuator health, correlation-id header echo). `AuthClient` is `@MockBean`
in both — do not require a live auth-service to run tests.

## Invariants (do not break)

1. **Domain data ONLY.** No credential/token storage or issuance here. `schema.sql` has no
   `app_user` table. Auth is verify-only (`SecurityConfig` + `AuthJwtFilter`).
2. **Framework-free domain.** `Account/Profile/CreditCard/Customer` are plain final classes
   with no Spring/JPA/Jackson annotations. Persistence mapping lives *only* in
   `CustomerEntity` (the `@Entity` adapter). Keep them separate.
3. **Profile defaults = legacy `(en_US, null, true, true)`.** `Profile.defaults()` returns
   `preferredLanguage="en_US"`, `favoriteCategory=null`, `myListPreference=true`,
   `bannerPreference=true`. Applied to every new customer at registration (parity fix H8 —
   see PARITY_AUDIT). Do not revert to opt-off booleans.
4. **`Account.status`** is `Account.ACTIVE` (`"active"`) or `Account.DISABLED` (`"disabled"`),
   seeded `active` at creation (parity fix M7). The 2-arg-less constructor defaults it to
   ACTIVE; `CustomerEntity.toDomain()` defaults a null column to ACTIVE.
5. **Registration required-field set** (server guard `requireRegistrationFields`, mirrors
   legacy `CustomerHTMLAction`): `account` object plus `familyName, givenName, streetName1,
   city, state, zipCode, telephone`; and `creditCard` object plus `cardNumber, cardType,
   expiryDate`. **Optional (never required):** `email`, `country`, `streetName2`. A
   missing/blank required field → HTTP 400. This is *in addition* to bean-validation on
   `RegisterRequest` (`@NotBlank` userName, password `@Size(min=4,max=25)`, `@Email` email).
6. **Card is masked on read.** `toView` returns `cardMasked` (`**** **** **** 1111`), never
   the raw PAN. Storage is plaintext by parity decision (PCI tokenisation is a post-migration
   improvement — see DECISIONS.md).
7. **Contract is single-sourced.** Endpoint paths + request/response DTOs come from the
   `client` SDK; the controller imports them. Keep public DTO/signature changes backward
   compatible.

## Client contract (`customer-service-client`)

`CustomerServiceClient` (default base URL `http://localhost:8081`) forwards the caller's
Bearer token on protected calls:

- `register(RegisterRequest)` → `POST /register` (public; true on 2xx, throws on 400/409)
- `getCustomer(id, bearer)` → `GET /customer/{id}` (empty on 404)
- `updateAccount(id, AccountDto, bearer)` → `PUT /customer/{id}/account`
- `updateProfile(id, ProfileDto, bearer)` → `PUT /customer/{id}/profile`
- `updateCard(id, CardDto, bearer)` → `PUT /customer/{id}/card`

All updates return the refreshed `CustomerView`. Update semantics: each replaces exactly its
one slice (account / profile / card) and preserves the other two (`CustomerService.update*`).

## See also

- Design + diagrams: `docs/LLD.md`
- Per-module conventions skill: `.claude/skills/customer-service/SKILL.md`
- Repo skill: `../.claude/skills/petstore-dev/SKILL.md`
- Parity baseline (H8/M7/M4/M5 here): `../docs/PARITY_AUDIT.md`
- Architecture rationale / ADRs: `../DECISIONS.md`
