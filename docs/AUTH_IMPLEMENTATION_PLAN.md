# ProcureMind — Authentication & Authorization Implementation Plan

> **Status: IMPLEMENTED. Phases 0 through 8 are complete and enforcement is live.**
> This remains the single source-of-truth blueprint for the OIDC-based authentication and
> role-based authorization now in place. Authentication is enforced by default at the API
> gateway, contract-service and ai-service; `AUTH_ENABLED=false` is retained only as a
> documented emergency kill switch, and the role matrix in section 12 is the one the code
> applies. Phase descriptions below are kept as the record of how the rollout was
> sequenced.

---

## 1. Objective

Introduce authentication and authorization to ProcureMind using standard protocols and
framework-supported components only:

- A dedicated **`auth-service`** built on **Spring Authorization Server** acting as an
  **OIDC provider** (issuer, discovery document, JWKS, token/authorize/userinfo/logout
  endpoints, hosted login page).
- **OAuth2 Authorization Code + PKCE** for the React SPA (`procuremind-react`).
- **OAuth2 Authorization Code** (server-side confidential client) for the Streamlit app
  (`procuremind-ui`).
- **OAuth2 Resource Server** JWT validation independently in the **API Gateway**,
  **contract-service**, and **ai-service**. Each tier validates the token itself.
- **PostgreSQL + Flyway owned by `auth-service`** for all identity data, in a dedicated
  database `procuremind_auth`.
- **Role-based authorization** (`ADMIN`, `ANALYST`, `VIEWER`) enforced at the gateway and,
  for sensitive operations, with method security in the services.
- **No trusted `X-User-*` headers** as an authentication mechanism.
- **No custom JWT / token minting code.** All token issuance, rotation, and revocation is
  handled by Spring Authorization Server.

Success = every HTTP entry point rejects unauthenticated requests once enforcement is on,
role rules are applied, both frontends complete a standards-compliant login, the Kafka
processing pipeline is unaffected, and the whole rollout can be reverted with a single
environment flag until Phase 8.

---

## 2. Current architecture relevant to authentication

Established by inspection of the repository. No security exists today.

| Area | Current state | Source |
|---|---|---|
| API Gateway | Spring Boot 4.0.7, `spring-cloud-starter-gateway-server-webmvc` (**servlet MVC**, not reactive). Pure declarative routing. **No filters, no security, no rate limiting.** Standalone Maven project — **not** in the root reactor. | `api-gateway/pom.xml`, `api-gateway/src/main/resources/application.yaml` |
| Gateway routes | `/api/contracts, /api/contracts/**` → `${CONTRACT_SERVICE_URL:http://localhost:8081}`; `/api/analysis, /api/analysis/**` → `${AI_SERVICE_URL:http://localhost:8082}`; `/health/contract`, `/health/ai` → downstream `/actuator/health`. `GatewayWelcomeController` serves `/` (HTML) and `/api` (JSON). | `api-gateway/src/main/resources/application.yaml`, `api-gateway/src/main/java/com/procuremind/api_gateway/controller/GatewayWelcomeController.java` |
| Gateway CORS | `globalcors` for `[/**]`: origins `http://localhost:5173`, `http://localhost:3000`; `allowed-headers: "*"`; `allow-credentials: true`; methods GET/POST/PUT/DELETE/PATCH/OPTIONS. Multipart limit 70 MB. | `api-gateway/src/main/resources/application.yaml` |
| contract-service | Spring Boot 4.0.7, Web MVC + JPA + Kafka + MinIO. Endpoints under `/api/contracts`: `POST /upload` (multipart `file` + `vendorName`), `GET /`, `GET /{id}`, `GET /{id}/status`. Publishes `contract.uploaded`. Port 8081, **published to host** in compose. No security. | `contract-service/**`, `docker-compose.yaml` |
| ai-service | Spring Boot **3.5.14**, Spring AI 1.1.0. Endpoints under `/api/analysis`: 10 read endpoints + `POST /chat`. Kafka listeners (`ai-processing-group`) drive parse → index → analyze. Port 8082, **published to host**. No security. Consumer tuning `max.poll.interval.ms: 1800000`, `max.poll.records: 1`. | `ai-service/**` |
| procuremind-common | Plain JAR, **no parent, no dependencies**. Contains only two Kafka event records + a no-op `Main`. | `procuremind-common/**` |
| Database | Single shared PostgreSQL 15 `procuremind_db` (compose service `postgres`, host `5433` → container `5432`, user `user` / `password`, volume `postgres_data`). **All** tables created by one Flyway migration owned by `ai-service`. Both services run `ddl-auto: validate`. | `docker-compose.yaml`, `ai-service/src/main/resources/db/migration/V1__init_schema.sql` |
| Messaging | Kafka KRaft (`confluentinc/cp-kafka:7.4.4`). Topics `contract.uploaded` / `contract.indexed` / `contract.analyzed`. **No synchronous service-to-service HTTP calls anywhere** (no Feign / RestTemplate / WebClient between services). | `docker-compose.yaml`, `procuremind-common` |
| React frontend | `procuremind-react` — React 19, Vite 8, Tailwind v4, Recharts. **No router library.** `src/api/client.js` uses plain `fetch`, no interceptors, base URL from `VITE_GATEWAY_URL` (empty → relative + Vite dev proxy to `:8080`). `src/context/AppContext.jsx` loads data on mount. Tab shell in `src/App.jsx`. **Not containerized, not in docker-compose.** No tests. | `procuremind-react/**` |
| Streamlit frontend | `procuremind-ui` — Streamlit (Python 3.13, `streamlit>=1.60.0`), single `main.py`, server-side `requests` via `safe_get_api` / `upload_contract_api` / `send_chat_api`. In compose as `procuremind-ui` (`:8501`, `GATEWAY_URL=http://api-gateway:8080`). Dockerfile hard-codes pip deps. No tests. | `procuremind-ui/**`, `docker-compose.yaml` |
| AuthN / AuthZ | **None.** No `spring-boot-starter-security` anywhere, no JWT library, no login, no users/roles, no `@PreAuthorize`, actuator `health`+`info` open with `show-details: always`. | whole repo |

**Endpoints the frontends actually call** (all via the gateway): `GET /api/contracts`,
`GET /api/contracts/{id}/status`, `POST /api/contracts/upload`,
`GET /api/analysis/dashboard-metrics`, `/financial-exposure`, `/risks/distribution`,
`GET /api/analysis/{id}`, `/{id}/toc`, `/{id}/risks`, `/node/{nodeId}`,
`POST /api/analysis/chat`.

---

## 3. Target architecture

```
                         ┌───────────────────────────────────────────┐
   Browser (React SPA)   │  auth-service  (Spring Authorization Srv)  │
   ───── Auth Code+PKCE ─▶│  issuer http://localhost:8083             │
                         │  /oauth2/authorize /oauth2/token          │
   Streamlit (server)    │  /oauth2/jwks /.well-known/openid-config  │
   ───── Auth Code ──────▶│  /userinfo /connect/logout  + /login page │
                         │  Postgres db: procuremind_auth (Flyway)   │
                         └───────────────┬───────────────────────────┘
                                         │ JWKS (validation)
        Authorization: Bearer <JWT>      │
   React / Streamlit ──────────▶ ┌───────┴────────┐
                                 │  API Gateway   │  Resource Server (validates JWT)
                                 │  :8080         │  RBAC coarse rules
                                 └───┬────────┬───┘
              Authorization header forwarded unchanged
                                 │        │
                    ┌────────────▼──┐  ┌──▼─────────────┐
                    │contract-service│  │  ai-service    │  each is its OWN
                    │  :8081         │  │  :8082         │  Resource Server
                    │  validates JWT │  │  validates JWT │  (defense in depth)
                    └───────┬────────┘  └──────┬─────────┘
                            └──── Kafka (unchanged, no security context) ────┘
```

Principles:

- **Every HTTP tier is an independent OAuth2 Resource Server.** A request that reaches
  `contract-service` or `ai-service` directly (their ports are published) is still
  rejected without a valid JWT. There is no header-based trust.
- The gateway **forwards the inbound `Authorization` header unchanged** (Spring Cloud
  Gateway Server WebMVC does not strip it and no `RemoveRequestHeader` filter is added).
- `auth-service` owns **only** identity. It never touches `procuremind_db`, never calls
  business services, and holds no contract data.
- Enforcement everywhere is gated by a single flag `AUTH_ENABLED` (default `false` until
  Phase 7). While `false`, resource-server config is loaded and a presented token is
  validated and populates the security context, but anonymous requests still pass.
- Spring Boot version split is a non-issue: JWT validation via JWKS is protocol-level and
  identical on Spring Security 6 (`ai-service`) and Spring Security 7 (gateway,
  `contract-service`, `auth-service`).

---

## 4. Authentication flow (generic OIDC Authorization Code + PKCE)

1. Client generates `code_verifier` + `code_challenge` (S256) and redirects the browser to
   `GET http://localhost:8083/oauth2/authorize?response_type=code&client_id=<id>&redirect_uri=<uri>&scope=openid%20profile%20roles&code_challenge=<challenge>&code_challenge_method=S256&state=<state>`.
2. `auth-service` renders its **hosted login page** (`/login`, form login backed by the
   `users` table + BCrypt). User authenticates.
3. `auth-service` redirects back to `redirect_uri?code=<code>&state=<state>`.
4. Client exchanges the code:
   `POST http://<auth-host>/oauth2/token` with `grant_type=authorization_code`, `code`,
   `redirect_uri`, `client_id`, `code_verifier` (and client secret for the confidential
   Streamlit client). Response: **access token (JWT, RS256)**, **refresh token**, **ID
   token**, `expires_in`.
5. Client calls the API with `Authorization: Bearer <access_token>`.
6. Each resource server (gateway, contract-service, ai-service) validates the JWT against
   the cached **JWKS** (`/oauth2/jwks`): signature, `iss`, `exp`, optional `aud`, then maps
   the `roles` claim to authorities.
