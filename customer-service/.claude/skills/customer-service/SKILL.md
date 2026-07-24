---
name: customer-service
description: >-
  Conventions for the migrated Pet Store customer-service (Spring Boot 3.3.5 / Java 21,
  package com.petstore.customer, port 8081). Use when working on customer DOMAIN data —
  customer profile / account / contact / billing address / credit card, registration and
  its required-field validation, profile preference defaults, Account status
  (active/disabled), or the customer-service-client SDK (getCustomer / updateAccount /
  updateProfile / updateCard). Triggers: customer, profile, account, credit card, register,
  CustomerController, CustomerService, CustomerEntity, CustomerDtos, CustomerServiceClient,
  Profile.defaults. NOT for credentials/tokens/login (those live in auth-service).
---

# customer-service — module skill

Customer domain microservice. Owns the customer aggregate (account/contact + billing,
profile preferences, credit card) keyed by an opaque `userId` (UUID from auth-service).
Read the repo skill `../../../../.claude/skills/petstore-dev/SKILL.md` for build/run,
hexagonal layering, and the parity rule that governs the whole system.

Design + diagrams: `../../../docs/LLD.md`. Claude guide: `../../../CLAUDE.md`.

## Layering (domain → port → adapter)

- **Domain** (`app/src/.../domain/`): `Account`, `Profile`, `CreditCard`, `Customer` are
  plain final classes — **no Spring/JPA/Jackson annotations**. Never add persistence or
  serialization concerns here.
- **Port**: `repository/CustomerRepository` (`findByUserId`, `save`).
- **Adapters**: `repository/jpa/CustomerEntity` (the only `@Entity`, table `customer`,
  `fromDomain`/`toDomain`), `JpaCustomerRepository` (implements the port),
  `CustomerJpaRepository` (Spring Data). Keep all persistence mapping in `CustomerEntity`;
  services touch only the port.
- **Application**: `service/CustomerService` orchestrates register + slice-preserving updates.
- **Web**: `web/CustomerController` (maps SDK DTOs ↔ domain via `toAccount/toCard/toView`),
  `web/RestExceptionHandler` (uniform `{status,error,detail,correlationId}`).

## Rules specific to this module

1. **No auth here.** customer-service stores no credentials and issues no tokens.
   auth-service (:8086) is the sole IdP; `SecurityConfig` is **verify-only** (bundled RS256
   public key via `auth-client` `AuthJwtFilter`). Registration provisions a credential in
   auth-service (`AuthClient.provision(user, pass, "USER")`). There is no `app_user` table.
2. **Profile defaults = `(en_US, null, true, true)`.** `Profile.defaults()` →
   `preferredLanguage="en_US"`, `favoriteCategory=null`, `myListPreference=true`,
   `bannerPreference=true` (legacy parity, fix H8). Do not change to opt-off.
3. **`Account.status`** = `Account.ACTIVE` (`"active"`) / `Account.DISABLED` (`"disabled"`),
   seeded `active` (fix M7). Null column defaults to `active` in `toDomain()`.
4. **Registration required-field set** (`requireRegistrationFields`, mirrors legacy
   `CustomerHTMLAction`): `account` + `familyName, givenName, streetName1, city, state,
   zipCode, telephone`; `creditCard` + `cardNumber, cardType, expiryDate`. **Optional:**
   `email`, `country`, `streetName2`. Missing/blank → 400. This is *in addition* to
   `@Valid` bean-validation on `RegisterRequest` (`@NotBlank` userName, password
   `@Size(min=4,max=25)`, `@Email` email).
5. **Card masked on read** (`cardMasked`, e.g. `**** **** **** 1111`); never return raw PAN.
   Plaintext storage is a recorded parity decision — do not add PCI tokenisation as part of a
   parity change (see `../../../../DECISIONS.md`).
6. **Slice-preserving updates.** `updateAccount/updateProfile/updateCreditCard` each rebuild
   the `Customer` replacing exactly one slice and preserving the other two.

## Client / DTO contract (`client/` sub-module → `customer-service-client`)

Thin SDK (`CustomerServiceClient`, default base URL `http://localhost:8081`) that forwards
the caller's Bearer token. The **server reuses** `CustomerServiceEndpoints` + `CustomerDtos`,
so keep the contract single-sourced and backward compatible.

- `register(RegisterRequest)` → `POST /register`
- `getCustomer(id, bearer)` → `GET /customer/{id}` (empty on 404)
- `updateAccount(id, AccountDto, bearer)` → `PUT /customer/{id}/account`
- `updateProfile(id, ProfileDto, bearer)` → `PUT /customer/{id}/profile`
- `updateCard(id, CardDto, bearer)` → `PUT /customer/{id}/card`

DTOs: `RegisterRequest`, `AccountDto`, `ProfileDto`, `CardDto`, `CustomerView`, `AuthResult`.
The client module depends only on spring-web/spring-context + jakarta.validation-api (no Boot
starter); the validation *provider* is supplied by the app.

## Build & test (this module only)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd customer-service && mvn -q clean install    # client then app (install: other modules import the client)
cd customer-service && mvn -q -pl app test     # CustomerServiceTest + HardeningTest (AuthClient is @MockBean)
```

Parity baseline for this module (H8 defaults, M7 status, M4 update path, M5 registration
validation): `../../../../docs/PARITY_AUDIT.md`.
