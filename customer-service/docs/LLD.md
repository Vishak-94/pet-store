# customer-service — Low-Level Design

Migrated Pet Store **customer domain** microservice (Spring Boot 3.3.5 / Java 21, package
`com.petstore.customer`, port **8081**). This document covers the class design, data model,
request flows, and the design decisions/invariants specific to this module. For repo-wide
conventions see `../../.claude/skills/petstore-dev/SKILL.md`; for the parity baseline see
`../../docs/PARITY_AUDIT.md`; for ADRs see `../../DECISIONS.md`.

## Overview

customer-service owns the **customer aggregate** — contact/billing `Account` (with a
`status`), `Profile` preferences, and a `CreditCard` — keyed by an opaque `userId` (a UUID
minted by auth-service). It is a hexagonal (ports & adapters) service:

- **Domain** (`domain/`): framework-free value objects, no Spring/JPA/Jackson.
- **Application** (`service/`): `CustomerService` orchestrates registration + updates.
- **Inbound adapter** (`web/`): `CustomerController` + `RestExceptionHandler`.
- **Outbound adapters**: `repository/jpa/*` (persistence) and `auth-client`'s `AuthClient`
  (credential provisioning) behind the service.
- **Contract** (`client/` sub-module): the importable `customer-service-client` SDK —
  endpoint constants + DTOs + `CustomerServiceClient`, reused by the server so the two can
  never drift.

**Boundary:** credentials and tokens live in **auth-service**. customer-service is
verify-only (bundled RS256 public key via `AuthJwtFilter`) and provisions credentials at
registration. There is no `app_user` table here.

## Class diagram — server (domain + application + adapters)

```mermaid
classDiagram
    class Customer {
        -String userId
        -Account account
        -Profile profile
        -CreditCard creditCard
        +getUserId() String
        +getAccount() Account
        +getProfile() Profile
        +getCreditCard() CreditCard
    }
    class Account {
        +String ACTIVE
        +String DISABLED
        -String givenName
        -String familyName
        -String email
        -String telephone
        -String streetName1
        -String streetName2
        -String city
        -String state
        -String zipCode
        -String country
        -String status
        +getStatus() String
    }
    class Profile {
        -String preferredLanguage
        -String favoriteCategory
        -boolean myListPreference
        -boolean bannerPreference
        +defaults() Profile
    }
    note for Profile "defaults() = (en_US, null, true, true)"
    class CreditCard {
        -String cardNumber
        -String cardType
        -String expiryDate
    }
    Customer *-- Account
    Customer *-- Profile
    Customer *-- CreditCard

    class CustomerService {
        <<Service>>
        +register(userName, password, Account, CreditCard) Customer
        +register(userName, password, Account) Customer
        +findByUserId(userId) Optional~Customer~
        +updateAccount(userId, Account) Customer
        +updateProfile(userId, Profile) Customer
        +updateCreditCard(userId, CreditCard) Customer
    }
    class DuplicateAccountException {
        <<RuntimeException>>
    }
    class AuthClient {
        <<auth-client>>
        +provision(userName, password, role) String
    }
    class CustomerRepository {
        <<interface / port>>
        +findByUserId(userId) Optional~Customer~
        +save(Customer) Customer
    }
    class JpaCustomerRepository {
        <<@Repository adapter>>
        +findByUserId(userId) Optional~Customer~
        +save(Customer) Customer
    }
    class CustomerEntity {
        <<Entity - table customer>>
        +fromDomain(Customer) CustomerEntity$
        +toDomain() Customer
    }
    class CustomerJpaRepository {
        <<Spring Data JpaRepository>>
    }
    class CustomerController {
        <<@RestController>>
        +register(RegisterRequest) 201
        +get(id) CustomerView
        +updateAccount(id, AccountDto) CustomerView
        +updateProfile(id, ProfileDto) CustomerView
        +updateCard(id, CardDto) CustomerView
        -requireRegistrationFields(req)
        -toView(Customer) CustomerView
    }
    class RestExceptionHandler {
        <<@RestControllerAdvice>>
    }
    class SecurityConfig {
        <<verify-only>>
    }

    CustomerController --> CustomerService
    CustomerController ..> DuplicateAccountException
    RestExceptionHandler ..> DuplicateAccountException
    CustomerService --> CustomerRepository
    CustomerService --> AuthClient
    CustomerService ..> DuplicateAccountException
    CustomerRepository <|.. JpaCustomerRepository
    JpaCustomerRepository --> CustomerJpaRepository
    JpaCustomerRepository ..> CustomerEntity
    CustomerJpaRepository ..> CustomerEntity
    CustomerController ..> Customer
```

## Class diagram — client SDK (`customer-service-client`, separate module)