7. On access-token expiry the client uses the **refresh token** (`grant_type=refresh_token`)
   to obtain a new pair. Refresh-token **rotation with reuse detection** is enabled.
8. Logout: RP-initiated logout via `GET /connect/logout?id_token_hint=...&post_logout_redirect_uri=...`;
   client also discards local tokens.

Token claims of interest: `iss` (`http://localhost:8083`), `sub` (user id), `aud`
(client id), `exp`, `iat`, `jti`, `scope`, and a custom **`roles`** array
(`["ADMIN"]` / `["ANALYST"]` / `["VIEWER"]`) added by an `OAuth2TokenCustomizer`.

---

## 5. React authentication flow (`procuremind-react`)

- **Client type:** public SPA client `procuremind-react`, **PKCE required**, no client
  secret, `client_authentication_methods = none`.
- **Library:** `react-oidc-context` + `oidc-client-ts` (adds two dependencies; no router is
  introduced — the redirect URI is the app root `http://localhost:5173/` and
  `onSigninCallback` strips the query string, so the existing tab-based `App.jsx` shell is
  preserved).
- **Config** (`src/auth/authConfig.js`): `authority = VITE_OIDC_AUTHORITY`
  (`http://localhost:8083`), `client_id = VITE_OIDC_CLIENT_ID` (`procuremind-react`),
  `redirect_uri = window.location.origin + '/'`, `scope = 'openid profile roles'`,
  `response_type = 'code'`, `automaticSilentRenew = true`, `userStore` =
  `WebStorageStateStore({ store: window.sessionStorage })`.
- **Gate:** `main.jsx` wraps the tree in `<AuthProvider>`; `App.jsx` uses `useAuth()` —
  when `!auth.isAuthenticated` it renders `<LoginScreen>` (a single "Sign in" button
  calling `auth.signinRedirect()`); otherwise the existing `Sidebar + Header + MainContent`
  shell renders unchanged.
- **Token attach:** `src/api/client.js` gains a module-level `getAccessToken` setter; every
  request (the `apiFetch` helper **and** the two raw `fetch` calls in `uploadContract` and
  `sendChatMessage`) adds `Authorization: Bearer <token>` when a token is present.
- **401 handling:** on `401`, call `auth.signinSilent()` once and retry; if that fails,
  `auth.signinRedirect()`.
- **Data loading:** `src/context/AppContext.jsx` runs `loadData()` only once the user is
  authenticated (guarded by auth state), instead of unconditionally on mount.
- **Logout:** `Header.jsx` shows the username (from `auth.user.profile`) and a logout
  button calling `auth.signoutRedirect()`.
- **Escape hatch:** `VITE_AUTH_REQUIRED` (default `true`); when `false` the gate is skipped
  and the app behaves as today (used only while the backend is still permissive).
- **Tokens are never written to `localStorage`.** `sessionStorage` + short access-token TTL
  + refresh rotation is the accepted trade-off for a project without a backend session
  cookie. (A future hardening — `HttpOnly` cookie via a BFF — is noted in §15, not in
  scope here.)

---

## 6. Streamlit authentication flow (`procuremind-ui`)

- **Client type:** confidential server-side client `procuremind-streamlit`,
  `client_secret_basic`, PKCE also enabled. Secret supplied via env
  `STREAMLIT_OIDC_CLIENT_SECRET`, never committed.
- **Library:** `Authlib` (added to `pyproject.toml` and the Dockerfile pip line). Streamlit
  ≥ 1.60 is present; native `st.login()` is available but is **not** used because it manages
  only a session cookie and does not expose the raw access token needed to call the
  gateway. A manual Authorization Code flow is used instead.
- **Module `procuremind-ui/auth.py`:**
  - `login_url()` builds the `/oauth2/authorize` URL against
    `STREAMLIT_OIDC_BROWSER_ISSUER` (`http://localhost:8083`), `redirect_uri`
    `http://localhost:8501/`, `scope=openid profile roles`, PKCE `code_challenge`.
  - `handle_callback()` reads `st.query_params` for `code` + `state`, exchanges the code at
    `STREAMLIT_OIDC_INTERNAL_ISSUER` (`http://auth-service:8083`) `/oauth2/token`, stores
    `access_token` / `refresh_token` / `id_token` / `expires_at` / `userinfo` in
    `st.session_state`, then clears the query params and `st.rerun()`.
  - `require_login()` — called at the top of `main()`: if no valid session token, render a
    "Sign in with ProcureMind ID" link to `login_url()` and `st.stop()`.
  - `get_access_token()` — returns the current token, transparently refreshing via
    `grant_type=refresh_token` when within 60 s of `expires_at`.
  - `logout()` — clears the auth keys from `st.session_state` and links to
    `/connect/logout`.
- **Request wiring in `main.py`:** an `auth_headers()` helper returns
  `{"Authorization": f"Bearer {get_access_token()}"}`; it is passed to `requests` in
  `safe_get_api`, `upload_contract_api`, and `send_chat_api`. On `401` the helper attempts
  one refresh, then forces re-login.
- **Sidebar:** show `userinfo["preferred_username"]` and a logout button.
- **Two issuer URLs** are required because the browser reaches the auth-service on
  `localhost:8083` while the Streamlit container reaches it on `auth-service:8083`
  (see §13 "Issuer URL strategy").
- **Escape hatch:** `AUTH_REQUIRED` (default `true`) to bypass the gate while the backend is
  permissive.

---

## 7. Gateway security architecture

- **Dependencies added** to `api-gateway/pom.xml`: `spring-boot-starter-oauth2-resource-server`
  (pulls `spring-security-oauth2-jose` + `spring-boot-starter-security`), and
  `spring-security-test` (test scope).
- **New package** `com.procuremind.api_gateway.security`:
  - `SecurityConfig` — a servlet `SecurityFilterChain`:
    - `csrf(csrf -> csrf.disable())`, `sessionManagement(STATELESS)`.
    - `cors(Customizer.withDefaults())` backed by a `CorsConfigurationSource` that mirrors
      today's `globalcors` values but replaces `allowed-headers: "*"` with an explicit list
      (`Authorization`, `Content-Type`, `Accept`, `X-Requested-With`) because `"*"` +
      `allow-credentials: true` is invalid.
    - `oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(rolesConverter)))`.
    - **Two conditional variants** via `@ConditionalOnProperty(name="app.security.enforce")`:
      - `false` (default): `authorizeHttpRequests(a -> a.anyRequest().permitAll())` — token
        still parsed if present.
      - `true`: the public list below is `permitAll`, `anyRequest().authenticated()`
        (role matchers added in Phase 8).
  - `JwtRolesConverter` — maps the `roles` claim (array) to
    `SimpleGrantedAuthority("ROLE_" + value)`.
- **Config** in `api-gateway/src/main/resources/application.yaml`:
  - `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: ${AUTH_JWK_SET_URI:http://localhost:8083/oauth2/jwks}`
  - a custom `JwtDecoder` bean adds `JwtValidators.createDefault()` +
    `JwtIssuerValidator(${AUTH_ISSUER_URI:http://localhost:8083})` (so the internal JWKS URL
    and the external issuer can differ — see §13).
  - `app.security.enforce: ${AUTH_ENABLED:false}`.
- **Public paths** (never require auth): `/`, `/api`, `/actuator/health`, `/actuator/info`,
  `/health/**`, and `OPTIONS /**`. (Auth-service endpoints are reached by the browser
  directly on `:8083`, not through the gateway, so no `/oauth2/**` passthrough route is
  required. Optional convenience routes are listed in Phase 2.)
- **Protected paths:** `/api/contracts/**`, `/api/analysis/**`.
- **No `X-User-*` header is added or trusted.** The gateway only validates and forwards.
- **The security filter must not read the request body** — the 70 MB multipart upload must
  continue to stream to `contract-service`. Resource-server JWT validation is header-only,
  so this is satisfied by construction; no custom body-reading filter is introduced.

---

## 8. contract-service security architecture

- **Dependencies added** to `contract-service/pom.xml`:
  `spring-boot-starter-oauth2-resource-server`, `spring-security-test` (test scope).
- **New package** `com.procuremind.contract_service.security`:
  - `SecurityConfig` (Spring Security 7 / Boot 4 syntax) — `SecurityFilterChain` with
    `csrf.disable()`, `sessionManagement STATELESS`,
    `oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(rolesConverter)))`,
    and the same `app.security.enforce` conditional (permitAll vs rules).
  - `JwtRolesConverter` (same mapping as the gateway).
- **Config** in `contract-service/src/main/resources/application.yaml`:
  `jwk-set-uri: ${AUTH_JWK_SET_URI:http://auth-service:8083/oauth2/jwks}`, custom
  `JwtDecoder` with issuer validator `${AUTH_ISSUER_URI:http://localhost:8083}`,
  `app.security.enforce: ${AUTH_ENABLED:false}`.
- **Public when enforced:** `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`,
  `/swagger-ui/**` (dev). Everything under `/api/contracts/**` requires authentication.
- **Method security (Phase 8 only):** `@EnableMethodSecurity` +
  `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")` on `ContractController.uploadContract`.
  **No controller method body is changed** — only an annotation is added.
- **Not affected:** `ContractService`, `StorageService`, `ContractRepository`,
  `ContractEventProducer`, `ContractEventListener`, `MinioConfig`, entities, DTOs.

---

## 9. ai-service security architecture

- **Dependencies added** to `ai-service/pom.xml`:
  `spring-boot-starter-oauth2-resource-server`, `spring-security-test` (test scope).
  (Spring Boot **3.5.14** resolves the Spring Security 6 line — the same JWT validates.)
- **New package** `com.procuremind.ai_service.security`:
  - `SecurityConfig` (Spring Security 6 syntax) — `SecurityFilterChain` with
    `csrf.disable()`, `sessionManagement STATELESS`,
    `authorizeHttpRequests(...)`, `oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(rolesConverter)))`,
    same `app.security.enforce` conditional.
  - `JwtRolesConverter` (same mapping).
