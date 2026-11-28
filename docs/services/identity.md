# identity

Handles accounts and sign-in (port **8083**, base path **`/identity`**). Keycloak does the actual authentication and stores credentials and roles. identity wraps it with a small API, keeps a local copy of each user in `app_user`, and writes an audit trail.

- **Stack:** Spring WebFlux, R2DBC (PostgreSQL), WebClient (Keycloak), reactive Redis.
- **Depends on:** Keycloak realm `Dwell` (client `dwell-client`), PostgreSQL database `identity`, Redis.
- **Two locks on every request** ([common-security](common-security.md)): the gateway's secret header (403 without it) **and** the user's own JWT, checked again here (401 if missing or invalid). `/api/auth/**` (login, register, refresh, logout) and `/actuator/health` need no token. Everything else needs a **permission** (403 otherwise), even if the gateway's route table says otherwise; the permission each endpoint needs is in the tables below. Other actuator endpoints need the realm role `ADMIN`; unknown paths are denied.

## Endpoints

Paths as the client calls them through the gateway (identity itself serves them under `/identity`).

### Auth: public

| Method | Path | Body | What it does |
|---|---|---|---|
| POST | `/identity/api/auth/register` | `user_name`, `email`, `first_name`, `last_name`, `credentials[{type,value,temporary}]` | Creates the user in Keycloak and in `app_user`. 201 |
| POST | `/identity/api/auth/login` | `username`, `password` | Returns Keycloak tokens (`access_token`, `refresh_token`, ...) |
| POST | `/identity/api/auth/refresh` | `refresh_token` | Returns new tokens |
| POST | `/identity/api/auth/logout` | `refresh_token` | Ends the Keycloak session |

### Roles

| Method | Path | Permission | What it does |
|---|---|---|---|
| GET | `/identity/api/roles` | `role:read` | List realm roles |
| POST | `/identity/api/roles` | `role:write` | Create a realm role (`name`, `description`) |
| DELETE | `/identity/api/roles/{roleName}` | `role:write` | Delete a realm role |
| GET | `/identity/api/roles/users/{userId}` | `role:read` | A user's realm roles |
| POST | `/identity/api/roles/users/{userId}/assign` | `user-role:assign` | Give roles (`roleNames: [...]`) |
| DELETE | `/identity/api/roles/users/{userId}/remove` | `user-role:assign` | Take roles away |

`user-role:assign` can give any role, including `ADMIN`, so treat it like admin power: only bundle it into roles you'd trust with that.

### Roles and permissions

Roles work like Jenkins' role strategy: **the system defines the permissions, you define the roles.** Create any role (`POST /identity/api/roles`), tick its permissions here, and give it to users. Nothing about roles is fixed in code: `ADMIN` and `CUSTOMER` are just the roles that ship pre-ticked.

| Method | Path | Permission | What it does |
|---|---|---|---|
| GET | `/identity/api/permissions` | `role:read` | Every permission (`name`, `description`): the columns of a role/permission screen |
| GET | `/identity/api/permissions/matrix` | `role:read` | Every role with its ticked permissions, plus every permission, in one call |
| GET | `/identity/api/roles/{roleName}/permissions` | `role:read` | One role's ticked permissions |
| PUT | `/identity/api/roles/{roleName}/permissions` | `role:write` | Replace the role's ticks: body `{"permissions": [...]}` is the **complete** list (`[]` removes all); identity adds and removes the difference |

- **Permissions** are the client roles of `dwell-api` (`DwellPermissions` lists the ones the services check). A permission name that doesn't exist → 400, and nothing changes.
- **When it takes effect:** a user's permissions are in their token, so they change on the next login or token refresh (at most the access-token lifetime).
- **Lockout guard:** you can't untick `role:write` from a role you have yourself (409): with nobody left who can change roles, only the Keycloak console could undo it. Ask another admin.
- **`role:write` is admin power too:** whoever has it can tick any permission onto a role, including their own.
- **Audit:** every change writes `ROLE_PERMISSIONS` (who, role, added, removed) to `user_log_audit`.
- **The matrix** leaves out Keycloak's own roles (`offline_access`, `uma_authorization`, `default-roles-dwell`) and shows the permissions a role bundles directly.
- **Keycloak side:** identity's admin client may only add and remove `dwell-api` roles on roles (fine-grained admin permissions, [setup step 5.6](../keycloak-setup.md#5-create-dwell-admin-identitys-admin-calls)); it can't hand out admin roles or change clients.

