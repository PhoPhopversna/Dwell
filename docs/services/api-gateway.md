# api-gateway

The only entry point for clients (port **8081**). It checks the caller's Keycloak JWT, finds the route for the request, checks the caller's roles against that route, applies rate limits, adds the shared secret, and forwards the request to identity or product-catalog. It also has its own small admin API to manage routes.

- **Stack:** Spring Cloud Gateway (WebFlux), Spring Security OAuth2 resource server, R2DBC (PostgreSQL), reactive Redis.
- **Depends on:** Keycloak (JWT keys), PostgreSQL database `gateway` (table `routes`), Redis (rate limits).

## What happens to a request

```
request
  │ 1. CorrelationIdWebFilter        reuse or create X-Correlation-ID; access log line at the end
  │ 2. Spring Security               validate the JWT (signature, expiry, issuer); invalid token → 401
  │                                  /api/routes/** needs the ADMIN role
  │ 3. route matching                 routes come from the `routes` table
  │ 4. GatewaySecretHeaderFilter     sets X-Gateway-Secret (overwrites any client value)
  │ 5. RouteAuthorizationFilter      checks the caller's roles against the route's roles → 401 / 403
  │ 6. DownstreamErrorFilter         downstream unreachable → 503, closed early → 502
  │ 7. RateLimitFilter               per-route limit in Redis → 429
  ▼
identity / product-catalog
```

### Route roles (`RouteAuthorizationFilter`)

Each route row has a `roles` column holding comma-separated Keycloak realm roles.

| `roles` value | Result |
|---|---|
| `PUBLIC` | Allowed without a token |
| `ADMIN` or `CUSTOMER,ADMIN` | Caller needs at least one of these roles; no token → 401, wrong role → 403 |
| empty / null | **Denied (403).** A new route stays closed until someone sets its roles |

Paths in `app.security.public-paths` (login, register, refresh, logout, `/health`) are always allowed without a token, whatever the route says, so users can always get a token. Error bodies never say which role was needed; the reason is only logged.

A route with a `method` (e.g. `GET`) is matched before a route with no method for the same path. That's how "GET for customers, everything else for admins" works (two rows).

### Rate limiting

There are two independent limits:
1. **Global, per client IP** (`spring.cloud.gateway.default-filters.RequestRateLimiter`): Redis token bucket, 10 requests/second, bursts of up to 20.
2. **Per route** (`RateLimitFilter` → `RateLimitServiceImpl` → `rate_limiter.lua`): a fixed window, keyed by `ip:normalizedPath:method`. Set with the route's `rate_limit` (max requests) and `rate_limit_duration` (seconds); `0` or null means no limit. Over the limit → **429** with the standard error body.

The route config used by the per-route limit is cached in memory and refreshed every 60 seconds (`@Scheduled`, `SchedulingConfig`). A route change can therefore take up to a minute to affect rate limiting. Forwarding itself updates immediately.

### Downstream failures (`DownstreamErrorFilter`)

| What happened | Client gets |
|---|---|
| Connection refused, connect timeout (5s), unknown host | 503 `Service temporarily unavailable` |
| Downstream closed the connection before answering | 502 `Bad gateway` |
| No response within 30s | 504 (Spring Cloud Gateway) |

The log line names the route and target. Timeouts: `GATEWAY_CONNECT_TIMEOUT_MS` (5000) and `GATEWAY_RESPONSE_TIMEOUT` (30s).

## Endpoints

### Proxied