- **Config** in `ai-service/src/main/resources/application.yaml`:
  `jwk-set-uri: ${AUTH_JWK_SET_URI:http://auth-service:8083/oauth2/jwks}`, issuer validator
  `${AUTH_ISSUER_URI:http://localhost:8083}`, `app.security.enforce: ${AUTH_ENABLED:false}`.
- **Public when enforced:** `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`,
  `/swagger-ui/**` (dev). Everything under `/api/analysis/**` (including `POST /chat`)
  requires authentication.
- **Method security (Phase 8 only):** `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")` on
  `ChatController.chat`. GET analysis endpoints require any authenticated role.
- **Kafka listeners are explicitly out of scope.** `ContractEventListener` runs on the
  consumer thread with no HTTP request and no `SecurityContext`; Spring Security filter
  chains do not apply. The parse → index → analyze pipeline is **not modified**. No service
  token is introduced because there are no outbound authenticated HTTP calls from the
  listeners today.
- **Not affected:** `PdfParsingService`, `IndexingService`, `AnalysisService`,
  `AnalysisQueryService`, `RetrievalService`, `ConversationService`, all agents and tools,
  entities, repositories, DTOs, prompts.

---

## 10. Auth-service responsibilities

**In scope:**

- Run Spring Authorization Server with a **static issuer** and full OIDC support
  (`.oidc(withDefaults())`): `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`,
  `/oauth2/revoke`, `/oauth2/introspect`, `/.well-known/openid-configuration`,
  `/userinfo` + `/connect/userinfo`, `/connect/logout`.
- Serve a **hosted login page** (`/login`) via Spring Security form login, backed by a JPA
  `UserDetailsService` over the `users` / `roles` tables with `BCryptPasswordEncoder`.
- Persist OAuth2 state in PostgreSQL via `JdbcRegisteredClientRepository`,
  `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService` (survives
  restart; no in-memory-only clients or authorizations).
- Add the **`roles`** claim to access tokens via `OAuth2TokenCustomizer<JwtEncodingContext>`.
- Use a **persistent RSA signing key** loaded from a mounted PKCS12 keystore (not the
  default ephemeral key), exposed through a `JWKSource<SecurityContext>` with a stable
  `kid`.
- Own **all identity Flyway migrations** in database `procuremind_auth`.
- Seed the three roles and, on first boot, a bootstrap **admin** user from environment
  variables (`AUTH_ADMIN_USERNAME` / `AUTH_ADMIN_PASSWORD`), plus register the two OAuth2
  clients from environment (`STREAMLIT_OIDC_CLIENT_SECRET`), via an idempotent
  `ApplicationRunner`.
- Optionally expose a minimal **ADMIN-only user administration API** (`/api/users` CRUD +
  role assignment) for creating `ANALYST` / `VIEWER` accounts. Marked optional; the
  bootstrap admin + seeded clients are sufficient for the cutover.

**Explicitly NOT responsibilities:** routing/proxying business traffic, reading or writing
`procuremind_db`, calling `contract-service` or `ai-service`, holding any contract/risk
data, participating in Kafka.

---

## 11. Database / schema design

- **New database `procuremind_auth`** in the existing `postgres` container. Rationale:
  `auth-service` owns 100 % of it with zero Flyway interaction with `procuremind_db`; the
  pre-existing "one migration owns every table" situation in `ai-service` is left untouched
  (not in scope to fix).
- **Provisioning:** an init script `scripts/init-auth-db.sql`
  (`CREATE DATABASE procuremind_auth;`) mounted into
  `/docker-entrypoint-initdb.d/` on the `postgres` service (runs only on a fresh volume).
  For an existing volume: documented one-off
  `docker compose exec postgres createdb -U user procuremind_auth`.
- **Flyway migrations** (owned by `auth-service`, `auth-service/src/main/resources/db/migration/`):

| Migration | Contents |
|---|---|
| `V1__identity_schema.sql` | `users(id uuid pk default gen_random_uuid(), username varchar(100) unique not null, password_hash varchar(100) not null, email varchar(255), enabled boolean not null default true, created_at timestamptz not null default now(), updated_at timestamptz)`; `roles(id smallint pk, name varchar(30) unique not null)`; `user_roles(user_id uuid references users(id) on delete cascade, role_id smallint references roles(id), primary key (user_id, role_id))` |
| `V2__seed_roles.sql` | `insert into roles (id, name) values (1,'ADMIN'),(2,'ANALYST'),(3,'VIEWER') on conflict do nothing;` |
| `V3__oauth2_authorization_server_schema.sql` | The canonical Spring Authorization Server DDL, copied verbatim from the library resources: `oauth2-registered-client-schema.sql`, `oauth2-authorization-schema.sql`, `oauth2-authorization-consent-schema.sql` (tables `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`). |

- **Bootstrap data** (admin user, client registrations) is **not** in Flyway — it is
  applied by an idempotent `ApplicationRunner` so BCrypt hashes / client secrets come from
  environment variables and are never committed.
- PostgreSQL 15 provides `gen_random_uuid()` in core; no extension required.

ER outline:

```
users ─┬─< user_roles >─┬─ roles
       │                 (ADMIN / ANALYST / VIEWER)
       │
oauth2_registered_client   (procuremind-react, procuremind-streamlit)
oauth2_authorization       (issued tokens / codes, rotated)
oauth2_authorization_consent
```

---

## 12. Users and roles

Three roles, seeded in `V2`:

| Role | Intended user | Capabilities |
|---|---|---|
| `VIEWER` | Read-only stakeholder | All `GET /api/analysis/**` and `GET /api/contracts/**` |
| `ANALYST` | Procurement / legal analyst | Everything `VIEWER` can do **plus** `POST /api/contracts/upload` and `POST /api/analysis/chat` |
| `ADMIN` | Platform administrator | Everything `ANALYST` can do **plus** user administration (`/api/users/**` on auth-service) and full actuator detail |

Endpoint → required authority (enforced at the gateway in Phase 8, reinforced by method
security in the services):

| Method + path | VIEWER | ANALYST | ADMIN |
|---|---|---|---|
| `GET /api/contracts`, `GET /api/contracts/{id}`, `GET /api/contracts/{id}/status` | ✅ | ✅ | ✅ |
| `GET /api/analysis/**` (all read endpoints) | ✅ | ✅ | ✅ |
| `POST /api/contracts/upload` | ❌ | ✅ | ✅ |
| `POST /api/analysis/chat` | ❌ | ✅ | ✅ |
| `POST/PUT/DELETE /api/users/**` (auth-service) | ❌ | ❌ | ✅ |
| `/actuator/**` detail | ❌ | ❌ | ✅ |

Password policy: BCrypt (strength 10–12). Bootstrap admin credentials come from
`AUTH_ADMIN_USERNAME` / `AUTH_ADMIN_PASSWORD`. Additional accounts are created by an admin
via the optional `/api/users` API or directly in the `users` table for the demo.

---

## 13. OAuth2 / OIDC configuration

### Registered clients

| Setting | `procuremind-react` | `procuremind-streamlit` |
|---|---|---|
| Client type | Public (SPA) | Confidential (server-side) |
| `client_authentication_methods` | `none` | `client_secret_basic` |
| Secret | — | `{bcrypt}` of `STREAMLIT_OIDC_CLIENT_SECRET` |
| `authorization_grant_types` | `authorization_code`, `refresh_token` | `authorization_code`, `refresh_token` |
| `redirect_uris` | `http://localhost:5173/` | `http://localhost:8501/` |
| `post_logout_redirect_uris` | `http://localhost:5173/` | `http://localhost:8501/` |
| `scopes` | `openid`, `profile`, `roles` | `openid`, `profile`, `roles` |
| `require_proof_key` (PKCE) | `true` | `true` |
| `require_authorization_consent` | `false` (first-party) | `false` (first-party) |
| Access-token TTL | 15 min | 15 min |
| Refresh-token TTL | 8 h | 8 h |
| Reuse refresh tokens | `false` (rotation + reuse detection) | `false` |
| Access-token format | Self-contained JWT (RS256) | Self-contained JWT (RS256) |
| ID-token signature | RS256 | RS256 |

### `roles` claim

`OAuth2TokenCustomizer<JwtEncodingContext>`: for `ACCESS_TOKEN` (and `ID_TOKEN`), read the
authenticated principal's authorities, strip the `ROLE_` prefix, and set claim
`roles = ["ADMIN" | "ANALYST" | "VIEWER", ...]`. Resource servers re-add the `ROLE_` prefix
in `JwtRolesConverter`.

### Issuer URL strategy (internal vs external)

The browser reaches `auth-service` at `http://localhost:8083`; other containers reach it at
`http://auth-service:8083`. To keep the `iss` claim consistent everywhere:

- `AuthorizationServerSettings.issuer` = **`http://localhost:8083`** (the browser-facing
  URL). All discovery-document endpoints therefore advertise `http://localhost:8083/...`.
- Resource servers (gateway, contract-service, ai-service) are configured with
  **`jwk-set-uri`** pointing at the **internal** URL (`http://auth-service:8083/oauth2/jwks`
  in compose; `http://localhost:8083/oauth2/jwks` for a service run on the host) and a
  **manual `JwtIssuerValidator("http://localhost:8083")`**. This avoids Spring's automatic
  discovery-document fetch (which would require the internal host to equal the issuer host).
- Streamlit uses two variables: `STREAMLIT_OIDC_BROWSER_ISSUER=http://localhost:8083` for
  the authorize redirect, and `STREAMLIT_OIDC_INTERNAL_ISSUER=http://auth-service:8083` for
  server-side token / refresh / userinfo calls.
- React (runs in the browser) uses `VITE_OIDC_AUTHORITY=http://localhost:8083` only.

This is the standard "front-channel vs back-channel URL" pattern; no `/etc/hosts` edit is
required.

