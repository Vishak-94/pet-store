# customer-service-client — customer-service client SDK

> Importable SDK jar for customer-service. Part of the Pet Store migration ([repo README](../../README.md)).

## What it provides

A thin, importable Java SDK that owns the **customer-service API contract** so callers never hardcode URLs or JSON shapes:

- Endpoint path constants (`CustomerServiceEndpoints`)
- Request/response DTOs (`CustomerDtos`)
- A typed HTTP client (`CustomerServiceClient`) built on Spring `RestClient`

It depends only on spring-web/spring-context + the Bean Validation API — no Spring Boot starter — so any consumer (e.g. the storefront monolith) can import it without pulling in the server's runtime. The server app also depends on this jar and reuses its DTOs + endpoint constants, so the server contract can't drift from what clients see.

## Maven coordinates

```xml
<dependency>
    <groupId>com.petstore</groupId>
    <artifactId>customer-service-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

Build/install it (and the parent reactor) with `cd customer-service && mvn -q clean install`.

## Key classes

- **`CustomerServiceClient`** — the entry point. Construct with the default base URL (`http://localhost:8081`), a per-environment base URL, or a preconfigured `RestClient`. Uses bounded connect (2s) / read (5s) timeouts so a hung service can't tie up caller threads. Operations:
  - `register(RegisterRequest)` → `POST /register` (public; `true` on 2xx, throws `HttpClientErrorException` on 400/409)
  - `getCustomer(id, bearerToken)` → `GET /customer/{id}` (empty on 404)
  - `updateAccount(id, AccountDto, bearerToken)` → `PUT /customer/{id}/account`
  - `updateProfile(id, ProfileDto, bearerToken)` → `PUT /customer/{id}/profile`
  - `updateCard(id, CardDto, bearerToken)` → `PUT /customer/{id}/card`
  - `login(userName, password)` → `POST /auth/login` (returns `Optional<AuthResult>`, empty on 401)

  Protected calls forward the caller's Bearer token; all updates return the refreshed `CustomerView`.
- **`CustomerServiceEndpoints`** — endpoint path constants (the published contract) and wire field-name constants (`userId`, `status`, `token`, ...). Base URL is not hardcoded here; it's a `CustomerServiceClient` constructor arg.
- **`CustomerDtos`** — records: `RegisterRequest`, `AccountDto`, `CardDto`, `ProfileDto`, `CustomerView` (card masked), `AuthResult`. DTOs carry Bean Validation constraints (length caps, `@Email`, `@NotBlank`, password `@Size(min=4,max=25)`); the validation provider is supplied by the app.

## Usage

```java
CustomerServiceClient client = new CustomerServiceClient(); // http://localhost:8081

// Register (public)
var account = new CustomerDtos.AccountDto("Jane", "Doe", "jane@example.com", "555-0100",
        "1 Main St", null, "Portland", "OR", "97201", "USA");
var card = new CustomerDtos.CardDto("4111 1111 1111 1111", "VISA", "12/29");
client.register(new CustomerDtos.RegisterRequest("jdoe", "s3cret", account, card));

// Read / update (Bearer-token protected; forward the caller's session JWT)
Optional<CustomerDtos.CustomerView> view = client.getCustomer(userId, bearerToken);
client.updateProfile(userId,
        new CustomerDtos.ProfileDto("en_US", "DOGS", true, false), bearerToken);
```

## See also

- Service README: [../README.md](../README.md)
- Claude guide: [../CLAUDE.md](../CLAUDE.md)
- Repo README: [../../README.md](../../README.md)