Anything that matches a row in `routes` is forwarded. See the route list in the [developer guide](../README.md#3-gateway-routes).

### Route admin API (ADMIN only)

| Method | Path | What it does |
|---|---|---|
| GET | `/api/routes` | List all routes |
| GET | `/api/routes/{id}` | One route; 404 if missing |
| POST | `/api/routes` | Create a route; 400 if fields are missing, too long, or a duplicate |
| PUT | `/api/routes/{id}` | Update a route; 404 if missing |
| DELETE | `/api/routes/{id}` | Delete a route; 404 if missing |
| GET | `/health` | Health check (public) |

Every change reloads the routes immediately (`RouteReloadService` publishes `RefreshRoutesEvent`).

#### Managing routes

```http
POST /api/routes
Authorization: Bearer <admin token>
Content-Type: application/json

{
  "uri": "http://localhost:8083",
  "path": "/identity/api/users/**",
  "method": "",
  "roles": ["ADMIN"],
  "description": "User management",
  "group_code": "DWELL",
  "status": "ACTIVE",
  "rate_limit": 0,
  "rate_limit_duration": 0,
  "created_by": "your-name"
}
```

`method: ""` means any method. Error codes in the response's `errorCode`: `404` not found, `00031` invalid or duplicate route data, `00030` database failure.

> **Known gap:** the `status` column is not used when routes are loaded (`RouteForwardServiceImpl` loads every row), so an `INACTIVE` route still forwards traffic. Delete the route to disable it.

## Configuration

| Property / env var | Default | Purpose |
|---|---|---|
| `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ISSUER` | `http://localhost:8080`, `Dwell` | Where JWT keys come from; tokens must be from this issuer |
| `GATEWAY_SECRET` | `dwell-dev-gateway-secret` | Shared secret sent downstream; **must match** identity and product-catalog |
| `app.security.admin-role` | `ADMIN` | Role needed for `/api/routes` |
| `app.security.public-paths` | login, register, refresh, logout, `/health` | Always callable without a token |
| `GATEWAY_CONNECT_TIMEOUT_MS`, `GATEWAY_RESPONSE_TIMEOUT` | 5000, 30s | Downstream timeouts |
| `spring.r2dbc.*`, `spring.data.redis.*` | localhost | Routes database and Redis |
| `APP_ENV`, `LOG_*` | – | Logging; see [common-logging](common-logging.md) |

## Code map

| Package | Main classes |
|---|---|
| `security` | `SecurityConfig` (JWT, ADMIN for `/api/routes`), `RouteAuthorizationFilter`, `RouteRoles`, `KeycloakRealmRoleConverter`, `SecurityResponses` (401/403 bodies) |
| `config` | `GatewaySecretHeaderFilter`, `DownstreamErrorFilter`, `RateLimitFilter`, `RateLimitConfig` (IP key), `LoggingConfig`, `SchedulingConfig` |
| `service.impl` | `RouteForwardServiceImpl` (builds gateway routes from the table), `RouteServiceImpl` (CRUD), `RouteReloadServiceImpl`, `RateLimitServiceImpl` |
| `exception` | `GlobalExceptionHandler` (errors from `/api/routes` only), `AppException`, `RouteNotFoundException`, `DataBaseException` |
| `resources` | `schema.sql` (routes table), `rate_limiter.lua` |

## Tests (23)

For how each test works (setup, action and checks), see [testing/api-gateway.md](../testing/api-gateway.md).

Run with `mvn -pl api-gateway test`. All are unit tests; no database, Redis or Keycloak needed.

**`RouteAuthorizationFilterTest`**: the role rules for proxied requests.
- A user with a required role is forwarded; without it → 403; with no token → 401.
- An anonymous token is still 401, even if the route lists an "anonymous" role.
- A route with no roles is denied.
- A `PUBLIC` route and a configured public path need no token, even when the route has no roles.
- Denied responses never reveal the required roles or the route configuration.

**`RouteRolesTest`**: parsing the `roles` column.
- Values are trimmed and blanks dropped.
- Joining removes duplicates and gives null for an empty list.

**`KeycloakRealmRoleConverterTest`**: JWT to Spring authorities.
- `realm_access.roles` become `ROLE_<name>`.
- A token without that claim has no roles.

**`DownstreamErrorFilterTest`**: answers when a service can't be reached.
- Connection refused → 503 with the standard JSON body and correlation id.
- Connect timeout → 503.
- Other errors pass through unchanged.

**`RouteServiceImplTest`**: route CRUD error mapping.
- A missing route is a 404, not a database error (this was a bug: it used to return 500).
- A database failure is a 500.
- Invalid or duplicate route data is a 400.

**`GlobalExceptionHandlerTest`**: response shape for `/api/routes` errors.
- 404 keeps its message.
- Bad JSON or a wrong parameter type → 400 "Invalid request".
- Framework errors keep their status (e.g. 405).
- Unexpected errors → 500 without any internal detail.

**`SchedulingConfigTest`**: the rate-limit route cache really refreshes every 60 seconds (`@Scheduled` is enabled).