---

## 14. Token and session strategy

- **Access token:** JWT, RS256, 15 min TTL, `roles` claim, `aud` = client id. Validated by
  every resource server against cached JWKS with default validators + issuer validator +
  optional audience validator. Clock skew tolerance 60 s.
- **Refresh token:** opaque, 8 h TTL, **rotated on every use with reuse detection**
  (Spring Authorization Server built-in). Stored server-side in `oauth2_authorization`.
- **ID token:** used only for identity display and RP-initiated logout (`id_token_hint`).
- **Resource servers are stateless** (`SessionCreationPolicy.STATELESS`, no
  `JSESSIONID`, CSRF disabled).
- **auth-service** keeps a normal servlet session for the login page only (standard for the
  authorization-code front channel).
- **React storage:** `User` object (incl. access + refresh token) in `sessionStorage` via
  `oidc-client-ts`; access token also held in memory for request signing. Cleared on tab
  close and on logout. Accepted trade-off; mitigations = short access TTL + refresh
  rotation.
- **Streamlit storage:** tokens in `st.session_state` (server-side, per session). Refreshed
  transparently within 60 s of expiry.
- **Revocation:** `/oauth2/revoke`; logout also deletes the local token copies.

---

## 15. Security considerations

- **Independent validation at every tier** removes the confused-deputy risk: because
  `contract-service` and `ai-service` validate the JWT themselves, their published ports
  (`8081`, `8082`) are no longer a privilege-escalation path (no header forgery is
  possible). **Optional** defense-in-depth in Phase 8: bind those host ports to
  `127.0.0.1` in `docker-compose.yaml`. Marked optional because correctness no longer
  depends on it.
- **No `X-User-*` trust.** Identity is only ever derived from a validated JWT.
- **CORS:** `allowed-headers: "*"` with `allow-credentials: true` is invalid and is
  replaced by an explicit header allow-list at the gateway; `auth-service` adds a narrow
  CORS config for `/oauth2/token`, `/oauth2/revoke`, `/userinfo`, `/.well-known/**`,
  `/oauth2/jwks` limited to the React origin (`http://localhost:5173`).
- **Signing key persistence:** a mounted PKCS12 keystore, not the default ephemeral RSA key
  (which would invalidate all tokens on every restart). Key rotation is a documented manual
  procedure (add a second key to the `JWKSource`, flip the active `kid`).
- **Actuator:** `/actuator/health` stays public; Phase 8 sets
  `management.endpoint.health.show-details: when-authorized` and restricts `/actuator/**`
  detail to `ADMIN` on all three services.
- **Transport:** all traffic is plain HTTP in local/dev. Production is assumed to terminate
  TLS at an upstream load balancer / ingress; enabling HTTPS is **out of scope** for this
  plan.
- **Explicitly out of scope** (pre-existing, not required for auth): prompt-injection
  hardening of the LLM agents, upload content-type/AV validation, secrets manager for
  infra credentials, HTTPS, the `ai-service` Flyway schema-ownership refactor, the Spring
  Boot version alignment.

---

## 16. Service-to-service security

- **Today there are no synchronous service-to-service HTTP calls.** All cross-service
  communication is Kafka events (`contract.uploaded` / `contract.indexed` /
  `contract.analyzed`), which carry no security context and are unaffected.
- Kafka consumers therefore run unauthenticated by design; no filter chain applies to them.
- **If** a future feature introduces a synchronous internal call, the pattern is: a
  dedicated confidential client in `auth-service` using the **`client_credentials`** grant,
  with the caller attaching its own bearer token and the callee validating it as any other
  JWT (scope-based rule). **No such client and no such code is created by this plan.**

---

## 17. Docker / network changes

All additive. Applied in Phase 2 (wiring) and Phase 7 (enforcement flag).

- **New service `auth-service`** in `docker-compose.yaml`: `build` from
  `auth-service/Dockerfile` with context `.`; `ports: ["8083:8083"]`; env for datasource
  (`jdbc:postgresql://postgres:5432/procuremind_auth`, `user` / `password`), `AUTH_ISSUER_URI`,
  keystore location/passwords, `AUTH_ADMIN_USERNAME` / `AUTH_ADMIN_PASSWORD`,
  `REACT_OIDC_REDIRECT_URI`, `STREAMLIT_OIDC_REDIRECT_URI`, `STREAMLIT_OIDC_CLIENT_SECRET`;
  `depends_on: postgres (service_healthy)`.
- **`postgres` service:** add a bind mount `./scripts/init-auth-db.sql` →
  `/docker-entrypoint-initdb.d/10-init-auth-db.sql`. (No change to existing volume,
  credentials, or the `procuremind_db` database.)
- **`api-gateway` service:** add env `AUTH_JWK_SET_URI`, `AUTH_ISSUER_URI`, `AUTH_ENABLED`
  (default `"false"`); add `auth-service` to `depends_on`.
- **`contract-service` / `ai-service` services:** add env `AUTH_JWK_SET_URI`,
  `AUTH_ISSUER_URI`, `AUTH_ENABLED` (default `"false"`).
- **`procuremind-ui` service:** add env `STREAMLIT_OIDC_BROWSER_ISSUER`,
  `STREAMLIT_OIDC_INTERNAL_ISSUER`, `STREAMLIT_OIDC_CLIENT_ID`,
  `STREAMLIT_OIDC_CLIENT_SECRET`, `STREAMLIT_OIDC_REDIRECT_URI`, `AUTH_REQUIRED`.
- **No changes** to the Kafka, Kafka-UI, or MinIO services, to the compose network model
  (default bridge), or to `procuremind-react` (still not containerized).
- Phase 8 (optional): change `contract-service` / `ai-service` port mappings to
  `127.0.0.1:8081:8081` / `127.0.0.1:8082:8082`.

---

## 18. Configuration / environment variables

| Variable | Consumed by | Purpose | Default |
|---|---|---|---|
| `AUTH_ISSUER_URI` | auth-service, gateway, contract-service, ai-service | Static token issuer / `iss` validation | `http://localhost:8083` |
| `AUTH_JWK_SET_URI` | gateway, contract-service, ai-service | Internal JWKS endpoint for validation | `http://auth-service:8083/oauth2/jwks` (compose) / `http://localhost:8083/oauth2/jwks` (host) |
| `AUTH_ENABLED` | gateway, contract-service, ai-service (`app.security.enforce`) | Master enforcement switch | `false` |
| `AUTH_JWK_KEYSTORE_PATH` | auth-service | PKCS12 keystore with the RSA signing key | `/run/secrets/auth-jwk.p12` (mounted) |
| `AUTH_JWK_KEYSTORE_PASSWORD` | auth-service | Keystore password | — (required) |
| `AUTH_JWK_KEY_ALIAS` | auth-service | Key alias | `auth-signing` |
| `AUTH_JWK_KEY_PASSWORD` | auth-service | Key password | — (required) |
| `AUTH_ADMIN_USERNAME` / `AUTH_ADMIN_PASSWORD` | auth-service | Bootstrap admin, seeded on first run | — (required) |
| `AUTH_DB_URL` / `AUTH_DB_USERNAME` / `AUTH_DB_PASSWORD` | auth-service | `procuremind_auth` datasource | `jdbc:postgresql://localhost:5433/procuremind_auth`, `user`, `password` |
| `REACT_OIDC_REDIRECT_URI` | auth-service (client seed) | React redirect URI | `http://localhost:5173/` |
| `STREAMLIT_OIDC_CLIENT_SECRET` | auth-service (client seed), Streamlit | Confidential client secret | — (required) |
| `STREAMLIT_OIDC_CLIENT_ID` | Streamlit | Client id | `procuremind-streamlit` |
| `STREAMLIT_OIDC_BROWSER_ISSUER` | Streamlit | Front-channel authorize URL base | `http://localhost:8083` |
| `STREAMLIT_OIDC_INTERNAL_ISSUER` | Streamlit | Back-channel token/userinfo URL base | `http://auth-service:8083` |
| `STREAMLIT_OIDC_REDIRECT_URI` | auth-service (client seed), Streamlit | Streamlit redirect URI | `http://localhost:8501/` |
| `AUTH_REQUIRED` | Streamlit (`main.py`) | UI-side login gate toggle | `true` |
| `VITE_OIDC_AUTHORITY` | React build | Issuer / authority | `http://localhost:8083` |
| `VITE_OIDC_CLIENT_ID` | React build | Client id | `procuremind-react` |
| `VITE_OIDC_SCOPE` | React build | Requested scopes | `openid profile roles` |
| `VITE_AUTH_REQUIRED` | React (`App.jsx`) | UI-side login gate toggle | `true` |

Additions go to `.env.example` (backend + shared) and `procuremind-react/.env.example`.
Real values go in the git-ignored `.env` files. No secret is committed.

---

## 19. Exact implementation phases

Each phase is independently shippable, leaves the app runnable, and is reversible.
`AUTH_ENABLED` / `*_AUTH_REQUIRED` stay `false`/off until stated otherwise.
**Existing unauthenticated behaviour is preserved through Phase 6.**

---

### Phase 0 — Decisions & non-code groundwork

- **Goal:** lock every decision needed for implementation so later phases are mechanical.
- **Files affected:** none (this document only).
- **What changes:** confirm — `auth-service` on Spring Boot 4.0.7 + Spring Authorization
  Server 2.x; port `8083`; database `procuremind_auth`; static issuer
  `http://localhost:8083` with internal JWKS URL; client ids `procuremind-react` (public,
  PKCE) and `procuremind-streamlit` (confidential); roles `ADMIN` / `ANALYST` / `VIEWER`;
  `roles` custom claim; enforcement flag `AUTH_ENABLED`; PKCS12 keystore for signing.
- **Why:** prevents rework; every subsequent phase references these values.
- **Dependencies:** none.
- **Tests:** none.
- **Verification:** this section reviewed and approved; `docker compose up` on the current
  repo still succeeds (baseline snapshot).
