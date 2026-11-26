# api-gateway

The only entry point for clients (port **8081**). It checks the caller's Keycloak JWT, finds the route for the request, checks the caller's permissions against that route, applies rate limits, adds the shared secret, and forwards the request to identity or product-catalog. The user's `Authorization` header is forwarded too, and each service checks the token again ([common-security](common-security.md)). It also has its own small admin API to manage routes.

- **Stack:** Spring Cloud Gateway (WebFlux), Spring Security OAuth2 resource server, R2DBC (PostgreSQL), reactive Redis.
- **Depends on:** Keycloak (JWT keys), PostgreSQL database `gateway` (table `routes`), Redis (rate limits).

## What happens to a request

```
request
  │ 1. CorrelationIdWebFilter        reuse or create X-Correlation-ID; access log line at the end
  │ 2. Spring Security               validate the JWT (signature, expiry, issuer, client = dwell-client); invalid token → 401
  │                                  /api/routes/** needs route:manage
  │ 3. route matching                 routes come from the `routes` table
  │ 4. GatewaySecretHeaderFilter     sets X-Gateway-Secret (overwrites any client value)
  │ 5. RouteAuthorizationFilter      checks the caller's permissions against the route's → 401 / 403
  │ 6. DownstreamErrorFilter         downstream unreachable → 503, closed early → 502
  │ 7. RateLimitFilter               per-route limit in Redis → 429
  ▼
identity / product-catalog
```

### Route permissions (`RouteAuthorizationFilter`)

Each route row has a `permissions` column holding comma-separated permissions (the `dwell-api` client roles in the caller's token; see [common-security](common-security.md#roles-and-permissions)).

| `permissions` value | Result |
|---|---|
| `PUBLIC` | Allowed without a token |
| `product:read` or `role:read,role:write` | Caller needs at least one of these; no token → 401, none of them → 403 |
| empty / null | **Denied (403).** A new route stays closed until someone sets its permissions |

Realm role names (`ADMIN`, `CUSTOMER`) never satisfy a route: only permissions count. Paths in `app.security.public-paths` (login, register, refresh, logout, `/health`) are always allowed without a token, whatever the route says, so users can always get a token. Error bodies never say which permission was needed; the reason is only logged.

A route with a `method` (e.g. `GET`) is matched before a route with no method for the same path. That's how "GET needs `product:read`, everything else `product:write`" works (two rows).

**Older databases:** the column used to be `roles` (realm role names). On startup, `schema.sql` adds `permissions`, converts the default routes (`CUSTOMER,ADMIN` on a catalog `GET` becomes `category:read` / `product:read`, and so on), and drops `roles`. Any other old value is copied unchanged and, being a role name, denies everyone until you update that route.

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
  "permissions": ["user:manage"],
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
| `app.security.public-paths` | login, register, refresh, logout, `/health` | Always callable without a token |
| `GATEWAY_CONNECT_TIMEOUT_MS`, `GATEWAY_RESPONSE_TIMEOUT` | 5000, 30s | Downstream timeouts |
| `spring.r2dbc.*`, `spring.data.redis.*` | localhost | Routes database and Redis |
| `APP_ENV`, `LOG_*` | – | Logging; see [common-logging](common-logging.md) |

## Code map

| Package | Main classes |
|---|---|
| `security` | `SecurityConfig` (JWT, `route:manage` for `/api/routes`), `RouteAuthorizationFilter`, `RoutePermissions`, `SecurityResponses` (401/403 bodies). Token checks and role mapping come from [common-security](common-security.md) |
| `config` | `GatewaySecretHeaderFilter`, `DownstreamErrorFilter`, `RateLimitFilter`, `RateLimitConfig` (IP key), `LoggingConfig`, `SchedulingConfig` |
| `service.impl` | `RouteForwardServiceImpl` (builds gateway routes from the table), `RouteServiceImpl` (CRUD), `RouteReloadServiceImpl`, `RateLimitServiceImpl` |
| `exception` | `GlobalExceptionHandler` (errors from `/api/routes` only), `AppException`, `RouteNotFoundException`, `DataBaseException` |
| `resources` | `schema.sql` (routes table), `rate_limiter.lua` |

## Tests (22)

For how each test works (setup, action and checks), see [testing/api-gateway.md](../testing/api-gateway.md).

Run with `mvn -pl api-gateway test`. All are unit tests; no database, Redis or Keycloak needed.

**`RouteAuthorizationFilterTest`**: the permission rules for proxied requests.
- A user with one of the route's permissions is forwarded; without → 403; with no token → 401.
- An anonymous token is still 401, even if the route lists an "anonymous" value.
- A route with no permissions is denied, even for a user with every permission.
- A realm role name on a route (an old value) never matches: 403.
- A `PUBLIC` route and a configured public path need no token, even when the route has no permissions.
- Denied responses never reveal the required permissions or the route configuration.

**`RoutePermissionsTest`**: parsing the `permissions` column.
- Values are trimmed and blanks dropped.
- Joining removes duplicates and gives null for an empty list.

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
