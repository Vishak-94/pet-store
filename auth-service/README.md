# auth-service — central Identity Provider (IdP) for the Pet Store fleet

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8086` · **Package:** `com.petstore.authsvc` · **Legacy origin:** Java Pet Store sign-on component / `UserEJB`

## What it does
- The **only token issuer** in the fleet — holds the RS256 private key and is the sole `signWith(privateKey)` caller anywhere.
- The **only credential store** — all users (`USER` customers, `SUPPLIER`, `ADMIN` staff) live in one `account` table; only authentication data lives here (profiles/cards stay in customer-service).
- Authenticates logins (BCrypt) and provisions credentials, then mints a short-lived RS256 JWT (`sub`, `uid`, `roles`, `iat`/`exp`).
- Every other service is a pure verifier: it imports `auth-client`, holds only the public key, and physically cannot mint a token.

## Layout
Maven reactor: a parent POM (`packaging=pom`) with two sub-modules. `pom.xml` lists `client` **before** `app` because `app` depends on `auth-client`.

- **`app/`** — artifactId `auth-service`, the runnable Spring Boot IdP (this README).
- **`client/`** — artifactId `auth-client`, the importable verify + login SDK ([client README](client/README.md)).

Key packages in `app/src/com/petstore/authsvc/`:

| Class | Role |
|-------|------|
| `AuthServiceApplication` | `@SpringBootApplication` entry point |
| `web/AuthController` | `POST /auth/login` → issue RS256 token |
| `web/AccountController` | `POST /auth/accounts` → provision a credential |
| `service/AuthService` | BCrypt credential check (only password-checker) |
| `security/JwtIssuer` | mints RS256 with the **private** key |
| `security/SecurityConfig` | stateless; login/accounts/actuator public, else deny |
| `domain/AccountEntity` | JPA `@Entity` for table `account` |
| `domain/AccountRepository` | `JpaRepository<AccountEntity, String>` |

## Build & run
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./generate-keys.sh                      # run once from repo root — creates the RSA keypair
cd auth-service && mvn -q clean install  # builds client then app; installs both to ~/.m2
mvn -pl app spring-boot:run              # run the IdP on :8086  (or repo ./run-all.sh)
```
`install` (not just `package`) is required because every verifier service imports `auth-client:1.0.0` from `~/.m2` — build auth-service first. Java 21 is required (`java.version=21`).

Tests: `mvn -q -pl app test` (`AccountControllerTest`), `mvn -q -pl client test` (`JwtVerifierTest`).

## API surface
Both endpoints are public in `SecurityConfig`; downstream services call them via `AuthClient`, not hand-rolled HTTP.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/auth/login` | authenticate → `{token, tokenType:Bearer, userId, roles}` or `401` |
| POST | `/auth/accounts` | provision a credential → `201 {userId, role, status}`, `400 invalid_request`, or `409 duplicate_account` |

Provision validation (legacy `UserEJB` parity, must not be weakened): rejects blank/null userName, null/empty password, `userName.length() > 25`, `password.length() > 25`, or userName containing `%`/`*` (mirrors `MAX_USERID_LENGTH` / `MAX_PASSWD_LENGTH`).

## Auth / security
- Roles: `USER`, `SUPPLIER`, `ADMIN`, carried in the token's `roles` claim and surfaced downstream as `ROLE_*` authorities.
- **RS256, issue-side.** This is the issuer: `JwtIssuer` loads `auth-private-key.pem` (PKCS#8) and signs. Verifiers hold only the public key — never HMAC (that would leak the secret).
- Passwords are BCrypt via `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (`{bcrypt}` prefix).
- Stateless (`SessionCreationPolicy.STATELESS`), CSRF disabled — a headless JSON API.
- Token claims: `sub`=userName, `uid`=stable userId, `roles`=list, `iat`/`exp` (`auth.jwt.ttl-seconds`, default 3600). Downstream services read the token from the `Authorization: Bearer` header or the `jwt` cookie.

## Data
H2 in-memory DB (`jdbc:h2:mem:auth`), `spring.sql.init.mode=always`, `ddl-auto=none`. `schema.sql` creates the `account` table (PK `user_name`, opaque `userId`); `data.sql` MERGEs three BCrypt-hashed seed logins: `j2ee/j2ee` (USER), `supplier/supplier` (SUPPLIER), `admin/admin` (ADMIN).

## Key management
`./generate-keys.sh` (repo root) generates an RSA-2048 keypair, copies the PKCS#8 private key to `app/resources/auth-private-key.pem` (gitignored) and the public key to `client/resources/petstore-auth-public.pem` (committed — safe to share). Never commit the private key.

## See also
- [`CLAUDE.md`](CLAUDE.md) — authoritative guide, invariants
- [`client/README.md`](client/README.md) — the `auth-client` verify SDK
- [`docs/LLD.md`](docs/LLD.md) — class + sequence design · root [`DECISIONS.md`](../DECISIONS.md) — ADRs
- [repo README](../README.md)