- **Rollback:** n/a.

---

### Phase 1 — Stand up `auth-service` in isolation

- **Goal:** a runnable OIDC provider that nothing else depends on yet.
- **Files affected (create):**
  - `pom.xml` (root) — add `<module>auth-service</module>` (the **only** change to an
    existing file in this phase).
  - `auth-service/pom.xml`, `auth-service/Dockerfile`, `auth-service/.gitignore`,
    `auth-service/mvnw*` (optional).
  - `auth-service/src/main/java/com/procuremind/auth/AuthServiceApplication.java`
  - `.../auth/config/AuthorizationServerConfig.java` — AS `SecurityFilterChain`,
    `OAuth2TokenCustomizer` (roles claim), `JWKSource` from keystore,
    `AuthorizationServerSettings` (issuer), `JdbcRegisteredClientRepository`,
    `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService`.
  - `.../auth/config/DefaultSecurityConfig.java` — form-login chain, `PasswordEncoder`,
    narrow CORS for token/jwks/userinfo.
  - `.../auth/config/JwkKeyConfig.java` — load PKCS12 keystore → `RSAKey`.
  - `.../auth/user/{User.java,Role.java,UserRepository.java,RoleRepository.java,JpaUserDetailsService.java}`
  - `.../auth/bootstrap/DataSeeder.java` — idempotent `ApplicationRunner`: seed roles,
    bootstrap admin from env, register the two OAuth2 clients from env.
  - `.../auth/web/UserAdminController.java` — **optional** ADMIN-only `/api/users` CRUD.
  - `auth-service/src/main/resources/application.yaml`
  - `auth-service/src/main/resources/db/migration/V1__identity_schema.sql`,
    `V2__seed_roles.sql`, `V3__oauth2_authorization_server_schema.sql`
  - `scripts/init-auth-db.sql`
  - `scripts/gen-auth-keystore.sh` (or documented `keytool` command) to create the dev
    PKCS12 keystore; the generated `.p12` is git-ignored.
  - Test sources listed in §23.
- **What changes:** a new Maven module + a new standalone service. No existing service,
  frontend, gateway, or `docker-compose.yaml` is touched.
- **Why:** deliver and validate the identity provider before anything consumes it.
- **Dependencies:** local PostgreSQL with a `procuremind_auth` database; a dev keystore.
- **Tests:** `AuthServiceApplicationTests` (Testcontainers Postgres, context loads);
  `TokenClaimsCustomizerTest` (roles claim present, correct values);
  `RegisteredClientSeedTest` (both clients persisted with expected settings);
  `LoginPageTest` (`MockMvc`: form login success / bad credentials);
  `JwkSourceTest` (stable `kid`, RS256).
- **Verification (manual):**
  - `mvn -pl auth-service -am spring-boot:run` (with env set).
  - `curl http://localhost:8083/.well-known/openid-configuration` → issuer
    `http://localhost:8083`, all endpoints listed.
  - `curl http://localhost:8083/oauth2/jwks` → one RSA key with `kid`.
  - Browser: complete an Authorization Code + PKCE flow manually (or via a scratch client)
    → receive a JWT; decode at jwt.io → RS256, `iss`, `sub`, `roles`, `aud`, `exp`.
  - `POST /oauth2/token` `grant_type=refresh_token` → new pair; reusing the old refresh
    token → rejected (reuse detection).
- **Rollback:** delete the `auth-service/` directory and the one `<module>` line in the
  root `pom.xml`. Nothing else references it — zero impact.

---

### Phase 2 — Integrate `auth-service` into Compose (no enforcement)

- **Goal:** `auth-service` runs as part of `docker compose up`; still nothing enforces.
- **Files affected (modify):**
  - `docker-compose.yaml` — add the `auth-service` service; add the
    `scripts/init-auth-db.sql` mount to `postgres`; add `auth-service` to
    `api-gateway.depends_on`.
  - `.env.example` — add the auth/OIDC variables from §18 (placeholders only).
  - *(Optional convenience)* `api-gateway/src/main/resources/application.yaml` — add
    passthrough routes so `http://localhost:8080/oauth2/**`, `/.well-known/**`, `/login`,
    `/userinfo`, `/connect/**` proxy to `auth-service` for a single browser origin. Not
    required; the browser can use `:8083` directly.
- **What changes:** infra wiring only. No security dependency, no Java, no frontend change.
- **Why:** make the provider reproducible and reachable at known URLs.
- **Dependencies:** Phase 1.
- **Tests:** none new (compose smoke only).
- **Verification:**
  - `docker compose up --build -d` → all containers healthy including `auth-service`.
  - `curl http://localhost:8083/.well-known/openid-configuration` → 200.
  - Regression: anonymous `GET /api/contracts`, `GET /api/analysis/dashboard-metrics`,
    `POST /api/contracts/upload`, `POST /api/analysis/chat` via the gateway → still 200.
  - Both frontends run unchanged.
- **Rollback:** revert `docker-compose.yaml` and `.env.example`; `docker compose up`. The
  `auth-service` container simply stops being started.

---

### Phase 3 — API Gateway becomes a Resource Server (permissive)

- **Goal:** the gateway validates a token when one is present but enforces nothing.
- **Files affected:**
  - **Modify** `api-gateway/pom.xml` — add `spring-boot-starter-oauth2-resource-server`,
    `spring-security-test` (test).
  - **Modify** `api-gateway/src/main/resources/application.yaml` — add
    `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, `app.security.enforce:
    ${AUTH_ENABLED:false}`, and the explicit CORS `allowed-headers` list (replacing `"*"`).
  - **Create** `api-gateway/src/main/java/com/procuremind/api_gateway/security/SecurityConfig.java`,
    `.../security/JwtRolesConverter.java`, `.../security/JwtDecoderConfig.java`
    (issuer validator).
  - **Modify** `docker-compose.yaml` — add `AUTH_JWK_SET_URI`, `AUTH_ISSUER_URI`,
    `AUTH_ENABLED: "false"` to `api-gateway`.
- **What changes:** the gateway gains a stateless security filter chain. With
  `app.security.enforce=false` the chain is `permitAll`, but a presented bearer is
  validated and its authorities populated. CORS becomes spec-valid.
- **Why:** prove token validation and JWKS connectivity in production-like wiring without
  risking existing traffic.
- **Dependencies:** Phase 2.
- **Tests:** `GatewaySecurityConfigTest` (`spring-security-test`): with flag off →
  anonymous request permitted; with a valid mock JWT → authorities present; with flag on →
  401 for a protected path, 200 for each public path; CORS preflight from
  `http://localhost:5173` returns the explicit allow-list.
- **Verification:**
  - `docker compose up` with `AUTH_ENABLED=false`.
  - Anonymous regression matrix (contracts, analysis, **70 MB upload**, chat) → all 200.
  - Obtain a token from `auth-service`; call `GET /api/contracts` with
    `Authorization: Bearer` → 200; confirm no `X-User-*` header is added downstream
    (inspect `contract-service` request logs / gateway DEBUG).
  - Malformed bearer → still 200 (permissive) and a logged validation warning.
  - `/`, `/api`, `/actuator/health`, `/health/ai`, `/health/contract` → still public.
- **Rollback:** `AUTH_ENABLED` is already `false`; or revert the pom, the yaml, and delete
  the `security/` package.

---

### Phase 4 — contract-service & ai-service become Resource Servers (permissive)

- **Goal:** independent JWT validation in both business services, still enforcing nothing.
- **Files affected:**
  - **Modify** `contract-service/pom.xml` and `ai-service/pom.xml` — add
    `spring-boot-starter-oauth2-resource-server`, `spring-security-test` (test).
  - **Modify** `contract-service/src/main/resources/application.yaml` and
    `ai-service/src/main/resources/application.yaml` — add `jwk-set-uri`, issuer validator
    config, `app.security.enforce: ${AUTH_ENABLED:false}`.
  - **Create** `contract-service/src/main/java/com/procuremind/contract_service/security/{SecurityConfig.java,JwtRolesConverter.java,JwtDecoderConfig.java}`
    (Spring Security 7 syntax).
  - **Create** `ai-service/src/main/java/com/procuremind/ai_service/security/{SecurityConfig.java,JwtRolesConverter.java,JwtDecoderConfig.java}`
    (Spring Security 6 syntax).
  - **Modify** `docker-compose.yaml` — add `AUTH_JWK_SET_URI`, `AUTH_ISSUER_URI`,
    `AUTH_ENABLED: "false"` to both services.
- **What changes:** both services gain a stateless permissive security chain that validates
  a presented token. No controller, service, entity, repository, Kafka, or pipeline code is
  touched.
- **Why:** establish defense-in-depth validation before the cutover so Phase 7 is a pure
  flag flip.
- **Dependencies:** Phase 3 (shared decisions) — technically independent of the gateway
  changes.
- **Tests:** `ContractServiceSecurityConfigTest`, `AiServiceSecurityConfigTest` (mock JWT:
  flag off → permit; flag on → 401 without token, 200 with; public paths list). A slice
  test asserting the Kafka listener path needs no security context (context loads with the
  chain present).
- **Verification:**
  - `docker compose up` with `AUTH_ENABLED=false`.
  - Direct calls (bypassing the gateway) to `http://localhost:8081/api/contracts` and
    `http://localhost:8082/api/analysis/dashboard-metrics` → 200 (permissive), and with a
    valid token → 200.
  - Upload a contract end to end → **Kafka pipeline still completes** (status
    `UPLOADED → INDEXED → ANALYZED`), confirming listeners are unaffected.
- **Rollback:** flag already `false`; or revert the two poms, two yamls, and delete both
  `security/` packages.

---

### Phase 5 — React SPA login (Authorization Code + PKCE)

- **Goal:** React performs a real OIDC login and sends bearer tokens; backend still
  permissive so anonymous use remains possible.
