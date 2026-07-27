# auth-client — auth-service client SDK

> Importable SDK jar for auth-service. Part of the Pet Store migration ([repo README](../../README.md)).

## What it provides
The verify-only counterpart to the auth-service IdP. It ships the **public key only** — it can validate an RS256 token but has no code path to mint one. Imported by every verifier service:
- `JwtVerifier` — verifies RS256 tokens using only the bundled public key.
- `AuthJwtFilter` — a ready-to-wire Spring `OncePerRequestFilter` that reads the token from the `Authorization: Bearer` header or the `jwt` cookie, verifies it, and populates the `SecurityContext` with `ROLE_*` authorities. Invalid/expired → anonymous (context cleared, no `401` thrown).
- `AuthClaims` — the shared claim record (`userId`, `username`, `roles`).
- `AuthClient` — a thin login/provision client that delegates to auth-service over HTTP.
- `AuthPublicKey` / `PemKeys` — load the bundled `petstore-auth-public.pem`.

## Maven coordinates
```xml
<dependency>
    <groupId>com.petstore</groupId>
    <artifactId>auth-client</artifactId>
    <version>1.0.0</version>
</dependency>
```
(Requires Java 21. Depends on jjwt `0.12.6`, spring-web, spring-security-web.)

## Key classes
| Class | Purpose |
|-------|---------|
| `JwtVerifier` | `verify(token)` → `AuthClaims`; construct from `PublicKey` or `fromPem(...)` |
| `AuthPublicKey` | `bundled()` → the library's committed public key |
| `AuthJwtFilter` | Spring filter; `JWT_COOKIE = "jwt"`; verify-only |
| `AuthClaims` | record `(userId, username, roles)` — claims `uid`, `roles` |
| `AuthClient` | `login(user, pass)` / `provision(user, pass, role)`; `DEFAULT_BASE_URL = http://localhost:8086` |

## Usage
Wire verification into a `SecurityFilterChain`:
```java
var verifier = new JwtVerifier(AuthPublicKey.bundled());
http.addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
```
Log in on the user's behalf:
```java
var client = new AuthClient();                       // http://localhost:8086
Optional<AuthClient.LoginResult> r = client.login("j2ee", "j2ee");
r.ifPresent(res -> use(res.token(), res.userId(), res.roles()));
```

## See also
- [auth-service README](../README.md) · [`CLAUDE.md`](../CLAUDE.md) — invariants (verifiers only verify)
- [repo README](../../README.md)
