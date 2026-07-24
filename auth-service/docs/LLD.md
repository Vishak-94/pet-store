# auth-service — Low-Level Design

Central Identity Provider (IdP) for the migrated Pet Store, port **8086**. It is the single
RS256 token **issuer** and the single **credential store**; every other service is a pure
verifier holding only the public key (bundled in `auth-client`). Shared platform conventions
live in the repo skill `../../.claude/skills/petstore-dev/SKILL.md`; architecture rationale in
`../../DECISIONS.md`; the legacy behavioural baseline in `../../docs/PARITY_AUDIT.md`.

The reactor has two artifacts:

- **`auth-service` (app)** — the runnable IdP. Holds the RSA private key, checks passwords,
  mints tokens, owns the `account` table.
- **`auth-client` (client)** — importable verify SDK. Holds only the public key. Provides a
  token verifier, a ready-to-wire Spring filter, shared claim types, and a thin login/provision
  HTTP client.

## Class design — app (`com.petstore.authsvc`)

```mermaid
classDiagram
    class AuthServiceApplication {
        +main(String[] args)$
    }

    class AuthController {
        -AuthService auth
        -JwtIssuer jwt
        +login(LoginRequest) ResponseEntity
    }
    class AuthController_LoginRequest {
        <<record>>
        +String userName
        +String password
    }

    class AccountController {
        -int MAX_USERID_LENGTH$
        -int MAX_PASSWD_LENGTH$
        -AccountRepository accounts
        -PasswordEncoder encoder
        +provision(ProvisionRequest) ResponseEntity
    }
    class AccountController_ProvisionRequest {
        <<record>>
        +String userName
        +String password
        +String role
    }

    class AuthService {
        -AccountRepository accounts
        -PasswordEncoder encoder
        +authenticate(String, String) Optional~AccountEntity~
    }

    class JwtIssuer {
        <<@Service>>
        -PrivateKey privateKey
        -long ttlMillis
        +issue(String username, String userId, List roles) String
        -loadPrivateKey(Resource)$ PrivateKey
    }

    class SecurityConfig {
        <<@Configuration>>
        +filterChain(HttpSecurity) SecurityFilterChain
        +passwordEncoder() PasswordEncoder
    }

    class AccountEntity {
        <<@Entity account>>
        -String userName
        -String password
        -String userId
        -String role
        +getUserName() String
        +getUserId() String
        +getRole() String
    }
    class AccountRepository {
        <<interface / port>>
        JpaRepository~AccountEntity, String~
    }

    AuthController --> AuthService : authenticate
    AuthController --> JwtIssuer : issue
    AuthController ..> AccountEntity : reads
    AccountController --> AccountRepository : save/existsById
    AuthService --> AccountRepository : findById
    AuthService --> AccountEntity : returns
    AccountRepository --> AccountEntity : manages
    AuthController *-- AuthController_LoginRequest
    AccountController *-- AccountController_ProvisionRequest
    SecurityConfig ..> AuthService : provides PasswordEncoder
```