- **Files affected:**
  - **Modify** `procuremind-react/package.json` — add `react-oidc-context`, `oidc-client-ts`.
  - **Create** `procuremind-react/src/auth/authConfig.js`, `src/auth/LoginScreen.jsx`.
  - **Modify** `procuremind-react/src/main.jsx` — wrap the app in `<AuthProvider>` with
    `authConfig` + `onSigninCallback` (strip query string, no router needed).
  - **Modify** `procuremind-react/src/App.jsx` — `useAuth()`; render `<LoginScreen>` when
    unauthenticated (unless `VITE_AUTH_REQUIRED === 'false'`), else the existing shell.
  - **Modify** `procuremind-react/src/api/client.js` — module-level token setter; attach
    `Authorization: Bearer` in `apiFetch` and in the raw `fetch` calls of `uploadContract`
    and `sendChatMessage`; on 401 → `signinSilent` then retry, else `signinRedirect`.
  - **Modify** `procuremind-react/src/context/AppContext.jsx` — call `loadData()` only when
    authenticated; pass the current token to `client.js`.
  - **Modify** `procuremind-react/src/components/layout/Header.jsx` — username + logout
    button (`signoutRedirect`).
  - **Modify** `procuremind-react/.env.example` — add `VITE_OIDC_*`, `VITE_AUTH_REQUIRED`.
- **What changes:** frontend only. No backend, gateway, or compose change.
- **Why:** get the SPA issuing standards-compliant logins before enforcement, so the
  cutover does not break it.
- **Dependencies:** Phases 1–2 (provider reachable); the React client registration from
  Phase 1.
- **Tests:** Vitest unit test for `client.js` (adds header when token set; single
  refresh-and-retry on 401). Manual E2E dominates (no prior FE test infra).
- **Verification:**
  - `npm install && npm run dev`; open `http://localhost:5173` → redirected to the
    `auth-service` login page; log in → back to the dashboard.
  - Network tab: every `/api/*` call (including upload and chat) carries
    `Authorization: Bearer`.
  - Let the access token expire → silent renew → calls keep working.
  - Logout → returns to login screen; subsequent calls carry no token.
  - Set `VITE_AUTH_REQUIRED=false` → app loads anonymously as today.
- **Rollback:** revert the listed files; `npm install`. Backend unaffected.

---

### Phase 6 — Streamlit login (Authorization Code, confidential client)

- **Goal:** Streamlit performs a server-side OIDC login and forwards bearer tokens; backend
  still permissive.
- **Files affected:**
  - **Modify** `procuremind-ui/pyproject.toml` — add `Authlib`.
  - **Modify** `procuremind-ui/Dockerfile` — add `Authlib` to the pip install line.
  - **Create** `procuremind-ui/auth.py` — `login_url()`, `handle_callback()`,
    `require_login()`, `get_access_token()` (with refresh), `logout()`, `auth_headers()`.
  - **Modify** `procuremind-ui/main.py` — call `handle_callback()` + `require_login()` at
    the top of `main()` (respecting `AUTH_REQUIRED`); pass `headers=auth_headers()` in
    `safe_get_api`, `upload_contract_api`, `send_chat_api`; add user + logout to the
    sidebar.
  - **Modify** `docker-compose.yaml` — add `STREAMLIT_OIDC_*` and `AUTH_REQUIRED` env to
    the `procuremind-ui` service.
  - **Modify** `.env.example` — Streamlit OIDC placeholders.
- **What changes:** Streamlit app only, plus its compose env. No backend change.
- **Why:** both frontends must authenticate before enforcement.
- **Dependencies:** Phases 1–2; the Streamlit client registration + secret from Phase 1.
- **Tests:** a small `pytest` for `auth.py` token-refresh logic (optional — no prior
  Python test infra). Manual E2E is primary.
- **Verification:**
  - `docker compose up`; open `http://localhost:8501` → "Sign in" link → `auth-service`
    login → redirected back, session established.
  - Dashboard, Document Intelligence, upload, and chat all work; captured requests to the
    gateway carry `Authorization: Bearer`.
  - Force token expiry → transparent refresh.
  - Logout clears the session; `AUTH_REQUIRED=false` restores anonymous behaviour.
- **Rollback:** revert the listed files and the compose env; rebuild the `procuremind-ui`
  image.

---

### Phase 7 — Enforcement cutover

- **Goal:** require a valid token at every HTTP tier. **This is the breaking change**, done
  as a single reversible flag flip.
- **Files affected (modify):**
  - `docker-compose.yaml` — set `AUTH_ENABLED: "true"` on `api-gateway`,
    `contract-service`, and `ai-service`.
  - `.env.example` / deployment notes — document that `AUTH_ENABLED=true` is the new
    default for real environments.
  - Frontend flags: set `VITE_AUTH_REQUIRED` / `AUTH_REQUIRED` to `true` (their default) —
    typically no file edit, just stop overriding them.
- **What changes:** the conditional security chains switch from `permitAll` to
  `authenticated()` (coarse; role rules come in Phase 8). No new code.
- **Why:** activate protection now that the provider and both clients are proven.
- **Dependencies:** Phases 3–6 all shipped and verified.
- **Tests:** re-run `Gateway/Contract/AiServiceSecurityConfigTest` with the enforce profile
  → 401 without token, 200 with a valid token, public paths still open.
- **Verification (full matrix):**
  - `curl http://localhost:8080/api/contracts` (no token) → **401**; with a valid token →
    **200**.
  - Direct `curl http://localhost:8081/api/contracts` and
    `http://localhost:8082/api/analysis/dashboard-metrics` (no token) → **401**.
  - **70 MB** multipart upload with token → 200 and the body still streams through the
    gateway.
  - `POST /api/analysis/chat` with token → 200; without → 401.
  - `/`, `/api`, `/actuator/health`, `/health/ai`, `/health/contract` → still public.
  - Full upload → **Kafka pipeline reaches `ANALYZED`** (listeners unaffected).
  - Both frontends: log in → all screens work; log out → API calls return 401 and the UI
    returns to login.
  - Stop `auth-service` after the resource servers have cached the JWKS → existing tokens
    keep validating; new logins fail (expected).
- **Rollback:** set `AUTH_ENABLED=false` (one line per service) and `docker compose up` →
  instantly back to anonymous; frontends keep working (they just send unused tokens). No
  data or schema change to undo.

---

### Phase 8 — Role-based authorization & hardening

- **Goal:** apply `ADMIN` / `ANALYST` / `VIEWER` rules; remove the anonymous fallback;
  tighten actuator; optional network hardening.
- **Files affected (modify):**
  - `api-gateway/.../security/SecurityConfig.java` — replace
    `anyRequest().authenticated()` with the §12 matcher rules
    (`POST /api/contracts/upload` and `POST /api/analysis/chat` →
    `hasAnyRole('ANALYST','ADMIN')`; GETs → any authenticated role; `/actuator/**` detail →
    `hasRole('ADMIN')`).
  - `contract-service/.../security/SecurityConfig.java` + `@EnableMethodSecurity`; add
    `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")` to
    `ContractController.uploadContract` (**annotation only, no body change**).
  - `ai-service/.../security/SecurityConfig.java` + `@EnableMethodSecurity`; add
    `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")` to `ChatController.chat`.
  - The three `application.yaml` files — `management.endpoint.health.show-details:
    when-authorized`, keep only `health` unauthenticated.
  - Remove the permissive branch / `app.security.enforce=false` code path and the
    `*_AUTH_REQUIRED` UI escape hatches (or keep `AUTH_ENABLED` as a documented kill
    switch, default `true`).
  - *(Optional)* `docker-compose.yaml` — bind `contract-service` / `ai-service` host ports
    to `127.0.0.1`.
  - `auth-service` — enable the `/api/users` admin API if not already; seed real per-role
    users; document key rotation and moving `AUTH_JWK_KEYSTORE_PASSWORD` to a real secret
    store.
  - Frontends — hide/disable the upload and chat controls when the user's `roles` claim
    lacks `ANALYST`/`ADMIN`, to match backend `403`s (`Header.jsx` / `Sidebar.jsx` /
    `UploadModal.jsx` visibility guards in React; sidebar/section guards in `main.py`).
- **What changes:** authorization rules + hardening. Still no business-logic change.
- **Why:** least-privilege access; close the permissive path.
- **Dependencies:** Phase 7.
- **Tests:** parametrised `(role × endpoint) → status` tests at the gateway
  (`spring-security-test` mock JWTs with each `roles` value); method-security tests for the
  two annotated controllers; an `auth-service` test that `/api/users` requires `ADMIN`.
- **Verification:**
  - VIEWER token: all GETs 200; `POST /upload` and `/chat` → **403**.
  - ANALYST token: upload + chat 200; `/api/users` → **403**; `/actuator/**` detail 403.
  - ADMIN token: `/api/users` 200; actuator detail 200.
  - Anonymous: every protected route 401; only the public list open.
  - Both UIs reflect the role (controls hidden for VIEWER).
- **Rollback:** relax the matcher rules back to `anyRequest().authenticated()` and remove
  the `@PreAuthorize` annotations; revert the actuator `show-details` change. The role
  model and users remain.

---

## 20. Exact files to create

**auth-service (Phase 1):**

