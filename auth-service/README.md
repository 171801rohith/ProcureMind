# auth-service

## Purpose

The identity provider. It is an **OAuth2 Authorization Server and OpenID Connect provider**
built on Spring Authorization Server, and it also exposes a small ADMIN-only user
administration API.

Nimbus JOSE + JWT appears in this service only as the library that Spring Authorization Server
and Spring Security use underneath. There is no hand-written token issuance, signing or
parsing anywhere in this repository.

## Responsibilities

- Authenticate users against `procuremind_auth` and issue OIDC tokens
- Host the branded login page
- Publish JWKS so resource servers can validate tokens without calling back
- Add a `roles` claim to access and id tokens
- Register and reconcile the two OAuth2 clients on start
- Serve `GET/POST/DELETE /api/users` for administrators

## Technology

Java 21, Spring Boot 4.0.7, Spring Authorization Server, Spring Security, Spring Web MVC,
Spring Data JPA, Thymeleaf (login page only), Flyway, PostgreSQL, Lombok.

## Entry point

`src/main/java/com/procuremind/auth/AuthServiceApplication.java`, a plain
`@SpringBootApplication`. Port 8083, database `procuremind_auth`. On start Flyway applies
three migrations and `DataSeeder` runs as an `ApplicationRunner`.

## Authorization server vs resource server

This service is **both**, in separate filter chains:

| Chain | Order | Scope | Role |
|---|---|---|---|
| `AuthorizationServerConfig.authorizationServerSecurityFilterChain` | `HIGHEST_PRECEDENCE` | OAuth2/OIDC endpoints | Authorization server |
| `ApiSecurityConfig.apiSecurityFilterChain` | `1` | `/api/**` | Resource server for its own API |
| `DefaultSecurityConfig.defaultSecurityFilterChain` | `2` | everything else | Form login, health, static assets |

The other three services (gateway, contract-service, ai-service) are resource servers only.

## Package structure

```
com/procuremind/auth/
├── config/      authorization server, security chains, signing key, properties
├── bootstrap/   DataSeeder
├── web/         login page controller, user admin controller, error mapping
├── service/     UserDetailsService and user administration
├── repository/  User and Role repositories
├── entity/      User, Role
├── dto/         UserDtos
└── security/    JwtRolesConverter
```

---

## Classes

### AuthorizationServerConfig

**Location:** `config/AuthorizationServerConfig.java`

Declares the authorization-server chain explicitly rather than relying on Boot's
autoconfiguration, so the sibling chain can keep health and login public. It enables OIDC with
`authorizationServer.oidc(Customizer.withDefaults())`, which is what provides
`/connect/logout`, `/userinfo` and the discovery document.

| Bean | Purpose |
|---|---|
| `authorizationServerSecurityFilterChain` | OAuth2/OIDC endpoints, CORS, and an HTML entry point that redirects browsers to `/login` |
| `registeredClientRepository` | `JdbcRegisteredClientRepository`, so client registrations survive a restart |
| `authorizationService` | `JdbcOAuth2AuthorizationService` |
| `authorizationConsentService` | `JdbcOAuth2AuthorizationConsentService` |
| `rolesTokenCustomizer` | Adds the `roles` claim |

**`rolesTokenCustomizer` detail that matters.** It collects `ROLE_*` authorities, strips the
prefix, and stores the claim as an **`ArrayList`**. The claim is persisted with the
authorization in `oidc_id_token_metadata` and read back by `JdbcOAuth2AuthorizationService`,
whose Jackson mapper only trusts types on Spring Security's allow-list. A `TreeSet` serialised
fine but failed to deserialise, which made RP-initiated logout return 400 and leave the
session alive. Do not change the collection type without checking that allow-list.

### DefaultSecurityConfig

**Location:** `config/DefaultSecurityConfig.java`

Form login against the branded page, the `BCryptPasswordEncoder` (strength 12) shared by user
passwords and the confidential client secret, and a narrow CORS source for the endpoints the
browser calls directly (`/oauth2/token`, `/oauth2/revoke`, `/oauth2/jwks`, `/userinfo`,
`/connect/**`, `/.well-known/**`).

Public: `/actuator/health*`, `/actuator/info`, `/login`, `/error`, `/css/**`,
`/default-ui.css`, `/webjars/**`, `/assets/**`. Everything else requires authentication.
`formLogin` points at `/login` with `failureUrl("/login?error")`; logout returns to
`/login?logout`.

### ApiSecurityConfig

**Location:** `config/ApiSecurityConfig.java`

Scopes itself to `/api/**` and treats this service as a resource server for the tokens it
issues. `/api/users/**` requires `hasRole("ADMIN")`. Stateless, CSRF disabled.

### JwkKeyConfig

**Location:** `config/JwkKeyConfig.java`

