# identity

Handles accounts and sign-in (port **8083**, base path **`/identity`**). Keycloak does the actual authentication and stores credentials and roles. identity wraps it with a small API, keeps a local copy of each user in `app_user`, and writes an audit trail.

- **Stack:** Spring WebFlux, R2DBC (PostgreSQL), WebClient (Keycloak), reactive Redis.
- **Depends on:** Keycloak realm `Dwell` (client `dwell-client`), PostgreSQL database `identity`, Redis.
- **Only reachable through the gateway:** `GatewayAuthWebFilter` rejects any request without the gateway's secret header (403).

## Endpoints

Paths as the client calls them through the gateway (identity itself serves them under `/identity`).

### Auth: public

| Method | Path | Body | What it does |
|---|---|---|---|
| POST | `/identity/api/auth/register` | `user_name`, `email`, `first_name`, `last_name`, `credentials[{type,value,temporary}]` | Creates the user in Keycloak and in `app_user`. 201 |
| POST | `/identity/api/auth/login` | `username`, `password` | Returns Keycloak tokens (`access_token`, `refresh_token`, ...) |
| POST | `/identity/api/auth/refresh` | `refresh_token` | Returns new tokens |
| POST | `/identity/api/auth/logout` | `refresh_token` | Ends the Keycloak session |

### Roles: ADMIN

| Method | Path | What it does |
|---|---|---|
| GET | `/identity/api/roles` | List realm roles |
| POST | `/identity/api/roles` | Create a realm role (`name`, `description`) |
| DELETE | `/identity/api/roles/{roleName}` | Delete a realm role |
| GET | `/identity/api/roles/users/{userId}` | A user's realm roles |
| POST | `/identity/api/roles/users/{userId}/assign` | Give roles (`roleNames: [...]`) |
| DELETE | `/identity/api/roles/users/{userId}/remove` | Take roles away |

### Users: ADMIN

| Method | Path | What it does |
|---|---|---|
| POST | `/identity/api/users/{userId}/deactivate` | Blocks the user everywhere (see below) |
| POST | `/identity/api/users/{userId}/activate` | Unblocks the user |

`{userId}` is always the **Keycloak user id** (a UUID), which is also `app_user.keycloak_id` and the `sub` claim of the user's tokens.

## How the main flows work

### Register

```
1. app_user already has this username or email?   → 409, Keycloak is not touched
2. create the user in Keycloak (admin API)         → 409 if Keycloak already has it
3. save the app_user row (created_by = SELF_REGISTER unless the request sets created_by)
4. step 3 failed?  → delete the Keycloak user again (audit REGISTER_ROLLBACK) and return the error
                     if that delete also fails: ERROR log + audit REGISTER_ROLLBACK_FAILED
```

So a user can't be left half-created: in Keycloak but missing from `app_user`.

> **Note:** a new user gets **no application role**. Until an admin assigns `CUSTOMER` (`/roles/users/{id}/assign`), every catalog call returns 403. Also, Keycloak refuses to log in a user without a first and last name ("Account is not fully set up"), so always send `first_name` and `last_name`.

### Login and refresh

```
1. ask Keycloak for tokens                 → wrong password: 401, audited (LOGIN, success=false)
2. read the user id (sub) from the token
3. find app_user by keycloak_id; create the row if it's missing (created_by = KEYCLOAK_SYNC)
4. app_user.active = false?                → 403, and the Keycloak session just created is revoked
5. login only: set last_login_at, audit LOGIN
6. return the tokens
```