```mermaid
classDiagram
    class CustomerServiceClient {
        +CustomerServiceClient()
        +CustomerServiceClient(baseUrl)
        +CustomerServiceClient(RestClient)
        +login(userName, password) Optional~AuthResult~
        +register(RegisterRequest) boolean
        +getCustomer(id, bearer) Optional~CustomerView~
        +updateAccount(id, AccountDto, bearer) CustomerView
        +updateProfile(id, ProfileDto, bearer) CustomerView
        +updateCard(id, CardDto, bearer) CustomerView
    }
    class CustomerServiceEndpoints {
        +String DEFAULT_BASE_URL
        +String LOGIN
        +String REGISTER
        +String CUSTOMER
        +String ACCOUNT
        +String PROFILE
        +String CARD
    }
    note for CustomerServiceEndpoints "REGISTER=/register  CUSTOMER=/customer/{id}\nACCOUNT|PROFILE|CARD=/customer/{id}/(account|profile|card)"
    class CustomerDtos {
        <<DTO container>>
    }
    class RegisterRequest {
        +String userName
        +String password
        +AccountDto account
        +CardDto creditCard
    }
    note for RegisterRequest "userName @NotBlank @Size max 25\npassword @NotBlank @Size 4..25\naccount @Valid"
    class AccountDto {
        +String givenName
        +String familyName
        +String email
        +String telephone
        +String streetName1
        +String streetName2
        +String city
        +String state
        +String zipCode
        +String country
    }
    note for AccountDto "email is @Email"
    class ProfileDto {
        +String preferredLanguage
        +String favoriteCategory
        +boolean myListPreference
        +boolean bannerPreference
    }
    class CardDto {
        +String cardNumber
        +String cardType
        +String expiryDate
    }
    class CustomerView {
        +String userId
        +Map account
        +Map profile
        +String cardMasked
    }
    class AuthResult {
        +String token
        +String customerId
        +List~String~ roles
    }
    CustomerServiceClient --> CustomerServiceEndpoints
    CustomerServiceClient ..> CustomerDtos
    CustomerDtos *-- RegisterRequest
    CustomerDtos *-- AccountDto
    CustomerDtos *-- ProfileDto
    CustomerDtos *-- CardDto
    CustomerDtos *-- CustomerView
    CustomerDtos *-- AuthResult
    RegisterRequest *-- AccountDto
    RegisterRequest *-- CardDto
```

> The server (`app`) imports this SDK and reuses `CustomerServiceEndpoints` +
> `CustomerDtos` directly in `CustomerController`, so endpoint paths and JSON shapes are
> single-sourced. `CustomerController` maps SDK DTOs to/from the framework-free domain
> (`toAccount`/`toCard`/`toView`); it never exposes the domain types over the wire.

## Data model

Single flattened table `customer` (`app/resources/schema.sql`; `ddl-auto: none`, schema
applied from SQL, H2 in-memory `jdbc:h2:mem:customer`). The legacy CMP graph
(Customer/Account/Profile/ContactInfo/Address/CreditCard) is collapsed into typed columns —
mapped by `CustomerEntity`.

| Group | Columns |
|-------|---------|
| key | `user_id` PK (VARCHAR 40, opaque UUID from auth-service) |
| account/contact | `given_name, family_name, email, telephone, street1, street2, city, state, zip_code, country` |
| **status** | `status` NOT NULL DEFAULT `'active'` (`active`/`disabled`) |
| profile | `preferred_language, favorite_category, my_list_pref, banner_pref` |
| card | `card_number, card_type, card_expiry` |

`data.sql` seeds **no** credentials (the central IdP holds all logins); customer rows are
created at registration. `CustomerEntity.toDomain()` defaults a null `status` to `active`
and returns a null `CreditCard` when all three card columns are null.

## Sequence — register (validation + defaults + provisioning)

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant C as CustomerController
    participant EX as RestExceptionHandler
    participant S as CustomerService
    participant A as AuthClient (auth-service :8086)
    participant R as CustomerRepository (JPA)

    Caller->>C: POST /register (RegisterRequest)
    Note over C: @Valid bean-validation<br/>(@NotBlank userName, password 4..25, @Email)
    alt bean-validation fails
        C-->>EX: MethodArgumentNotValidException
        EX-->>Caller: 400 {error:validation_failed, detail:{field:msg}, correlationId}
    end
    C->>C: requireRegistrationFields(req)
    Note over C: required: family/given name, street1, city,<br/>state, zip, telephone + card number/type/expiry<br/>(email, country, street2 optional)
    alt required field missing/blank
        C-->>Caller: 400 "Missing required registration fields: ..."
    end
    C->>S: register(userName, password, Account, CreditCard)
    S->>A: provision(userName, password, "USER")
    alt user name taken (409)
        A-->>S: HttpClientErrorException.Conflict
        S-->>EX: DuplicateAccountException
        EX-->>Caller: 409 {error:duplicate_account, correlationId}
    end
    A-->>S: userId (UUID)
    Note over S: Customer(userId, account, Profile.defaults()=en_US/null/true/true, card)
    S->>R: save(Customer)
    R-->>S: Customer
    S-->>C: Customer
    C-->>Caller: 201 {userId, status:"registered"}