Supplies the RSA signing key. With `auth.jwk.keystore-path` set it loads a PKCS12 keystore;
otherwise it generates a **non-persistent in-memory RSA key** with a stable key id, which is
dev-only because tokens do not survive a restart. `scripts/gen-auth-keystore.sh` generates a
persistent keystore.

### DataSeeder

**Location:** `bootstrap/DataSeeder.java`, an `ApplicationRunner`, `@Transactional`

| Step | Behaviour |
|---|---|
| `seedAdminUser()` | Creates the bootstrap ADMIN only when `auth.admin.password` is set; never overwrites an existing user. Roles themselves come from Flyway, not from here |
| `seedReactClient()` | Public client `procuremind-react`: `ClientAuthenticationMethod.NONE`, authorization code + refresh token grants, PKCE required, consent disabled, scopes `openid profile roles` |
| `reconcileReactRedirectUris(...)` | Adds the silent-renew redirect URI to an already-registered SPA client. Without it an environment seeded before silent renew existed rejects every `prompt=none` renewal |
| `seedStreamlitClient()` | Confidential client `procuremind-streamlit` with `client_secret_basic`, seeded only when the secret is set |

Token settings for both: self-contained access tokens, TTLs from `auth.token.*` (defaults
15 minutes and 8 hours), `reuseRefreshTokens(false)`.

**Refresh tokens are not issued to the React client.** Spring Authorization Server's
`OAuth2RefreshTokenGenerator` returns `null` for the authorization-code grant when the client
authenticates with `none`. The registration keeps the grant so the client could be made
confidential later without a migration. The SPA renews with a `prompt=none` iframe instead.

### JpaUserDetailsService

**Location:** `service/JpaUserDetailsService.java`

`loadUserByUsername(String)` loads the user with roles and maps each role name to a
`ROLE_*` authority for form login. `User.roles` is `@ManyToMany(fetch = EAGER)`.

### UserAdminService

**Location:** `service/UserAdminService.java`

| Method | Behaviour |
|---|---|
| `listUsers()` | Maps entities to `UserResponse`. **Never exposes `passwordHash`** |
| `createUser(CreateUserRequest)` | Rejects a duplicate username, defaults to `VIEWER` when no roles are given, uppercases and validates each role against the `roles` table, BCrypt-encodes the password |
| `deleteUser(UUID)` | Returns false when the user does not exist |

### Web layer

| Class | Purpose |
|---|---|
| `web/LoginPageController` | `GET /login` renders `templates/login.html`. Error text is deliberately generic so neither field is confirmed |
| `web/UserAdminController` | `/api/users` with a class-level `@PreAuthorize("hasRole('ADMIN')")` |
| `web/UserAdminExceptionHandler` | Scoped to the user controller. Duplicate username maps to 409, unknown role to 400, bean-validation failures to 400 with per-field messages |

---

## Endpoints

### GET /api/users
Returns `UserResponse` records: `id`, `username`, `email`, `enabled`, `roles`. ADMIN only.

### POST /api/users
Body `CreateUserRequest`: `username` (required), `password` (required, 8 to 100 chars),
`email` (optional), `roles` (optional, defaults to `VIEWER`). Returns 201 with `Location`.
409 on duplicate, 400 on unknown role or validation failure. ADMIN only.

### DELETE /api/users/{id}
204 or 404. ADMIN only.

### GET /login
Public. The branded Thymeleaf page. Credentials still post to Spring Security's own `/login`
processing endpoint, and Thymeleaf injects the CSRF field automatically because the form uses
`th:action`.

Standard OAuth2/OIDC endpoints come from Spring Authorization Server:
`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/userinfo`, `/connect/logout`,
`/.well-known/openid-configuration`.

---

## Database

`procuremind_auth`, migrated by Flyway:

| Migration | Creates |
|---|---|
| `V1__identity_schema.sql` | `users`, `roles`, `user_roles` |
| `V2__seed_roles.sql` | `ADMIN`, `ANALYST`, `VIEWER` |
| `V3__oauth2_authorization_server_schema.sql` | `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent` |

`User`: `id`, `username` (unique), `passwordHash`, `email`, `enabled`, `createdAt`,
`updatedAt`, `roles` (`@ManyToMany` eager).
`Role`: `id` (Short), `name` (unique).

---

## Issuer and keys

`auth.issuer-uri` defaults to `http://localhost:8083` and is the **browser-facing** value
advertised in the discovery document and validated as the `iss` claim by every resource
server. Resource servers fetch keys from a different URL (`auth-service:8083` inside Docker).
Keeping the two separate is deliberate; do not derive one from the other.

## Testing

`LoginPageControllerTest` and `UserAdminControllerTest` are web slices that need no database.
`TokenClaimsCustomizerTest` includes a round-trip through the same Jackson configuration the
JDBC authorization service uses, which is the regression guard for the logout bug.
`AuthServiceApplicationTests`, `LoginPageTest` and `RegisteredClientSeedTest` use Testcontainers
and need Docker.