### Users

| Method | Path | Permission | What it does |
|---|---|---|---|
| POST | `/identity/api/users/{userId}/deactivate` | `user:manage` | Blocks the user everywhere (see below) |
| POST | `/identity/api/users/{userId}/activate` | `user:manage` | Unblocks the user |

`{userId}` is always the **Keycloak user id** (a UUID), which is also `app_user.keycloak_id` and the `sub` claim of the user's tokens.

## How the main flows work

### Register

```
1. app_user already has this username or email?   → 409, Keycloak is not touched
2. create the user in Keycloak (admin API)         → 409 if Keycloak already has it
3. give the user the default roles                 → app.registration.default-roles, default [CUSTOMER]
4. save the app_user row (created_by = SELF_REGISTER unless the request sets created_by)
5. step 3 or 4 failed? → delete the Keycloak user again (audit REGISTER_ROLLBACK) and return the error
                         if that delete also fails: ERROR log + audit REGISTER_ROLLBACK_FAILED
```

So a user can't be left half-created: in Keycloak but without their role, or missing from `app_user`.

- **Default role:** someone who registers can use the catalog straight away. `CUSTOMER` bundles `category:read` and `product:read`; no admin step is needed. Set `app.registration.default-roles: []` to give no role (an admin then assigns one), or list other realm roles. Only registration does this: users created in the Keycloak console get no role automatically.
- **Role assignment fails** (e.g. the role doesn't exist in Keycloak): the caller gets a general 503 and the cause is logged as an ERROR (`Could not give new user ... the registration roles ...`). The Keycloak user is deleted again, so the person can simply try later.

> **Note:** Keycloak refuses to log in a user without a first and last name ("Account is not fully set up"), so always send `first_name` and `last_name`.

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

Admin calls (create, delete or disable users, manage roles) use the **`dwell-admin` service account** (`KEYCLOAK_SERVICE_CLIENT_ID`, `client_credentials` grant), never `dwell-client`. The token is cached in Redis per client (`keycloak:admin:<client>:access_token`, `keycloak:admin:<client>:refresh_token`) and renewed with the refresh token or a fresh login. Calls that get 401 retry once with a new token. Setting up the clients: [Keycloak first-time setup](../keycloak-setup.md).

### First admin (bootstrap)

With `BOOTSTRAP_ENABLED=true`, or the `local` profile (`application-local.yml`), identity creates at startup:
- the realm roles `ADMIN` and `CUSTOMER`;
- the admin user, assigned `ADMIN` (local: `dwell_admin` / `Admin@12345`). It's created through the normal register flow, so it also gets the registration default roles (`CUSTOMER`); that's harmless, since `ADMIN` already bundles every permission.

It's idempotent and never changes an existing password. It retries 3 times if Keycloak is still starting, and a failure never stops the service. Keep it **off in production**.

### Permission sync (every startup)

identity makes Keycloak match the code's permission list (`DwellPermissions`), so a new permission needs no Keycloak step in any environment (`KeycloakPermissionSync`, after the bootstrap):

1. creates the holder role `permission-scope` and every missing permission, with its description;
2. bundles **every** `dwell-api` role into the holder (also ones made by hand), so they all reach tokens: the holder is in `dwell-client`'s scope;
3. gives a permission it just created to the roles in `app.keycloak.permission-sync.initial-grants` (default `ADMIN: ["*"]`, `CUSTOMER: [category:read, product:read]`). Only at creation: an untick in the matrix stays.

Log: `Keycloak permissions synced: created [...], added to token scope [...]` when something changed, then `Keycloak permissions check passed (n permissions)` or a WARN naming what's missing. It never deletes or renames anything, never changes `dwell-client`, and never stops startup. `KEYCLOAK_PERMISSION_SYNC=false` makes it report only. Keycloak side: [setup step 5.6](../keycloak-setup.md#5-create-dwell-admin-identitys-admin-calls) (`manage` on `dwell-api`).

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
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | `dwell-client`, (dev secret) | Client for user logins only |
| `KEYCLOAK_SERVICE_CLIENT_ID`, `KEYCLOAK_SERVICE_CLIENT_SECRET` | none (`local` profile: `dwell-admin`, dev secret) | **Required.** Service client for identity's admin API calls; must differ from `KEYCLOAK_CLIENT_ID`, or identity won't start |
| `GATEWAY_SECRET` | `dwell-dev-gateway-secret` | Must match the gateway |
| `GATEWAY_AUTH_ENABLED` | `true` | Turn the gateway-only check off (tests only) |
| `KEYCLOAK_ISSUER` | `http://localhost:8080/realms/Dwell` | Tokens' `iss`; with `KEYCLOAK_URL`/`KEYCLOAK_REALM` for the realm's keys. See [common-security](common-security.md#configuration) |
| `KEYCLOAK_PERMISSION_SYNC`, `app.keycloak.permission-sync.initial-grants` | `true`, `ADMIN: ["*"]`, `CUSTOMER: [category:read, product:read]` | Startup permission sync and which roles get a newly created permission |
| `app.registration.default-roles` | `[CUSTOMER]` | Realm roles a self-registered user gets; `[]` = none |
| `BOOTSTRAP_ENABLED`, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | off | First admin |
| `webclient.timeout.*` | connect 5s, response 7s | Keycloak calls |
| `REDIS_HOST`, `REDIS_PORT` | localhost:6379 | Admin token cache |

## Code map

| Package | Main classes |
|---|---|
| `controller` | `AuthController`, `RoleController`, `RolePermissionController` (permissions, matrix, ticking), `UserController` |
| `service.impl` | `KeycloakAuthServiceImpl` (register/login/refresh/logout), `UserServiceImpl` (`app_user` sync, audit), `UserAdminServiceImpl` (activate/deactivate), `RoleServiceImpl`, `RolePermissionServiceImpl` (roles as bundles of permissions), `KeycloakAdminServiceImpl` (admin token + Redis), `KeycloakGenericConnector` (HTTP calls + Keycloak error mapping) |
| `bootstrap` | `AdminBootstrap`, `BootstrapProperties`, `KeycloakRealmSecurityCheck` (startup security check), `KeycloakPermissionSync` (creates missing permissions at startup), `PermissionSyncProperties` |
| `config` | `SecurityConfig` (JWT + permission rules; gateway secret and token checks come from [common-security](common-security.md)), `WebClientConfig`, `DatabaseConfig`, `LoggingConfig` |
| `dto` | Requests and responses; `TokenClaims` reads `sub`, username, email and names from a token |
| `exception` | `GlobalExceptionHandler`, `AuthException`, `ConflictException` |

## Tests (73)

For how each test works (setup, action and checks), see [testing/identity.md](../testing/identity.md).

Run with `mvn -pl identity test`. All are unit tests: Keycloak is replaced by a stub `WebClient` or mocks, and the database by mocked repositories.

**`KeycloakAuthServiceImplTest`**: register and login flows.
- A local duplicate is rejected before Keycloak is called.
- A self-registered user gets `CUSTOMER` before the local row is saved; the configured roles are used, and an empty list assigns none.
- A failed role assignment deletes the Keycloak user and gives a general 503; no local row is written.
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

**`SecurityConfigTest`**: the permission rules, through the real filter chain.
- No token → 401; a realm role without permissions → 403.
- `role:read` reads roles but changes nothing; `role:write` creates and deletes roles but can't assign them; `user-role:assign` assigns and removes but can't delete roles (`DELETE /api/roles/users/...` is never treated as deleting a role named `users`); `user:manage` activates and deactivates.
- `role:read` sees the permission list, the matrix and a role's ticks; ticking needs `role:write` (not `user-role:assign`).

**`RolePermissionServiceImplTest`**: ticking permissions on roles.
- Ticking adds what's missing and removes the rest (one Keycloak call each), and is audited with who, added and removed.
- The same ticks change nothing; an unknown permission → 400 with nothing changed; an unknown role → 404.
- You can't untick `role:write` from a role you have; someone without that role can.
- The matrix shows every business role (not Keycloak's built-ins) and every permission; the `permission-scope` holder is never listed or tickable.

**`KeycloakPermissionSyncTest`**: creating permissions at startup.
- A new Keycloak gets every permission, the holder filled, and the initial grants (`ADMIN` all, `CUSTOMER` the two reads); one in sync isn't written to.
- A permission new in the code is created and granted alone; an earlier untick stays unticked.
- A permission created by hand reaches tokens (holder) but is granted to nobody.
- A holder missing from `dwell-client`'s scope is reported (older setups listing every permission pass); switched off, it only reports; a missing grant role is reported and the rest goes on; a 403 names the missing right.

**`KeycloakPropertiesTest`**: the admin service client.
- Startup fails without a service client, or if it is the login client.

**`SensitiveToStringTest`**: logging an object never prints secrets.
- `LoginRequest` hides the password; `RegisterRequest` hides the password and email; `TokenResponse` hides the tokens.

## Keycloak realm security

These settings stop ordinary users from getting admin power in Keycloak, its admin API being able to list, change and delete users.

| Setting | Must be | Why |
|---|---|---|
| Realm default roles (`default-roles-dwell`) | **No** `realm-management` roles (e.g. `manage-users`) | Every user gets the default roles, so an admin role there makes every user an admin of the realm |
| Dwell realm's `admin-cli` client | **Direct access grants off** | It's a public client: with direct access grants on, anyone can swap a username and password for a token there |
| `dwell-client` (user logins) | **Full scope off**; scope = `ADMIN`, `CUSTOMER` and the `dwell-api` permissions | Tokens from your login then carry only these roles, even if a user is given an admin role by mistake |

**Two clients, two jobs:**

| Client | Used for | Settings |
|---|---|---|
| `dwell-client` | User login, refresh, logout | Full scope off, scope `ADMIN` + `CUSTOMER` + the `dwell-api` permissions; service account off |
| `dwell-admin` | identity's admin API calls only (`KEYCLOAK_SERVICE_CLIENT_ID` / `_SECRET`) | Service account only (no login flows); roles `manage-users`, `view-users`, `query-users`, `manage-realm`, `view-realm`, `query-clients`, `view-clients` |

Full scope off applies to *every* token a client issues, including its service account's, so identity's admin calls need their own client. There is no fallback: if `KEYCLOAK_SERVICE_CLIENT_ID` / `_SECRET` aren't set, or name the login client, identity fails at startup.

> **A new permission** must also be added to `dwell-client`'s scope (*Clients → dwell-client → Client scopes → dwell-client-dedicated → Scope*), or it never appears in tokens and users get 403. A new realm role that only bundles existing permissions needs nothing more. See [Keycloak setup, step 3](../keycloak-setup.md#3-create-the-permissions-and-the-roles-that-bundle-them).

**Startup check.** On every start, identity checks these settings, plus that `dwell-client` has no service account left (`KeycloakRealmSecurityCheck`), and logs:
- `INFO Keycloak realm Dwell security check passed`, or
- `ERROR Keycloak realm Dwell is insecure: ...`, naming each problem.

It only reads; it never changes Keycloak or stops startup. If Keycloak can't be reached it logs a WARN. Turn it off with `app.keycloak.security-check.enabled=false`.

**How to fix it** (Keycloak admin console, realm Dwell; do it in every environment that logs the ERROR):
1. *Realm settings → User registration → Default roles*: remove every `realm-management` role.
2. *Clients → admin-cli → Settings → Capability config*: turn off *Direct access grants*.
3. Create `dwell-admin` as above ([step by step](../keycloak-setup.md#5-create-dwell-admin-identitys-admin-calls)), put its id and secret in `KEYCLOAK_SERVICE_CLIENT_ID` / `KEYCLOAK_SERVICE_CLIENT_SECRET`, and restart identity.
4. On `dwell-client`: *Client scopes → dwell-client-dedicated → Scope*: turn off *Full scope allowed* and add the realm roles `ADMIN` and `CUSTOMER`. Then turn off *Service accounts roles* in its capability config.
5. *Sessions → Sign out all active sessions*, so tokens already issued with the old roles stop working. Everyone, including identity's cached admin token, gets a new token on the next call.
6. Restart identity and check that the startup log says `security check passed`.

**Checking it by hand:**
- A token for `admin-cli` with a username and password must be refused (`unauthorized_client`).
- A normal user's token must get **403** from `GET <keycloak>/admin/realms/Dwell/users`.
- A token from your login must contain only `ADMIN`/`CUSTOMER` in `realm_access` and **no `resource_access.realm-management`**.

## Known issues

- **The `dwell-client` secret's default value is committed** in `application.yml`. Once `dwell-admin` is in use and `dwell-client`'s service account is off, that secret no longer gives admin access, but set `KEYCLOAK_CLIENT_SECRET` in every non-local environment and rotate it anyway.
- **`created_by` can be set by any client** on the public register endpoint.