```

## Sequence — read account (toView + masking)

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant F as AuthJwtFilter (verify-only)
    participant C as CustomerController
    participant S as CustomerService
    participant R as CustomerRepository (JPA)

    Caller->>F: GET /customer/{id} (Bearer token)
    F->>F: verify RS256 with bundled public key
    F->>C: authenticated request
    C->>S: findByUserId(id)
    S->>R: findByUserId(id)
    R-->>S: Optional~Customer~
    alt not found
        S-->>C: Optional.empty
        C-->>Caller: 404 {error:Not Found}
    end
    S-->>C: Customer
    Note over C: toView → account map (incl. status) +<br/>profile map + cardMasked (**** **** **** 1111)
    C-->>Caller: 200 CustomerView
```

## Sequence — update account / profile / card

```mermaid
sequenceDiagram
    autonumber
    participant Caller
    participant F as AuthJwtFilter
    participant C as CustomerController
    participant S as CustomerService
    participant R as CustomerRepository (JPA)

    Caller->>F: PUT /customer/{id}/{account|profile|card} (Bearer + DTO)
    F->>C: verified request
    alt account
        C->>S: updateAccount(id, Account)
    else profile
        C->>S: updateProfile(id, Profile)
    else card
        C->>S: updateCreditCard(id, CreditCard)
    end
    S->>R: findByUserId(id)  (require existing)
    alt no such customer
        R-->>S: empty
        S-->>C: IllegalArgumentException
        C-->>Caller: 404 {error:not_found}
    end
    R-->>S: existing Customer
    Note over S: rebuild Customer replacing ONLY the one slice,<br/>preserving the other two
    S->>R: save(Customer)
    R-->>S: Customer
    S-->>C: Customer
    C-->>Caller: 200 CustomerView (refreshed)
```

## Endpoints

| Method | Path | Auth | Request | Response | Errors |
|--------|------|------|---------|----------|--------|
| POST | `/register` | public | `RegisterRequest` | 201 `{userId, status}` | 400 validation/missing fields, 409 duplicate |
| GET | `/customer/{id}` | Bearer | — | 200 `CustomerView` | 404 not found |
| PUT | `/customer/{id}/account` | Bearer | `AccountDto` | 200 `CustomerView` | 404 |
| PUT | `/customer/{id}/profile` | Bearer | `ProfileDto` | 200 `CustomerView` | 404 |
| PUT | `/customer/{id}/card` | Bearer | `CardDto` | 200 `CustomerView` | 404 |

`SecurityConfig` permits `/register`, `/h2-console/**`, `/actuator/**`; everything else
requires a verified token. Session policy is STATELESS; verification uses `AuthJwtFilter`
with the bundled public key. `LOGIN` (`/auth/login`) is a client-SDK constant pointing at
the auth flow — customer-service itself issues no tokens.

## Design decisions & invariants

1. **Domain data only; verify-only auth.** No credential/token storage or issuance. auth-service
   is the sole IdP; `AuthClient.provision` creates the credential (role `USER`) at registration.
2. **Framework-free domain vs JPA adapter.** `Account/Profile/CreditCard/Customer` carry no
   persistence annotations; `CustomerEntity` is the only `@Entity` and maps both directions.
3. **Profile defaults `(en_US, null, true, true)`** — legacy `ProfileLocalHome` parity (fix H8).
4. **`Account.status` `active`/`disabled`**, seeded `active` (fix M7); null column → `active`.
5. **Registration required-field guard** mirrors legacy `CustomerHTMLAction`
   (`extractContactInfo`/`extractCreditCard`): required contact + card fields; `email`,
   `country`, `streetName2` optional. Complements bean-validation on `RegisterRequest`.
6. **Card masking on read**; plaintext storage retained by parity decision (PCI tokenisation
   deferred — see DECISIONS.md).
7. **Slice-preserving updates** — each update replaces exactly one of account/profile/card.
8. **Single-sourced contract** — server reuses the client SDK's endpoints + DTOs.
9. **Uniform error shape + correlation id** — `RestExceptionHandler` emits
   `{status, error, detail, correlationId}`; `CorrelationIdFilter` sets/echoes
   `X-Correlation-Id`.
