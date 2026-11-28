# Keycloak first-time setup

How to set up Keycloak for Dwell from an empty instance: the realm, the two clients, the first admin user and the first admin token. Do this once per environment (your laptop, staging, production). Screens and labels are from **Keycloak 26.8.2** (the version in `docker/infra`). Dwell needs **26.8.2 or later**: it uses fine-grained admin permissions v2 to let identity manage role permissions without broader admin rights. Upgrading an existing Keycloak 25: see [Upgrading from Keycloak 25](#upgrading-from-keycloak-25).

**What you end up with:**

| Thing | What it is | Used by |
|---|---|---|
| Realm `Dwell` | Holds Dwell's users, roles and clients | Everything |
| Client `dwell-api` | Holds the **permissions** (`product:read`, `category:write`, ...) as client roles. No logins | Every service checks these |
| Realm roles `ADMIN`, `CUSTOMER` | **Bundles** of permissions (composite roles). Users get a role, and with it its permissions | Users' tokens |
| Client `dwell-client` | **User logins** (login, refresh, logout). No admin power | identity (`KEYCLOAK_CLIENT_ID` / `_SECRET`) |
| Client `dwell-admin` | **identity's admin API calls** (create users, assign roles). No user logins | identity (`KEYCLOAK_SERVICE_CLIENT_ID` / `_SECRET`) |
| User `dwell_admin` | The first Dwell admin (realm role `ADMIN`) | You, to call admin endpoints |

Why two clients: see [identity → Keycloak realm security](services/identity.md#keycloak-realm-security). In short, one client can't both issue minimal user tokens and admin tokens, so identity refuses to start if they're the same client.

> **Two different "admins", don't mix them up:**
> - The **Keycloak admin** (`admin` / `admin` locally) logs into the Keycloak admin console in the `master` realm. It comes from `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` in `docker/infra`. Only `keycloak-init` (and people setting up Keycloak) use it; the Dwell services never get it.
> - A **Dwell admin** (e.g. `dwell_admin`) is a normal user in the `Dwell` realm with the realm role `ADMIN`. It calls Dwell's admin endpoints through the gateway.

## Automatic setup (keycloak-init)

**You normally don't click through this guide.** `docker/infra/docker-compose.yml` has a one-shot service, `keycloak-init`, that waits until Keycloak is healthy, does steps 2-6 below through the admin API, and exits:

```bash
(cd docker/infra && docker compose up -d)                      # Keycloak + keycloak-init (+ Postgres, Redis, Kafka, MinIO)
(cd docker/infra && docker compose logs keycloak-init)         # what it created or fixed
scripts/dwell.sh start                                         # identity then creates the permissions and dwell_admin
```

### A new environment in 5 steps

What you provide is only the Keycloak admin login and two secrets you make up:

| What | Who sets it | Who uses it |
|---|---|---|
| **Keycloak master admin** (user + password) | Keycloak creates it on its first start from `KC_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` | Only `keycloak-init`. identity never gets it |
| **`dwell-client` secret** (user logins) | **You choose it** (any long random string, nothing to copy) | `keycloak-init` sets it in Keycloak; identity uses it |
| **`dwell-admin` secret** (identity's admin calls) | **You choose it** | `keycloak-init` sets it in Keycloak; identity uses it |

The client ids (`dwell-client`, `dwell-admin`, `dwell-api`) have defaults. Locally none of this needs setting: everything defaults to matching values.

1. **Start Keycloak** with a strong master admin password.
2. **Choose the two client secrets**, e.g. `openssl rand -hex 32` (hex: no `+ / =` to trip up `.env` files; Keycloak would otherwise generate a similar random string).
3. **Run `keycloak-init`** with the master admin login and the two secrets: `docker compose up`, or `setup.sh` / the image as below.
4. **Give identity the same two secrets** (`KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_SERVICE_CLIENT_SECRET`) and start it. It creates the permissions; with bootstrap on (`local` profile, or `BOOTSTRAP_ENABLED=true` once elsewhere) it also creates the first admin ([step 8](#8-create-the-first-dwell-admin)).
5. **Create the gateway routes** once, with that admin's token ([step 9](#9-get-an-admin-token)).

After that, Keycloak needs no more manual steps: new permissions come from code, new roles from the role/permission matrix.

| Service | Keycloak settings |
|---|---|
| identity | `KEYCLOAK_URL`, `KEYCLOAK_ISSUER` + the two secrets |
| api-gateway, product-catalog | `KEYCLOAK_URL`, `KEYCLOAK_ISSUER` only (they just check tokens: no secrets) |

All three also share `GATEWAY_SECRET`, which has nothing to do with Keycloak.

### What keycloak-init does

It runs on every `up` and is safe to repeat:

| It... | Examples |
|---|---|
| **creates** what's missing | realm `Dwell`, realm roles `ADMIN` / `CUSTOMER`, clients `dwell-api` (+ `permission-scope`), `dwell-client`, `dwell-admin` and its rights |
| **never overwrites** what admins may change, never deletes | descriptions, token lifetimes, roles and their permissions, users, other clients |
| **enforces** the settings Dwell's security depends on, logging `FIXED: ...` | `dwell-client`: full scope off, no service account; `dwell-admin`: service account only; `admin-cli` without password logins; no `realm-management` roles in the default roles; `dwell-admin`'s rights limited to `dwell-api` |
| **sets the client secrets** from its environment | `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_SERVICE_CLIENT_SECRET` (never printed) |

It does **not** create permissions (identity does, at startup: [step 3](#3-create-the-permission-client-and-the-roles)) or users (identity's bootstrap: [step 8](#8-create-the-first-dwell-admin)).

**Secrets.** Locally they default to identity's local dev defaults, so a fresh Keycloak and identity match with no configuration. Anywhere else, set your own in `docker/infra/.env` (see `.env.example`) and the same values in identity's environment. The environment is the source of truth: change a secret, run `docker compose up keycloak-init`, restart identity, and it's rotated. Any `*_SECRET` / `*_PASSWORD` can also be given as `<NAME>_FILE` (a path to a mounted file).

**Without Docker Compose** (an existing or hosted Keycloak), run the same script from your machine (needs `bash`, `curl`, `jq`), or build `docker/infra/keycloak-init` and run the image once per deploy:

```bash
KC_URL=https://keycloak.example.com KC_ADMIN_USER=... KC_ADMIN_PASSWORD=... \
KEYCLOAK_CLIENT_SECRET=... KEYCLOAK_SERVICE_CLIENT_SECRET=... \
docker/infra/keycloak-init/setup.sh
```

All variables are listed at the top of `docker/infra/keycloak-init/setup.sh`. When it fails it exits non-zero with the step, the call and Keycloak's answer (`ERROR: ...`).

The steps below are what `keycloak-init` does, kept as a reference for checking or doing it by hand.

## 1. Start Keycloak

```bash
cd docker/infra && docker compose up -d
```

Open <http://localhost:8080>, click **Administration Console** and log in with the Keycloak admin (`admin` / `admin` locally). In production, use the credentials your deployment set in `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`, and change them after first login.

## 2. Create the realm

1. Top-left realm dropdown (shows `master`) → **Create realm**.
2. **Realm name:** `Dwell` (case matters: it's in every token's issuer, `http://localhost:8080/realms/Dwell`).
3. **Create**.

Everything below happens in the `Dwell` realm. Check that the dropdown shows `Dwell` before each step.

## 3. Create the permission client and the roles

Dwell checks **permissions**, never role names. A permission is a client role of the client `dwell-api`; a realm role (`ADMIN`, `CUSTOMER`, or any role you make) is just a bundle of permissions. To give someone access you still give them a role; to make a new kind of user (e.g. a seller), you make a new role that bundles the right permissions, with no code change.

**You don't create the permissions yourself.** The services' permissions are listed in code (`DwellPermissions` in common-security, with descriptions), and **identity creates any that are missing at every startup**. It also gives each permission it creates to the roles in `app.keycloak.permission-sync.initial-grants` (default: `ADMIN` gets every new permission, `CUSTOMER` gets `category:read` and `product:read`), once, at creation; after that, ticks are yours to change.

| Permission | Allows | Initially `ADMIN` | Initially `CUSTOMER` |
|---|---|---|---|
| `category:read` | Read categories and their attributes | ✓ | ✓ |
| `category:write` | Create, change and delete categories and their attributes | ✓ | |
| `product:read` | Read products, variants and images (and the image WebSocket) | ✓ | ✓ |
| `product:write` | Create, change and delete products, variants and images | ✓ | |
| `role:read` | List roles, their permissions and a user's roles | ✓ | |
| `role:write` | Create and delete roles and tick their permissions | ✓ | |
| `user-role:assign` | Give users roles and take them away | ✓ | |
| `user:manage` | Activate and deactivate users | ✓ | |
| `route:manage` | Manage the gateway's routes (`/api/routes`) | ✓ | |

1. **Clients → Create client**, client type `OpenID Connect`, **Client ID** `dwell-api` → **Next**. **Capability config:** Client authentication **On**, untick **every** authentication flow (nobody logs in through it; it only holds roles) → **Next → Save**.
2. **Roles** tab of `dwell-api` → **Create role** `permission-scope`, description "Not a permission: bundles every permission so they reach tokens. Never assign it." identity fills it with every permission (step 4.4 explains why).
3. **Realm roles → Create role**: `ADMIN` (Dwell administrators) and `CUSTOMER` (normal users). Locally, identity's bootstrap also creates these on startup; creating them by hand is harmless. Create them before identity's first start so they receive the initial grants.

**Making your own roles** (Jenkins style): an admin creates a role and ticks its permissions through identity's API, no Keycloak console needed:

```http
POST /identity/api/roles                          {"name": "backend", "description": "Backend team"}
PUT  /identity/api/roles/backend/permissions      {"permissions": ["product:read", "product:write"]}
POST /identity/api/roles/users/{userId}/assign    {"roleNames": ["backend"]}
GET  /identity/api/permissions/matrix             every role and its ticked permissions
```

The `PUT` sends the complete list of ticked boxes; identity adds and removes the difference. Users get the change on their next login or token refresh (at most the access-token lifetime). Details: [identity → Roles and permissions](services/identity.md#roles-and-permissions).

## 4. Create `dwell-client` (user logins)

1. **Clients → Create client.**
2. **General settings:** client type `OpenID Connect`, **Client ID** `dwell-client` → **Next**.
3. **Capability config:**
   - **Client authentication:** On (it's a confidential client: identity holds its secret).
   - **Authentication flow:** tick only **Direct access grants** (identity logs users in with their username and password). Untick **Standard flow** and leave **Service accounts roles** unticked.
   - **Next → Save.**
4. **Client scopes** tab → **dwell-client-dedicated** → **Scope** tab:
   - Turn **Full scope allowed** off.
   - **Assign role** → filter by realm roles → tick `ADMIN` and `CUSTOMER` → **Assign**.
   - **Assign role** → **Filter by clients** → tick **`permission-scope`** (only this one) → **Assign**.

   Now a user's tokens only ever contain these roles and the permissions, even if someone gives them an admin role by mistake. Keycloak treats everything `permission-scope` bundles as in scope, and identity keeps every permission in it, so **new permissions reach tokens without coming back here**. A new realm role (e.g. `SELLER`) only needs adding here if something checks the role name itself; Dwell's permission checks don't.

   *Older setups* listed all nine permissions here instead; that still works, and identity's startup check accepts either.
5. **Credentials** tab → copy the **Client secret**. This is `KEYCLOAK_CLIENT_SECRET`.

## 5. Create `dwell-admin` (identity's admin calls)

1. **Clients → Create client**, client type `OpenID Connect`, **Client ID** `dwell-admin` → **Next**.
2. **Capability config:**
   - **Client authentication:** On.
   - **Authentication flow:** tick only **Service accounts roles**. Untick **Standard flow** and **Direct access grants**, so nobody can log in through this client.
   - **Next → Save.**
3. **Service accounts roles** tab → **Assign role** → change the filter to **Filter by clients** → search `realm-management` → tick:
   `manage-users`, `view-users`, `query-users`, `manage-realm`, `view-realm`, `query-clients`, `view-clients` → **Assign**.
4. Leave **Full scope allowed** on for this client (*Client scopes → dwell-admin-dedicated → Scope*). Its only "user" is its own service account, which holds exactly the roles above.
5. **Client scopes** tab → **Add client scope** → `kc-admin-api` → **Add (Default)**. Since Keycloak 26.8.2 the Admin API only accepts tokens with this scope; without it every identity admin call gets 401.
6. Let `dwell-admin` tick permissions on roles, and nothing more about clients (it deliberately has no `manage-clients`):
   1. **Realm settings → Admin permissions**: turn on → **Save**. A **Permissions** item appears in the menu.
   2. **Permissions → Policies → Create policy → Client**: name `dwell-admin client`, client `dwell-admin`. It must be a **client** policy: Keycloak evaluates a service account's admin permissions with the *client* as the identity, so a user policy naming the service-account user never matches.
   3. **Permissions → Create permission → Clients**: name `dwell-admin may manage dwell-api permissions`, scopes **manage** and **map-roles-composite**, enforce access to **specific clients** → `dwell-api`, policy `dwell-admin client` → **Save**.

   Now `dwell-admin` can create `dwell-api` roles (identity's startup sync) and add or remove them on any role (ticking), for `dwell-api` only. Tested: it still can't add admin roles (`realm-management`) to a role, change `dwell-client` or its scope, read client secrets, create clients or give itself admin roles. It can edit `dwell-api`'s own settings, which is harmless: nobody logs in through `dwell-api`, and the services reject tokens issued to it.
7. **Credentials** tab → copy the **Client secret**. This is `KEYCLOAK_SERVICE_CLIENT_SECRET`.

## 6. Lock down the realm

1. **Clients → admin-cli → Settings → Capability config:** untick **Direct access grants** → **Save**. (`admin-cli` is a public client in every realm; with this on, anyone can trade a username and password for a token there.)
2. **Realm settings → User registration → Default roles:** make sure there's **no** `realm-management` role (e.g. `manage-users`). Every user gets the default roles.
   Leave `CUSTOMER` out of the default roles too. identity gives it to people who register (`app.registration.default-roles`), and adding it here would give it to every user, including existing ones and service accounts.

## 7. Give identity the client settings

Put the ids and the two secrets you copied in the repo's `.env` (copy `.env.example`; `scripts/dwell.sh` loads it), or in your deployment's environment:

```bash
KEYCLOAK_URL=http://localhost:8080
KEYCLOAK_REALM=Dwell
KEYCLOAK_CLIENT_ID=dwell-client
KEYCLOAK_CLIENT_SECRET=<dwell-client secret from step 4>
KEYCLOAK_SERVICE_CLIENT_ID=dwell-admin
KEYCLOAK_SERVICE_CLIENT_SECRET=<dwell-admin secret from step 5>
```

With `keycloak-init` you don't copy anything: it sets the secrets from its environment, which defaults to identity's local dev defaults. If you create the clients by hand, Keycloak generates **new** secrets, so set both in identity's environment.

identity refuses to start if `KEYCLOAK_SERVICE_CLIENT_ID` / `_SECRET` are missing or name `dwell-client`. The gateway and product-catalog only verify tokens, so they don't need any client secret.

## 8. Create the first Dwell admin

**Locally:** start identity with the `local` profile. On startup it creates the roles and the user `dwell_admin` / `Admin@12345` with `ADMIN`:

```bash
SPRING_PROFILES_ACTIVE=local mvn -pl identity spring-boot:run
# or: scripts/dwell.sh start identity
```

**Other environments:** either start identity once with
`BOOTSTRAP_ENABLED=true BOOTSTRAP_ADMIN_USERNAME=... BOOTSTRAP_ADMIN_EMAIL=... BOOTSTRAP_ADMIN_PASSWORD=...` and then turn bootstrap off again, or create the user by hand: **Users → Add user** (username, email, **Email verified** on) → **Create** → **Credentials → Set password** (Temporary off) → **Role mapping → Assign role** → `ADMIN`.

Either way, check identity's startup log for:
- `Keycloak realm Dwell security check passed`. If it says `is insecure: ...` instead, each problem it names maps to a step above.

## 9. Get an admin token

Through the gateway (once its routes exist):

```bash
curl -s http://localhost:8081/identity/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dwell_admin","password":"Admin@12345"}'
```

**In a new environment the gateway has no routes yet**, and creating them (`/api/routes`) needs an admin token. Get the first one from identity directly, passing the gateway secret yourself:

```bash
curl -s http://localhost:8083/identity/api/auth/login \
  -H "X-Gateway-Secret: $GATEWAY_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"username":"dwell_admin","password":"Admin@12345"}'
```

Use `data.access_token` from the answer as `Authorization: Bearer <token>`, then create the routes listed in the [developer guide](README.md#3-gateway-routes).

**More admins later:** log in as an admin and call `POST /identity/api/roles/users/{userId}/assign` with `{"roleNames": ["ADMIN"]}` (see [identity endpoints](services/identity.md#roles)). `{userId}` is the user's Keycloak id (the `sub` claim of their token, or the id in *Users* in the console).

## 10. Check it's secure

Each of these should hold (details in [identity → Checking it by hand](services/identity.md#keycloak-realm-security)):

| Try | Expected |
|---|---|
| `client_credentials` token for `dwell-client` | Refused (`unauthorized_client`): no service account |
| Password login on `admin-cli` or `dwell-admin` | Refused (`unauthorized_client`) |
| A user's login token | `realm_access.roles` only `ADMIN`/`CUSTOMER`; `resource_access.dwell-api.roles` = the role's permissions (all nine for an admin, `category:read` + `product:read` for a customer); no `resource_access.realm-management` |
| That user token on `GET <keycloak>/admin/realms/Dwell/users` | 401 (Keycloak 26.8.2+: no `kc-admin-api` scope; older versions answer 403) |
| `client_credentials` token for `dwell-admin` on the same URL | 200 |

For example:

```bash
TOKEN_URL=http://localhost:8080/realms/Dwell/protocol/openid-connect/token
# must be refused
curl -s -d grant_type=client_credentials -d client_id=dwell-client \
  -d client_secret="$KEYCLOAK_CLIENT_SECRET" $TOKEN_URL
# must return an access_token
curl -s -d grant_type=client_credentials -d client_id=dwell-admin \
  -d client_secret="$KEYCLOAK_SERVICE_CLIENT_SECRET" $TOKEN_URL
```

## Production checklist

- Change the Keycloak admin password; don't run `start-dev`.
- New secrets for both clients, set only through the environment (never the committed defaults): the same values for `keycloak-init` and identity.
- `docker compose logs keycloak-init` (or the job's output) ends with `Keycloak setup done`.
- `BOOTSTRAP_ENABLED` off after the first admin exists, and change that admin's password.
- Set `KEYCLOAK_URL` / `KEYCLOAK_ISSUER` to the public Keycloak URL the tokens are issued under.
- Startup log says `security check passed`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| identity won't start: `keycloak.service-client-id and keycloak.service-client-secret must be set` | Step 7: `KEYCLOAK_SERVICE_CLIENT_ID` / `_SECRET` not set |
| identity won't start: `must not be the login client` | `KEYCLOAK_SERVICE_CLIENT_ID` is `dwell-client`; use `dwell-admin` |
| Login returns 401 for a correct password, or `unauthorized_client` | `dwell-client` secret wrong, or **Direct access grants** off on `dwell-client` |
| Register, role or user admin calls fail with 401/403 / bootstrap fails | `dwell-admin` secret wrong, **Service accounts roles** off, or missing `realm-management` roles (step 5) |
| Users get 403 on every route although they have the role | The role doesn't bundle the permission (step 3.4), the permission isn't in `dwell-client`'s scope (step 4.4), or they logged in before you changed it (log in again). Decode the token: `resource_access.dwell-api.roles` must list the permission |
| Gateway 401 `invalid_token` | `KEYCLOAK_ISSUER` doesn't match the token's `iss` (e.g. `localhost` vs a hostname) or realm name case |
| identity's admin calls (register, roles, users) all fail with 401 after a Keycloak upgrade | `dwell-admin` lacks the `kc-admin-api` client scope (step 5.5) |
| Ticking permissions (`PUT /identity/api/roles/{role}/permissions`) gives 403/503 | Step 5.6 missing, or its policy is a **user** policy instead of a **client** policy |
| Startup log `Could not sync Keycloak permissions: ... needs 'manage' on client dwell-api` | Step 5.6's permission lacks the **manage** scope |
| Startup log `Keycloak permissions incomplete ... permission-scope is not in the scope of dwell-client` | Step 4.4: put `permission-scope` in `dwell-client`'s scope (one time) |
| Startup log `is insecure: ...` | Each listed problem matches a step: default roles (6.2), admin-cli (6.1), full scope (4.4), service account on `dwell-client` (4.3) |