- **Database down:** login and refresh fail with 503, and the session is revoked (no tokens without a local user).
- **Rejected tokens:** a revoked, expired or invalid refresh token gets 401 (Keycloak's `invalid_grant`).

### Logout

Ends the Keycloak session, then writes a `LOGOUT` audit row. The audit is best-effort: logout succeeds even if the database is down, because the session is already gone.

### Deactivate and activate

```
deactivate:  Keycloak enabled=false → end all of the user's Keycloak sessions → app_user.active=false → audit DEACTIVATE
activate:    Keycloak enabled=true  → app_user.active=true → audit ACTIVATE
```

- **Keycloak is updated first,** so if a later step fails the user is already blocked. Repeating the call is safe.
- **Guards:** an admin can't deactivate themselves (409); the id must be a UUID (400); an unknown user gets 404.
- **Short window:** an access token issued before deactivation keeps working until it expires (at most 5 minutes). Refresh and new logins are refused immediately, from any Keycloak client.

### Keycloak admin token

Admin calls (create, delete or disable users, manage roles) use `dwell-client`'s **service account** (`client_credentials` grant). The token is cached in Redis (`keycloak:admin:access_token`, `keycloak:admin:refresh_token`) and renewed with the refresh token or a fresh login. Calls that get 401 retry once with a new token.

### First admin (bootstrap)

With `BOOTSTRAP_ENABLED=true`, or the `local` profile (`application-local.yml`), identity creates at startup:
- the realm roles `ADMIN` and `CUSTOMER`;
- the admin user, assigned `ADMIN` (local: `dwell_admin` / `Admin@12345`).

It's idempotent and never changes an existing password. It retries 3 times if Keycloak is still starting, and a failure never stops the service. Keep it **off in production**.

## Data

`app_user` and `user_log_audit` are created at startup by `DatabaseConfig` from `resources/database/*.sql`. Changes must be idempotent (`ADD COLUMN IF NOT EXISTS`).

| `app_user` column | Meaning |
|---|---|
| `keycloak_id` | Keycloak user id (unique) |
| `username`, `email`, `first_name`, `last_name` | Copied at registration, or from the token on first login |
| `active` | `false` = blocked from login and refresh |
| `last_login_at` | Last successful login |
| `created_by` | `SELF_REGISTER`, `bootstrap`, `KEYCLOAK_SYNC` (row created on first login), or what the request sent |

`user_log_audit.action` values: `REGISTER`, `REGISTER_ROLLBACK`, `REGISTER_ROLLBACK_FAILED`, `LOGIN` (success or failure), `LOGOUT`, `ACTIVATE`, `DEACTIVATE`. Each row has the request's `correlation_id`.

## Errors

The client only gets a general message per status (`Invalid request`, `Authentication failed`, `Access denied`, `Resource not found`, `Request could not be completed`, `Service temporarily unavailable`). Which user, which role, and what Keycloak said are only logged.

| Situation | Status |
|---|---|
| Validation failure, bad JSON | 400 |
| Wrong password, invalid or expired token | 401 |
| Inactive user, missing role | 403 |
| Username/email taken, admin deactivating themselves | 409 |
| Keycloak or the database unreachable, Keycloak 5xx | 503 |

## Configuration

| Property / env var | Default | Purpose |
|---|---|---|
| `KEYCLOAK_URL`, `KEYCLOAK_REALM` | `http://localhost:8080`, `Dwell` | Keycloak |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | `dwell-client`, (dev secret) | Client for logins and the admin service account |
| `GATEWAY_SECRET` | `dwell-dev-gateway-secret` | Must match the gateway |
| `GATEWAY_AUTH_ENABLED` | `true` | Turn the gateway-only check off (tests only) |
| `BOOTSTRAP_ENABLED`, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | off | First admin |
| `webclient.timeout.*` | connect 5s, response 7s | Keycloak calls |
| `REDIS_HOST`, `REDIS_PORT` | localhost:6379 | Admin token cache |

## Code map

| Package | Main classes |
|---|---|
| `controller` | `AuthController`, `RoleController`, `UserController` |
| `service.impl` | `KeycloakAuthServiceImpl` (register/login/refresh/logout), `UserServiceImpl` (`app_user` sync, audit), `UserAdminServiceImpl` (activate/deactivate), `RoleServiceImpl`, `KeycloakAdminServiceImpl` (admin token + Redis), `KeycloakGenericConnector` (HTTP calls + Keycloak error mapping) |
| `bootstrap` | `AdminBootstrap`, `BootstrapProperties` |
| `config` | `GatewayAuthWebFilter`, `SecurityConfig`, `WebClientConfig`, `DatabaseConfig`, `LoggingConfig` |
| `dto` | Requests and responses; `TokenClaims` reads `sub`, username, email and names from a token |
| `exception` | `GlobalExceptionHandler`, `AuthException`, `ConflictException` |

## Tests (40)

For how each test works (setup, action and checks), see [testing/identity.md](../testing/identity.md).

Run with `mvn -pl identity test`. All are unit tests: Keycloak is replaced by a stub `WebClient` or mocks, and the database by mocked repositories.

**`KeycloakAuthServiceImplTest`**: register and login flows.
- A local duplicate is rejected before Keycloak is called.
- A failed local save deletes the Keycloak user; if that delete fails too, it's audited and the original error is kept.
- Login returns tokens only once the local user is in sync.
- A refused login (e.g. an inactive user) revokes the new Keycloak session.
- A wrong password is audited and `app_user` isn't touched.

**`UserServiceImplTest`**: the `app_user` rules.
- Login sets `last_login_at` and writes a `LOGIN` audit row.
- A missing row is created from the token (`KEYCLOAK_SYNC`).
- Two logins creating the same row at once: the second uses the first one's row.
- An inactive user gets 403, audited.
- Database down → 503.
- A taken username or email → 409; free ones pass.

**`UserAdminServiceImplTest`**: activate and deactivate.
- Deactivate disables the user in Keycloak, ends their sessions, updates `app_user` and audits.
- Activate re-enables without touching sessions.
- An admin can't deactivate themselves.
- A non-UUID id is rejected before any call.
- An unknown Keycloak user → 404, and `app_user` isn't changed.
- An expired admin token is refreshed once.

**`KeycloakGenericConnectorTest`**: mapping Keycloak's HTTP answers.
- A 4xx or 5xx with an **empty body** is still an error (this was a bug: an empty error body used to count as success).
- `invalid_grant` → 401; other 400s stay 400; normal answers work.

**`GlobalExceptionHandlerTest`**: what the client sees.
- An existing user, Keycloak details and names are never revealed.
- Validation and bad input → 400, not 500.
- Unexpected errors and access denied get general messages; unmapped statuses fall back to a general message.
- Keycloak unreachable → 503; an unmapped Keycloak 5xx → 503, otherwise 500.
- A local duplicate → 409.

**`GatewayAuthWebFilterTest`**: gateway-only access.
- A request with the right secret passes; a missing or wrong secret → 403.
- Excluded paths pass.
- Startup fails if no secret is configured.

**`SensitiveToStringTest`**: logging an object never prints secrets.
- `LoginRequest` hides the password; `RegisterRequest` hides the password and email; `TokenResponse` hides the tokens.

## Known issues

- **Security: every user can read and change all users through Keycloak's admin API.** The realm's default roles (`default-roles-dwell`) include `realm-management` → `manage-users`, and the Dwell realm's public `admin-cli` client accepts a username and password. Any registered user can get a token there and list (verified), change or delete users. Fix: remove `manage-users` from `default-roles-dwell`, and turn off direct access grants on the Dwell realm's `admin-cli`. identity is not affected by that change: its service account has `manage-users` assigned directly.
- **New users have no application role,** so they get 403 on the catalog until an admin assigns `CUSTOMER`. To change this, add `CUSTOMER` to the realm's default roles.
- **`created_by` can be set by any client** on the public register endpoint.
