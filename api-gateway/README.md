# api-gateway

## Purpose

The single ingress for every browser call. It is a declarative reverse proxy that also acts as
an OAuth2 resource server, applying coarse-grained role rules before forwarding.

It holds no business logic and no database.

## Responsibilities

- Route `/api/**` to contract-service, ai-service and auth-service
- Validate the JWT and map the `roles` claim to authorities
- Enforce the coarse role matrix
- Own CORS for both frontends
- Serve a landing page at `/` and a JSON route index at `/api`
- Proxy `/health/contract` and `/health/ai`

## Technology

Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.2, `spring-cloud-starter-gateway-server-webmvc`
(the **servlet** gateway, not the reactive one), Spring Security OAuth2 Resource Server.

**This module is not in the root Maven reactor.** Build it with `./mvnw -f api-gateway/pom.xml`.

## Entry point

`src/main/java/com/procuremind/api_gateway/ApiGatewayApplication.java`, a plain
`@SpringBootApplication`. Routes are declarative in `application.yaml`; there is no Java route
configuration. Port 8080.

## Routes

| Route id | Predicate | Target |
|---|---|---|
| `contract-service-route` | `/api/contracts`, `/api/contracts/**` | `${CONTRACT_SERVICE_URL:http://localhost:8081}` |
| `ai-service-route` | `/api/analysis`, `/api/analysis/**` | `${AI_SERVICE_URL:http://localhost:8082}` |
| `auth-service-users-route` | `/api/users`, `/api/users/**` | `${AUTH_SERVICE_URL:http://localhost:8083}` |
| `contract-service-health` | `/health/contract` | rewritten to `/actuator/health` on 8081 |
| `ai-service-health` | `/health/ai` | rewritten to `/actuator/health` on 8082 |

The user-administration route exists so the browser keeps a single origin; auth-service still
enforces ADMIN itself.

## Classes

### SecurityConfig

**Location:** `security/SecurityConfig.java`

Two mutually exclusive `SecurityFilterChain` beans selected by `app.security.enforce`, which
binds to `${AUTH_ENABLED:true}`.

| Constant | Value |
|---|---|
| `PUBLIC_PATHS` | `/`, `/api`, `/actuator/health`, `/actuator/health/**`, `/health/**` |
| `READ_ROLES` | `VIEWER`, `ANALYST`, `ADMIN` |
| `WRITE_ROLES` | `ANALYST`, `ADMIN` |

Enforcing chain, in order:

| Matcher | Rule |
|---|---|
| `OPTIONS /**` | permit (CORS preflight) |
| `PUBLIC_PATHS` | permit |
| `POST /api/contracts/upload` | `WRITE_ROLES` |
| `POST /api/analysis/chat` | `WRITE_ROLES` |
| `GET /api/contracts`, `/api/contracts/**` | `READ_ROLES` |
| `GET /api/analysis`, `/api/analysis/**` | `READ_ROLES` |
| `/api/users`, `/api/users/**` | `ADMIN` |
| `/actuator/**` | `ADMIN` |
| anything else | authenticated |

An unrecognised value for the property registers **neither** chain, so Spring Boot's own
locked-down default applies. The failure mode is closed, never open.

No `X-User-*` header is read, added or trusted. The original `Authorization` header is
forwarded and each downstream service validates it again.

**`corsConfigurationSource`** allows origins `http://localhost:5173` and
`http://localhost:3000`, methods GET/POST/PUT/DELETE/PATCH/OPTIONS, headers `Authorization`,
`Content-Type`, `Accept`, `X-Requested-With`, with credentials enabled. The explicit header
list matters: `"*"` with `allow-credentials: true` is invalid.

### JwtDecoderConfig

Builds `NimbusJwtDecoder.withJwkSetUri(...)` and wraps the default validators with a
`JwtIssuerValidator` for `app.security.issuer-uri`. The key-set URL and the issuer are
deliberately independent so containers can fetch keys over the internal network while still
validating the public issuer value. Default `jwk-set-uri` is
`http://localhost:8083/oauth2/jwks`; Compose overrides it.

### JwtRolesConverter

Converts the flat `roles` claim (for example `["ANALYST"]`) into `ROLE_*` authorities. A
missing claim or one that is not a collection yields no role authorities. The same class is
duplicated in contract-service, ai-service and auth-service; it is not shared through
`procuremind-common`, which deliberately carries no Spring dependency.

### GatewayWelcomeController

`GET /` returns an HTML landing page and `GET /api` returns a JSON route index. Both are
public. This is the only controller in the module.

## Multipart

`spring.servlet.multipart.max-file-size` and `max-request-size` are 70MB. Nothing in the
gateway reads the request body, which is what lets a large upload stream straight through to
contract-service.

## Testing

`GatewaySecurityConfigTest` asserts the full role by endpoint matrix, that no `X-User-*`
header authenticates anything, and that CORS preflight uses the explicit allow list.
`GatewaySecurityKillSwitchTest` covers `AUTH_ENABLED=false`. Both pin the downstream URLs to a
closed port so the assertions observe the gateway's own decision rather than whatever happens
to be running locally.
