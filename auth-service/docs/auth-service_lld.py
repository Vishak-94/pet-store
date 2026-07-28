#!/usr/bin/env python3
"""
auth-service — Low-Level-Design diagram generator.

Renders TWO diagrams into this directory using the shared house-style library:
  * auth-service_class.png/.svg  — UML class diagram, grouped by layer + the
    reusable auth-client SDK (the verify seam every other service imports).
  * auth-service_schema.png/.svg — the `account` table + the RS256 key model and
    the JWT wire contract (claims) the service issues and the SDK verifies.

House rule: every class, field, method, table, column, claim and key below is
extracted from the real source under auth-service/ — nothing is invented.
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import (
    new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND
)


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("auth-service — Class diagram (app IdP + reusable auth-client verify SDK)", rankdir="TB")

    # ── Web layer (app) ──────────────────────────────────────────────────────
    def _web(s):
        s.node("AuthController", uml_class(
            "AuthController", "@RestController",
            attrs=["auth: AuthService", "jwt: JwtIssuer"],
            methods=["login(LoginRequest): ResponseEntity<Map>"],
            kind="web",
            note="POST /auth/login — mint RS256 token"))
        s.node("AccountController", uml_class(
            "AccountController", "@RestController",
            attrs=["accounts: AccountRepository", "encoder: PasswordEncoder",
                   "MAX_USERID_LENGTH=25", "MAX_PASSWD_LENGTH=25"],
            methods=["provision(ProvisionRequest): ResponseEntity<Map>",
                     "callerIsAdmin(): boolean"],
            kind="web",
            note="POST /auth/accounts — provision credential; ADMIN token gates non-USER roles"))
        s.node("LoginRequest", uml_class(
            "LoginRequest", "record",
            attrs=["userName: String", "password: String"], kind="web"))
        s.node("ProvisionRequest", uml_class(
            "ProvisionRequest", "record",
            attrs=["userName: String", "password: String", "role: String"], kind="web"))

    # ── Service layer (app) ──────────────────────────────────────────────────
    def _svc(s):
        s.node("AuthService", uml_class(
            "AuthService", "@Service",
            attrs=["accounts: AccountRepository", "encoder: PasswordEncoder"],
            methods=["authenticate(userName, rawPassword): Optional<AccountEntity>"],
            kind="service",
            note="Only password-checker (BCrypt); empty = unknown OR mismatch"))

    # ── Security / config (app) ──────────────────────────────────────────────
    def _sec(s):
        s.node("JwtIssuer", uml_class(
            "JwtIssuer", "@Service",
            attrs=["privateKey: PrivateKey", "ttlMillis: long"],
            methods=["issue(username, userId, roles): String",
                     "loadPrivateKey(Resource): PrivateKey"],
            kind="config",
            note="SOLE holder of RSA private key; only signWith() in fleet"))
        s.node("SecurityConfig", uml_class(
            "SecurityConfig", "@Configuration",
            methods=["jwtVerifier(): JwtVerifier",
                     "filterChain(http, verifier, env): SecurityFilterChain",
                     "passwordEncoder(): PasswordEncoder"],
            kind="config",
            note="Stateless; login/accounts/actuator permitAll, else denyAll; H2 console only @dev"))

    # ── Domain + persistence port (app) ──────────────────────────────────────
    def _dom(s):
        s.node("AccountEntity", uml_class(
            "AccountEntity", "@Entity account",
            attrs=["userName: String @Id", "password: String", "userId: String @unique", "role: String"],
            methods=["getUserName()", "getPassword()", "getUserId()", "getRole()"],
            kind="entity"))
        s.node("AccountRepository", uml_class(
            "AccountRepository", "interface (port)",
            methods=["findById(id): Optional<AccountEntity>", "existsById(id)", "save(a)"],
            kind="port",
            note="extends JpaRepository<AccountEntity, String> — Spring Data adapter at runtime"))

    # ── auth-client SDK (client) — reused by every verifier service ──────────
    def _client(s):
        s.node("AuthClient", uml_class(
            "AuthClient", "HTTP client (RestClient)",
            attrs=["http: RestClient", "LOGIN=/auth/login", "ACCOUNTS=/auth/accounts",
                   "DEFAULT_BASE_URL=:8086"],
            methods=["login(userName, password): Optional<LoginResult>",
                     "provision(userName, password, role): String"],
            kind="client",
            note="Callers reuse instead of hand-rolling HTTP; bounded timeouts"))
        s.node("LoginResult", uml_class(
            "LoginResult", "record",
            attrs=["token: String", "userId: String", "roles: List<String>"], kind="client"))
        s.node("AuthJwtFilter", uml_class(
            "AuthJwtFilter", "OncePerRequestFilter",
            attrs=["verifier: JwtVerifier", "cookieName: String", "JWT_COOKIE=jwt"],
            methods=["doFilterInternal(req, res, chain)", "extractToken(req): String"],
            kind="client",
            note="Verify-only; reads Bearer header OR jwt cookie; populates SecurityContext ROLE_*"))
        s.node("JwtVerifier", uml_class(
            "JwtVerifier", "verify SDK",
            attrs=["publicKey: PublicKey"],
            methods=["verify(token): AuthClaims", "fromPem(pem): JwtVerifier"],
            kind="client",
            note="REUSED by warehouse/inventory/storefront/… — public key only, cannot sign"))
        s.node("AuthClaims", uml_class(
            "AuthClaims", "record (wire contract)",
            attrs=["userId: String", "username: String", "roles: List<String>",
                   "CLAIM_USER_ID=uid", "CLAIM_ROLES=roles"],
            kind="domain",
            note="Claim-name constants shared by issuer + verifier so they can't drift"))
        s.node("AuthPublicKey", uml_class(
            "AuthPublicKey", "bundled key loader",
            methods=["bundled(): PublicKey"],
            kind="client",
            note="Loads petstore-auth-public.pem from classpath — zero-config verifier"))
        s.node("PemKeys", uml_class(
            "PemKeys", "utility",
            methods=["rsaPublicKey(pem): PublicKey"],
            kind="client",
            note="No signing path exists here"))

    # ── Bootstrap ─────────────────────────────────────────────────────────────
    def _boot(s):
        s.node("AuthServiceApplication", uml_class(
            "AuthServiceApplication", "@SpringBootApplication",
            methods=["main(String[])"], kind="framework"))

    cluster(g, "web", "Web layer  (app · com.petstore.authsvc.web)", _web, "#EAF2FB", PALETTE["web"][1])
    cluster(g, "svc", "Service layer  (app · .service)", _svc, "#E7F4EF", PALETTE["service"][1])
    cluster(g, "sec", "Security / Config  (app · .security)", _sec, "#F2F2F2", PALETTE["config"][1])
    cluster(g, "dom", "Domain & persistence port  (app · .domain)", _dom, "#F6F0FA", PALETTE["domain"][1])
    cluster(g, "cli", "auth-client SDK  (client · com.petstore.auth.client) — imported by every verifier service",
            _client, "#E6F0FA", PALETTE["client"][1])
    cluster(g, "boot", "Bootstrap", _boot, "#F0F0F0", PALETTE["framework"][1])

    # ── Relationships (all from real source) ─────────────────────────────────
    edge(g, "AuthController", "AuthService", "depends", "authenticate")
    edge(g, "AuthController", "JwtIssuer", "depends", "issue")
    edge(g, "AuthController", "LoginRequest", "compose")
    edge(g, "AccountController", "AccountRepository", "depends", "save / existsById")
    edge(g, "AccountController", "ProvisionRequest", "compose")
    edge(g, "AuthService", "AccountRepository", "depends", "findById")
    edge(g, "AuthService", "AccountEntity", "depends", "returns")
    edge(g, "AccountRepository", "AccountEntity", "depends", "manages")

    edge(g, "JwtIssuer", "AuthClaims", "depends", "uses CLAIM_* constants")
    edge(g, "SecurityConfig", "JwtVerifier", "depends", "@Bean")
    edge(g, "SecurityConfig", "AuthJwtFilter", "depends", "addFilterBefore")

    edge(g, "AuthClient", "LoginResult", "compose")
    edge(g, "AuthJwtFilter", "JwtVerifier", "depends", "verify")
    edge(g, "AuthJwtFilter", "AuthClaims", "depends", "reads roles/username")
    edge(g, "JwtVerifier", "AuthClaims", "depends", "returns")
    edge(g, "JwtVerifier", "PemKeys", "depends", "fromPem")
    edge(g, "AuthPublicKey", "PemKeys", "depends", "rsaPublicKey")
    edge(g, "AuthPublicKey", "JwtVerifier", "flow", "key source")

    legend(g, [
        (PALETTE["web"][0], "Web / REST controller"),
        (PALETTE["service"][0], "Service (business logic)"),
        (PALETTE["config"][0], "Security / config / issuer"),
        (PALETTE["domain"][0], "Domain VO / wire contract"),
        (PALETTE["port"][0], "Persistence port (interface)"),
        (PALETTE["entity"][0], "JPA entity"),
        (PALETTE["client"][0], "auth-client SDK (reused)"),
        (PALETTE["framework"][0], "Framework / bootstrap"),
        ("#FFFFFF", "--> depends   ..> flow   <> compose"),
    ])

    render(g, "auth-service_class")


# ─────────────────────────────────────────────────────────────────────────────
# (b) SCHEMA + KEY/TOKEN DATA-MODEL DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("auth-service — Data model: account table + RS256 key model + JWT wire contract", rankdir="LR")

    # ── The one credential store (owned) ─────────────────────────────────────
    def _db(s):
        s.node("account", table_node("account", [
            ("user_name", "VARCHAR(25) NOT NULL", "pk"),
            ("password", "VARCHAR(120) NOT NULL  {bcrypt}hash", ""),
            ("user_id", "VARCHAR(40) NOT NULL  UNIQUE", ""),
            ("role", "VARCHAR(20) NOT NULL  USER|SUPPLIER|ADMIN", ""),
        ], kind="owned"))
        s.node("seeds", uml_class(
            "data.sql seeds", "MERGE KEY(user_name)",
            attrs=["j2ee / j2ee → USER",
                   "supplier / supplier → SUPPLIER",
                   "admin / admin → ADMIN"],
            kind="config",
            note="Idempotent MERGE; H2 file jdbc:h2:file:./data/auth, ddl-auto=none"))

    # ── RSA key model (asymmetric split is the security invariant) ───────────
    def _keys(s):
        s.node("privatekey", table_node("auth-private-key.pem  (app only, gitignored)", [
            ("format", "PKCS#8 / RSA-2048", "pk"),
            ("held_by", "JwtIssuer.privateKey", ""),
            ("used_for", "signWith() — mint RS256", ""),
            ("config", "auth.jwt.private-key (application.yml)", ""),
        ], kind="owned"))
        s.node("publickey", table_node("petstore-auth-public.pem  (auth-client, committed)", [
            ("format", "X.509 / RSA public", "pk"),
            ("loaded_by", "AuthPublicKey.bundled()", ""),
            ("used_for", "verifyWith() — verify only", ""),
            ("shipped_in", "auth-client jar classpath", ""),
        ], kind="external"))

    # ── JWT wire contract (envelope + claims) ────────────────────────────────
    def _token(s):
        s.node("token", table_node("JWT (RS256 compact:  header.payload.signature)", [
            ("alg", "RS256 (inferred from RSA key)", ""),
            ("sub", "AccountEntity.userName", "pk"),
            ("uid", "AccountEntity.userId (CLAIM_USER_ID)", ""),
            ("roles", "[AccountEntity.role] (CLAIM_ROLES)", ""),
            ("iat", "now", ""),
            ("exp", "now + auth.jwt.ttl-seconds (3600)", ""),
        ], kind="owned"))
        s.node("loginresp", table_node("POST /auth/login → 200 body", [
            ("token", "signed JWT string", ""),
            ("tokenType", "Bearer", ""),
            ("userId", "stable opaque id", ""),
            ("roles", "List<String>", ""),
        ], kind="owned"))
        s.node("provresp", table_node("POST /auth/accounts → 201 body", [
            ("userId", "UUID.randomUUID()", ""),
            ("role", "USER|SUPPLIER|ADMIN", ""),
            ("status", "provisioned", ""),
        ], kind="owned"))

    cluster(g, "db", "Credential store (H2, owned by auth-service)", _db, "#EAF7EE", TABLE_KIND["owned"][1])
    cluster(g, "keys", "RSA key model (asymmetric split = core security invariant)", _keys, "#F5F5F5", "#888888")
    cluster(g, "tok", "JWT wire contract (issued here, verified by auth-client)", _token, "#EAF2FB", PALETTE["web"][1])

    # relationships / provenance
    edge(g, "seeds", "account", "fk", "seeds rows")
    edge(g, "account", "token", "flow", "sub/uid/roles from row")
    edge(g, "privatekey", "token", "flow", "signs")
    edge(g, "token", "publickey", "flow", "verified by")
    edge(g, "token", "loginresp", "compose", "returned in")
    edge(g, "account", "provresp", "flow", "userId+role")

    legend(g, [
        (TABLE_KIND["owned"][0], "Owned by auth-service"),
        (TABLE_KIND["external"][0], "Shipped for verifiers (public key)"),
        (PALETTE["web"][0], "Wire contract (JWT / JSON body)"),
        (PALETTE["config"][0], "Seed data"),
        ("#2F8F46", "→ provenance / signs / verifies"),
    ])

    render(g, "auth-service_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("wrote auth-service_class.{png,svg} and auth-service_schema.{png,svg}")
