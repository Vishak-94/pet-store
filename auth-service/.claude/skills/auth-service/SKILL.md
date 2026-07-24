---
name: auth-service
description: Conventions for the Pet Store central IdP (auth-service, port 8086) and its importable auth-client SDK. Use when working on authentication, login, token issuance, the RS256 signing keypair, the single account/credential store, provisioning accounts, roles/claims, or when a service needs to verify JWTs / adopt auth-client + AuthJwtFilter. Triggers: auth-service, auth-client, JwtIssuer, JwtVerifier, AuthJwtFilter, AuthClient, AuthClaims, AccountController, AccountEntity, RS256, private key, public key, /auth/login, /auth/accounts, provision, Bearer token, jwt cookie, generate-keys.sh, seed logins (j2ee/supplier/admin).
---

# auth-service — developer skill

The central Identity Provider on port **8086**: the ONLY token issuer (holds the RSA private
key) and the ONLY credential store. Every other service is a pure verifier. Read the module
guide `../../../CLAUDE.md` and the design `../../../docs/LLD.md` before changing anything here.
Shared platform conventions (build/run, hexagonal layering, JMS, parity rule) are in the repo
skill `petstore-dev` (`../../../../.claude/skills/petstore-dev/SKILL.md`); rationale in
`../../../../DECISIONS.md`, parity baseline in `../../../../docs/PARITY_AUDIT.md`.

## Where things live

- **Issuance (private key)** lives ONLY in `app` — `com.petstore.authsvc`:
  `security/JwtIssuer.issue(...)` is the sole `signWith(privateKey)` call; `web/AuthController`
  drives login; `service/AuthService` is the only password check (BCrypt).
- **Verification (public key)** lives ONLY in `client` — `com.petstore.auth.client`:
  `JwtVerifier.verify(...)`, `AuthJwtFilter`, `AuthPublicKey.bundled()`, `PemKeys`, `AuthClaims`.
- **Account store**: `domain/AccountEntity` (`account` table) + `domain/AccountRepository`
  (JPA port). Writes only via `web/AccountController.provision` or `resources/data.sql` seeds.
- Never add the private key or any signing path to `client` — that would let verifiers forge.

## Adding a claim or role

Coordinated three-file change, in lockstep, or verifiers silently drop the data:
1. `JwtIssuer.issue` — add the `.claim("x", ...)`.
2. `client/.../JwtVerifier.verify` — read it back.
3. `client/.../AuthClaims` (record) — carry the new field.
Roles come from `AccountEntity.role` and become `ROLE_<role>` authorities in `AuthJwtFilter`.

## Provision validation set (legacy UserEJB parity — do not weaken)

`AccountController.provision` returns `400 invalid_request` when: userName null/blank; password
null/empty; `userName.length() > 25`; `password.length() > 25`; userName contains `%` or `*`.
Duplicate userName → `409 duplicate_account`. Missing/blank role defaults to `USER`. These caps
(`MAX_USERID_LENGTH`/`MAX_PASSWD_LENGTH = 25`) are pinned by `app/test/.../AccountControllerTest`
— extend that test if you touch the rules.

## How a service adopts auth-client + AuthJwtFilter

1. Depend on `com.petstore:auth-client:1.0.0` (build auth-service with `mvn install` first).
2. Wire the verify-only filter in the service's `SecurityFilterChain`:
   ```java
   var verifier = new JwtVerifier(AuthPublicKey.bundled());
   http.addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
   ```
   The filter reads `Authorization: Bearer <t>` OR a `jwt` cookie, verifies RS256, and sets the
   SecurityContext with `ROLE_*` authorities. Invalid/expired → anonymous (no exception thrown).
3. For login/provision on the user's behalf, use `AuthClient` (`login`, `provision`,
   `DEFAULT_BASE_URL = http://localhost:8086`). Never re-implement the HTTP calls or hold creds.

## Key handling

`./generate-keys.sh` (repo root) makes the RSA-2048 keypair: PKCS#8 private key →
`app/resources/auth-private-key.pem` (gitignored), public key →
`client/resources/petstore-auth-public.pem` (committed, shareable). Never commit the private
key. Rotating keys = rerun the script and rebuild both modules so verifiers refresh the public key.

## Build & test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd auth-service && mvn -q clean install   # client then app; publishes auth-client to ~/.m2
mvn -q -pl app test        # AccountControllerTest
mvn -q -pl client test     # JwtVerifierTest
```

Seeds (H2 in-memory): `j2ee/j2ee` (USER), `supplier/supplier` (SUPPLIER), `admin/admin` (ADMIN).