`JwtIssuer` is the **only** holder of the private key (loaded from `auth-private-key.pem`,
PKCS#8) and the only `signWith` call. `AccountRepository` is the persistence port; the JPA
adapter is provided by Spring Data over `AccountEntity`.

## Class design — client (`com.petstore.auth.client`)

```mermaid
classDiagram
    class AuthJwtFilter {
        <<OncePerRequestFilter>>
        -JwtVerifier verifier
        +doFilterInternal(req, res, chain)
        -extractToken(req)$ String
    }

    class JwtVerifier {
        -PublicKey publicKey
        +JwtVerifier(PublicKey)
        +fromPem(String)$ JwtVerifier
        +verify(String token) AuthClaims
    }

    class AuthPublicKey {
        +bundled()$ PublicKey
    }

    class PemKeys {
        +rsaPublicKey(String pem)$ PublicKey
    }

    class AuthClaims {
        <<record>>
        +String userId
        +String username
        +List~String~ roles
    }

    class AuthClient {
        +String LOGIN$
        +String ACCOUNTS$
        +String DEFAULT_BASE_URL$
        -RestClient http
        +login(String, String) Optional~LoginResult~
        +provision(String, String, String) String
    }
    class AuthClient_LoginResult {
        <<record>>
        +String token
        +String userId
        +List~String~ roles
    }

    AuthJwtFilter --> JwtVerifier : verify
    AuthJwtFilter ..> AuthClaims : populates SecurityContext
    JwtVerifier --> AuthClaims : returns
    JwtVerifier ..> PemKeys : fromPem
    AuthPublicKey ..> PemKeys : rsaPublicKey
    AuthPublicKey ..> JwtVerifier : key source
    AuthClient *-- AuthClient_LoginResult
```

Only the **public** key ever reaches this module (`petstore-auth-public.pem`, loaded by
`AuthPublicKey.bundled()`). There is no signing path, so importers can verify but not forge.

## Sequence — login → issue RS256 token

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller (UI / AuthClient)
    participant AC as AuthController
    participant AS as AuthService
    participant R as AccountRepository
    participant PE as PasswordEncoder (BCrypt)
    participant JI as JwtIssuer (private key)

    C->>AC: POST /auth/login {userName, password}
    AC->>AS: authenticate(userName, password)
    AS->>R: findById(userName)
    R-->>AS: Optional<AccountEntity>
    AS->>PE: matches(rawPassword, account.password)
    PE-->>AS: true / false
    AS-->>AC: Optional<AccountEntity>
    alt no account or password mismatch
        AC-->>C: 401 {error: invalid_credentials}
    else authenticated
        AC->>JI: issue(userName, userId, [role])
        JI->>JI: Jwts.builder().sub/uid/roles/iat/exp.signWith(privateKey)
        JI-->>AC: RS256 token
        AC-->>C: 200 {token, tokenType:Bearer, userId, roles}
    end
```

## Sequence — provision (with validation)

```mermaid
sequenceDiagram
    autonumber
    participant S as Service (e.g. customer-service via AuthClient)
    participant ACtl as AccountController
    participant R as AccountRepository
    participant PE as PasswordEncoder (BCrypt)

    S->>ACtl: POST /auth/accounts {userName, password, role}
    alt userName blank/null OR password null/empty
        ACtl-->>S: 400 {error: invalid_request}
    else userName>25 OR password>25 OR userName has '%' or '*'
        ACtl-->>S: 400 {error: invalid_request}
    else valid
        ACtl->>R: existsById(userName)
        alt already exists
            R-->>ACtl: true
            ACtl-->>S: 409 {error: duplicate_account}
        else new
            R-->>ACtl: false
            ACtl->>ACtl: role = role.isBlank() ? "USER" : role<br/>userId = UUID.randomUUID()
            ACtl->>PE: encode(password)
            PE-->>ACtl: {bcrypt}hash
            ACtl->>R: save(new AccountEntity(userName, hash, userId, role))
            ACtl-->>S: 201 {userId, role, status: provisioned}
        end
    end
```

## Sequence — downstream service verifying a token via AuthJwtFilter

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser / client
    participant F as AuthJwtFilter (in verifier service)
    participant V as JwtVerifier (public key)
    participant SC as SecurityContextHolder
    participant H as Protected handler

    B->>F: request with Authorization: Bearer <t> OR jwt cookie
    F->>F: extractToken(req) — header else cookie
    alt no token
        F->>H: chain.doFilter (anonymous)
    else token present
        F->>V: verify(token)
        V->>V: Jwts.parser().verifyWith(publicKey).parseSignedClaims
        alt valid signature + not expired
            V-->>F: AuthClaims{userId, username, roles}
            F->>SC: set UsernamePasswordAuthenticationToken<br/>authorities = ROLE_+each role
            F->>H: chain.doFilter (authenticated)
        else invalid / expired / forged
            V-->>F: throws JwtException
            F->>SC: clearContext() (stays anonymous)
            F->>H: chain.doFilter
        end
    end
```

## Endpoints

| Method | Path             | Auth | Request | Success | Errors |
|--------|------------------|------|---------|---------|--------|
| POST   | `/auth/login`    | public | `{userName, password}` | 200 `{token, tokenType:Bearer, userId, roles}` | 401 `invalid_credentials` |
| POST   | `/auth/accounts` | public | `{userName, password, role}` | 201 `{userId, role, status:provisioned}` | 400 `invalid_request`, 409 `duplicate_account` |
| GET    | `/actuator/**`   | public | — | health/info/metrics | — |

All other paths are `denyAll()` (`SecurityConfig`). Service is stateless (no sessions), CSRF
disabled.

## Token claims

| Claim  | Source | Meaning |
|--------|--------|---------|
| `sub`  | `AccountEntity.userName` | login name |
| `uid`  | `AccountEntity.userId`   | stable opaque id other services reference |
| `roles`| `[AccountEntity.role]`   | drives `ROLE_*` authorities on verifiers |
| `iat`/`exp` | now / now + `auth.jwt.ttl-seconds` (default 3600) | validity window |

Adding a claim/role is a **coordinated three-file change**: `JwtIssuer.issue` (write),
`JwtVerifier.verify` (read), and the `AuthClaims` record (carry). Keep them in lockstep or
verifiers silently drop the new data.

## Key management

`generate-keys.sh` (repo root) creates an RSA-2048 keypair: the PKCS#8 private key is copied to
`app/resources/auth-private-key.pem` (gitignored) and the public key to
`client/resources/petstore-auth-public.pem` (committed and safe to share). `JwtIssuer` reads
the private key via `auth.jwt.private-key` (`application.yml`); verifiers load the bundled
public key with `AuthPublicKey.bundled()`. Rotating keys = rerun the script and rebuild both
modules so verifiers pick up the new public key.

## Design decisions / invariants

- **Asymmetric (RS256), not symmetric.** Verifiers must never be able to mint tokens; only the
  IdP holds the signing key. `auth-client` has no signing code path at all.
- **One account store for all realms.** `USER` (customer), `SUPPLIER`, `ADMIN` share the
  `account` table; only authentication data lives here (profile/cards are in customer-service).
- **Legacy `UserEJB` validation preserved.** 25-char caps on userName/password and the ban on
  `%`/`*` in userName mirror the legacy EJB and are pinned by `AccountControllerTest`.
- **BCrypt via delegating encoder** (`{bcrypt}` prefix) — the only password check in the fleet.
- **Provision is the only write path** into the store besides `data.sql` seeds
  (`j2ee/j2ee` USER, `supplier/supplier` SUPPLIER, `admin/admin` ADMIN).