```
auth-service/pom.xml
auth-service/Dockerfile
auth-service/.gitignore
auth-service/src/main/java/com/procuremind/auth/AuthServiceApplication.java
auth-service/src/main/java/com/procuremind/auth/config/AuthorizationServerConfig.java
auth-service/src/main/java/com/procuremind/auth/config/DefaultSecurityConfig.java
auth-service/src/main/java/com/procuremind/auth/config/JwkKeyConfig.java
auth-service/src/main/java/com/procuremind/auth/user/User.java
auth-service/src/main/java/com/procuremind/auth/user/Role.java
auth-service/src/main/java/com/procuremind/auth/user/UserRepository.java
auth-service/src/main/java/com/procuremind/auth/user/RoleRepository.java
auth-service/src/main/java/com/procuremind/auth/user/JpaUserDetailsService.java
auth-service/src/main/java/com/procuremind/auth/bootstrap/DataSeeder.java
auth-service/src/main/java/com/procuremind/auth/web/UserAdminController.java        (optional)
auth-service/src/main/resources/application.yaml
auth-service/src/main/resources/db/migration/V1__identity_schema.sql
auth-service/src/main/resources/db/migration/V2__seed_roles.sql
auth-service/src/main/resources/db/migration/V3__oauth2_authorization_server_schema.sql
auth-service/src/test/java/com/procuremind/auth/AuthServiceApplicationTests.java
auth-service/src/test/java/com/procuremind/auth/TokenClaimsCustomizerTest.java
auth-service/src/test/java/com/procuremind/auth/RegisteredClientSeedTest.java
auth-service/src/test/java/com/procuremind/auth/LoginPageTest.java
auth-service/src/test/java/com/procuremind/auth/JwkSourceTest.java
scripts/init-auth-db.sql
scripts/gen-auth-keystore.sh
```

**Gateway (Phase 3):**

```
api-gateway/src/main/java/com/procuremind/api_gateway/security/SecurityConfig.java
api-gateway/src/main/java/com/procuremind/api_gateway/security/JwtRolesConverter.java
api-gateway/src/main/java/com/procuremind/api_gateway/security/JwtDecoderConfig.java
api-gateway/src/test/java/com/procuremind/api_gateway/GatewaySecurityConfigTest.java
```

**contract-service (Phase 4):**

```
contract-service/src/main/java/com/procuremind/contract_service/security/SecurityConfig.java
contract-service/src/main/java/com/procuremind/contract_service/security/JwtRolesConverter.java
contract-service/src/main/java/com/procuremind/contract_service/security/JwtDecoderConfig.java
contract-service/src/test/java/com/procuremind/contract_service/ContractServiceSecurityConfigTest.java
```

**ai-service (Phase 4):**

```
ai-service/src/main/java/com/procuremind/ai_service/security/SecurityConfig.java
ai-service/src/main/java/com/procuremind/ai_service/security/JwtRolesConverter.java
ai-service/src/main/java/com/procuremind/ai_service/security/JwtDecoderConfig.java
ai-service/src/test/java/com/procuremind/ai_service/AiServiceSecurityConfigTest.java
```

**React (Phase 5):**

```
procuremind-react/src/auth/authConfig.js
procuremind-react/src/auth/LoginScreen.jsx
procuremind-react/src/api/client.test.js            (Vitest; test tooling added with it)
```

**Streamlit (Phase 6):**

```
procuremind-ui/auth.py
```

---

## 21. Exact existing files to modify

| File | Phase | Nature of change |
|---|---|---|
| `pom.xml` (root) | 1 | Add `<module>auth-service</module>` (only line changed). |
| `auth-service/*` | — | (new module — see §20) |
| `docker-compose.yaml` | 2, 3, 4, 7, 8 | Add `auth-service` service; mount `scripts/init-auth-db.sql` on `postgres`; add `AUTH_*` env to `api-gateway`, `contract-service`, `ai-service`; add `STREAMLIT_OIDC_*` env to `procuremind-ui`; flip `AUTH_ENABLED` to `true` (Phase 7); optional loopback port binds (Phase 8). |
| `.env.example` | 2, 3, 5, 6 | Add auth / OIDC / keystore / admin placeholders. |
| `api-gateway/pom.xml` | 3 | Add `spring-boot-starter-oauth2-resource-server`, `spring-security-test`. |
| `api-gateway/src/main/resources/application.yaml` | 3, 8 | Add resource-server `jwk-set-uri`, `app.security.enforce`, explicit CORS `allowed-headers`; role matchers (Phase 8); actuator `show-details: when-authorized` (Phase 8). |
| `contract-service/pom.xml` | 4 | Add `spring-boot-starter-oauth2-resource-server`, `spring-security-test`. |
| `contract-service/src/main/resources/application.yaml` | 4, 8 | Add `jwk-set-uri`, issuer validator, `app.security.enforce`; actuator tightening (Phase 8). |
| `contract-service/.../controller/ContractController.java` | 8 | Add `@PreAuthorize` to `uploadContract` — **annotation only, no body change**. |
| `ai-service/pom.xml` | 4 | Add `spring-boot-starter-oauth2-resource-server`, `spring-security-test`. |
| `ai-service/src/main/resources/application.yaml` | 4, 8 | Add `jwk-set-uri`, issuer validator, `app.security.enforce`; actuator tightening (Phase 8). |
| `ai-service/.../controller/ChatController.java` | 8 | Add `@PreAuthorize` to `chat` — **annotation only, no body change**. |
| `procuremind-react/package.json` | 5 | Add `react-oidc-context`, `oidc-client-ts` (+ Vitest devDeps in Phase 5 if tests added). |
| `procuremind-react/src/main.jsx` | 5 | Wrap the tree in `<AuthProvider>`. |
| `procuremind-react/src/App.jsx` | 5, 8 | Auth gate (login screen vs shell); role-based control visibility (Phase 8). |
| `procuremind-react/src/api/client.js` | 5 | Bearer attach in `apiFetch` + `uploadContract` + `sendChatMessage`; 401 refresh-and-retry. |
| `procuremind-react/src/context/AppContext.jsx` | 5 | Load data only when authenticated; provide token to `client.js`. |
| `procuremind-react/src/components/layout/Header.jsx` | 5, 8 | Username + logout; hide privileged actions for `VIEWER` (Phase 8). |
| `procuremind-react/src/components/upload/UploadModal.jsx` | 8 | Disable/hide when role lacks `ANALYST`/`ADMIN`. |
| `procuremind-react/src/components/layout/Sidebar.jsx` | 8 | Hide chat/upload entries for insufficient roles (visibility only). |
| `procuremind-react/.env.example` | 5 | Add `VITE_OIDC_*`, `VITE_AUTH_REQUIRED`. |
| `procuremind-ui/pyproject.toml` | 6 | Add `Authlib`. |
| `procuremind-ui/Dockerfile` | 6 | Add `Authlib` to the pip install line. |
| `procuremind-ui/main.py` | 6, 8 | Call `require_login()` / `handle_callback()`; `auth_headers()` on the three request helpers; sidebar user + logout; role-based section guards (Phase 8). |

No other existing file is modified.

---

## 22. Files that must NOT be modified

- `procuremind-common/**` — stays framework-free. **No shared security library, no shared
  JWT filter, no `X-User` DTO.** Adding Spring/Spring Security here would force one Spring
  version across the 3.5.14 / 4.0.7 split.
- `ai-service/src/main/resources/db/migration/V1__init_schema.sql` and any
  `procuremind_db` schema — untouched. Auth data lives only in `procuremind_auth`.
- All Kafka code and event contracts: `*/service/kafka/ContractEventListener.java`,
  `ContractEventProducer.java`, `procuremind-common` event records.
- The AI/processing pipeline: `ai-service` `PdfParsingService`, `IndexingService`,
  `AnalysisService`, `AnalysisQueryService`, `RetrievalService`, `ConversationService`,
  `agent/**`, `agent/tools/**`, `entity/**`, `Repository/**`, `dto/**`,
  `src/main/resources/prompts/**`.
- `contract-service` business code: `service/**`, `entity/**`, `repository/**`,
  `dto/**`, `config/MinioConfig.java`.
- **Controller method bodies** — only `@PreAuthorize` annotations are added in Phase 8; no
  logic, signatures, or return types change.
- `api-gateway` routing rules in `application.yaml` (route `id`/`uri`/`predicates`) and
  `GatewayWelcomeController` — unchanged (security config and CORS headers are additive).
- `procuremind-react/vite.config.js`, `tailwind.config.js`, `postcss.config.js`,
  `.oxlintrc.json` — unchanged.
- `docker-compose.yaml` services `kafka`, `kafka-ui`, `minio`, and the `postgres`
  credentials / `procuremind_db` database / `postgres_data` volume — unchanged (only an
  init-script mount is added).
- `docs/architecture.md`, root `README.md`, all module `README.md` files — no doc rewrite.
- `DummyContracts/**`.
- The three existing `*ApplicationTests.java` smoke tests — left in place; new tests are
  added alongside.

---

## 23. Testing strategy

Given the near-absent baseline (3 `contextLoads` tests, no CI, no frontend tests), the plan
adds a **minimum viable** automated set plus a manual checklist (§24).

**auth-service (Phase 1):**

- `AuthServiceApplicationTests` — context loads against **Testcontainers PostgreSQL**
  (`org.testcontainers:postgresql`, `junit-jupiter`), Flyway migrations apply.
- `TokenClaimsCustomizerTest` — the `OAuth2TokenCustomizer` puts the correct `roles` array
  on the access token for a given user.
- `RegisteredClientSeedTest` — after `DataSeeder`, both clients exist with expected grant
  types, redirect URIs, PKCE flag, token TTLs.
- `LoginPageTest` (`MockMvc`) — form login success and failure; protected AS endpoint
  redirects to `/login` when unauthenticated.
- `JwkSourceTest` — keystore loads, one RS256 key, stable `kid`.

**Resource servers (Phases 3–4, 8):**

- `GatewaySecurityConfigTest`, `ContractServiceSecurityConfigTest`,
  `AiServiceSecurityConfigTest` using `spring-security-test`:
  - flag off → anonymous permitted;
  - flag on → 401 without token, 200 with a mock JWT, public paths always open;
  - Phase 8 → parametrised `(roles claim × endpoint) → expected status`.
- Method-security tests for `ContractController.uploadContract` and `ChatController.chat`
  (`@WithMockUser(roles=...)` / mock JWT).

**React (Phase 5):**

- Vitest unit test for `src/api/client.js`: `Authorization` header attached when a token is
  set; exactly one silent-refresh-and-retry on a 401.

**Streamlit (Phase 6):**

- Optional `pytest` for `auth.py` refresh-window logic.

**CI (optional, recommended at Phase 1):** add `.github/workflows/build.yml` running
`mvn -B verify` (root reactor) + `mvn -B verify` in `api-gateway` + `npm ci && npm run
build` in `procuremind-react`. Non-blocking; introduced only to protect the new module.

---

## 24. Manual verification checklist

Run at the end of each phase (cumulative).

**Provider (Phases 1–2):**

- [ ] `GET http://localhost:8083/.well-known/openid-configuration` → issuer
      `http://localhost:8083`, all endpoints present.
- [ ] `GET http://localhost:8083/oauth2/jwks` → one RSA key with `kid`.
- [ ] Manual Authorization Code + PKCE flow returns a JWT; decoded token shows RS256,
      `iss`, `sub`, `aud`, `exp`, and a `roles` array.
- [ ] `grant_type=refresh_token` returns a new pair; reusing the old refresh token is
      rejected.
- [ ] `docker compose up --build -d` → every container healthy including `auth-service`.

**Permissive backend (Phases 3–4):**

- [ ] Anonymous `GET /api/contracts`, `GET /api/analysis/dashboard-metrics` via the gateway
      → 200.
- [ ] Anonymous `POST /api/contracts/upload` with a ~70 MB PDF → 202; pipeline reaches
      `ANALYZED`.
- [ ] Anonymous `POST /api/analysis/chat` → 200.
- [ ] Direct `GET http://localhost:8081/...` and `http://localhost:8082/...` → 200
      (permissive).
- [ ] Valid token on any of the above → still 200; no `X-User-*` header seen downstream.
- [ ] CORS preflight from `http://localhost:5173` returns the explicit `allowed-headers`
      list including `Authorization`.

**Frontends (Phases 5–6):**

- [ ] React: unauthenticated visit → redirect to the login page → after login, dashboard
      loads; every `/api/*` request carries `Authorization: Bearer`.
- [ ] React: access-token expiry → silent renew → no user-visible failure.
- [ ] React: logout → back to login; no token on subsequent requests.
- [ ] Streamlit: "Sign in" → login → redirected back; all tabs work; requests carry the
      bearer; logout clears the session.
- [ ] `VITE_AUTH_REQUIRED=false` / `AUTH_REQUIRED=false` → both apps run anonymously.

**Enforced (Phase 7):**

- [ ] No token → gateway `GET /api/contracts` → 401; valid token → 200.
- [ ] No token → direct `:8081` / `:8082` calls → 401.
- [ ] 70 MB upload with token → 202 and the body streams through; pipeline reaches
      `ANALYZED`.
- [ ] `/`, `/api`, `/actuator/health`, `/health/ai`, `/health/contract` → still public.
- [ ] Stop `auth-service` after JWKS is cached → existing tokens still validate; new logins
      fail.
- [ ] Both frontends: full login → all features → logout works.

**RBAC (Phase 8):**

- [ ] VIEWER: GETs 200; `POST /upload` and `/chat` → 403.
- [ ] ANALYST: upload + chat 200; `/api/users` → 403.
- [ ] ADMIN: `/api/users` 200; `/actuator/**` detail 200.
- [ ] Anonymous: all protected routes 401.
- [ ] React/Streamlit hide upload + chat controls for VIEWER.

---

## 25. Rollback strategy

- **Global kill switch:** every enforcing behaviour is gated by `AUTH_ENABLED`
  (`app.security.enforce`). Setting it to `false` on the gateway and both services and
  running `docker compose up` returns the system to fully anonymous operation in one step,
  with no data or schema migration to reverse. Frontends continue to work (they simply send
  tokens nobody checks).
- **Per phase:**
  - Phase 1 — delete `auth-service/` and the root `pom.xml` module line.
  - Phase 2 — revert `docker-compose.yaml` + `.env.example`; the container stops starting.
  - Phase 3 — revert `api-gateway` pom/yaml, delete `security/`; flag already off.
  - Phase 4 — revert both poms/yamls, delete both `security/` packages; flag already off.
  - Phase 5 — revert React files, `npm install`.
  - Phase 6 — revert Streamlit files + compose env, rebuild image.
  - Phase 7 — set `AUTH_ENABLED=false` (three lines).
  - Phase 8 — relax matcher rules to `authenticated()`, remove `@PreAuthorize` annotations,
    revert actuator `show-details`.
- **Database:** `procuremind_auth` is separate and additive. Dropping it (and the
  `auth-service` container) fully removes the identity store without affecting
  `procuremind_db`.
- **No irreversible step exists** anywhere in Phases 0–8.

---

## 26. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Spring Boot 3.5.14 vs 4.0.7 → different Spring Security lines | JWT validation is protocol-level via JWKS; `ai-service` (Security 6) and the others (Security 7) validate the identical token. Config classes differ syntactically only. |
| Spring Authorization Server's default signing key is ephemeral → tokens die on restart | Load a persistent RSA key from a mounted PKCS12 keystore; documented rotation procedure. |
| Issuer URL mismatch between browser (`localhost:8083`) and containers (`auth-service:8083`) | Static issuer = `http://localhost:8083`; resource servers use internal `jwk-set-uri` + a manual `JwtIssuerValidator`; Streamlit uses split browser/internal issuer vars. No `/etc/hosts` edit. |
| `allowed-headers: "*"` + `allow-credentials: true` is invalid and can break CORS | Replace with an explicit header allow-list at the gateway in Phase 3. |
| React token storage in `sessionStorage` → XSS exposure | Short access-token TTL (15 min) + refresh rotation with reuse detection + no `localStorage`; future BFF-cookie hardening noted, not in scope. |
| A security filter reading the request body would break the 70 MB multipart upload | Resource-server JWT validation is header-only; no body-reading filter is added; explicit 70 MB upload test in Phases 3 and 7. |
| Direct calls to published `:8081` / `:8082` bypass the gateway | Each service is its own resource server (Phase 4) — a direct call still needs a valid JWT; optional loopback port bind in Phase 8. |
| Enforcement breaks existing clients at cutover | Phases 5–6 make both frontends send tokens *before* Phase 7; Phase 7 is a single reversible flag. |
| `auth-service` outage makes the whole system unauthenticated-unusable | JWKS is cached by resource servers (tokens keep validating during a brief outage); `auth-service` has an actuator healthcheck and `restart: unless-stopped`. |
| Kafka listeners accidentally caught by a security chain | Listeners run with no HTTP request/`SecurityContext`; a slice test asserts the context loads with the chain present; pipeline E2E re-verified in Phases 4 and 7. |
| Gateway is outside the Maven reactor | Security dependencies are added directly to `api-gateway/pom.xml`; only `auth-service` joins the reactor. No reactor restructuring. |
| Streamlit native `st.login()` cannot expose the access token for API calls | Use a manual `Authlib` Authorization Code flow storing tokens in `st.session_state`. |
| Swagger UIs per service become unreachable when enforced | `/v3/api-docs/**` and `/swagger-ui/**` are on the public list in dev; can be tightened later. |

---

## 27. Definition of done

- `auth-service` runs as a Spring Authorization Server with a persistent signing key, OIDC
  discovery, JWKS, a hosted login page, JDBC-persisted clients/authorizations, and a
  `roles` claim on access tokens.
- `procuremind_auth` is a separate database whose schema is created solely by
  `auth-service` Flyway migrations; `procuremind_db` is unchanged.
- The API Gateway, `contract-service`, and `ai-service` **each independently validate**
  the JWT (signature, `iss`, `exp`) and map `roles` → authorities. No `X-User-*` trust
  anywhere. No custom token code anywhere.
- With `AUTH_ENABLED=true`: unauthenticated requests to any `/api/**` route (via the
  gateway or direct to a service port) return 401; the documented public paths remain open.
- Role rules from §12 pass the `(role × endpoint)` matrix; `POST /api/contracts/upload` and
  `POST /api/analysis/chat` require `ANALYST` or `ADMIN`.
- `procuremind-react` completes Authorization Code **+ PKCE**; `procuremind-ui` completes
  Authorization Code as a confidential client; both attach bearer tokens to every gateway
  call and handle refresh + logout.
- The Kafka parse → index → analyze pipeline is byte-for-byte unchanged and still reaches
  `ANALYZED` after an authenticated upload.
- The minimum automated tests in §23 pass; the §24 checklist is fully ticked for Phases
  0–8.
- Setting `AUTH_ENABLED=false` cleanly reverts to anonymous operation.

---

## 28. Resume / interview-relevant engineering outcomes

- Designed and deployed a **dedicated OIDC identity provider** using **Spring
  Authorization Server** (issuer, discovery, JWKS, PKCE, refresh-token rotation with reuse
  detection, JDBC-persisted authorization state) — not a hand-rolled token service.
- Implemented **multi-tier, stateless JWT resource-server validation** across an API
  gateway and two downstream microservices spanning **two Spring Boot major versions**
  (3.5.14 and 4.0.7), demonstrating that JWKS-based validation is framework-version
  independent.
- Integrated an **OAuth2 Authorization Code + PKCE** flow into a React SPA (public client)
  and an **Authorization Code** flow into a server-side Streamlit app (confidential
  client), including silent token renewal and RP-initiated logout.
- Modelled and enforced **role-based access control** via a custom `roles` claim mapped to
  Spring Security authorities, with coarse rules at the edge and method security on
  sensitive operations.
- Solved the **front-channel vs back-channel issuer URL** problem (browser vs container
  networking) the standard way, without host-file hacks.
- Delivered the whole change as an **incremental, flag-gated, fully reversible rollout**
  with a clean cutover phase — no big-bang migration, existing functionality preserved
  until a single switch.
- Explicitly rejected the **trusted-header (`X-User-*`) anti-pattern** in favour of
  independent verification at every trust boundary, and documented the reasoning.
- Kept identity concerns **isolated in their own service and database** with
  service-owned Flyway migrations, respecting the existing bounded-context boundaries and
  the event-driven pipeline.
```
