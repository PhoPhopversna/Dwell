# Keycloak first-time setup

How to set up Keycloak for Dwell from an empty instance: the realm, the two clients, the first admin user and the first admin token. Do this once per environment (your laptop, staging, production). Screens and labels are from Keycloak 25 (the version in `docker/infra`).

**What you end up with:**

| Thing | What it is | Used by |
|---|---|---|
| Realm `Dwell` | Holds Dwell's users, roles and clients | Everything |
| Realm roles `ADMIN`, `CUSTOMER` | The roles the gateway checks | Users' tokens |
| Client `dwell-client` | **User logins** (login, refresh, logout). No admin power | identity (`KEYCLOAK_CLIENT_ID` / `_SECRET`) |
| Client `dwell-admin` | **identity's admin API calls** (create users, assign roles). No user logins | identity (`KEYCLOAK_SERVICE_CLIENT_ID` / `_SECRET`) |
| User `dwell_admin` | The first Dwell admin (realm role `ADMIN`) | You, to call admin endpoints |

Why two clients: see [identity → Keycloak realm security](services/identity.md#keycloak-realm-security). In short, one client can't both issue minimal user tokens and admin tokens, so identity refuses to start if they're the same client.

> **Two different "admins", don't mix them up:**
> - The **Keycloak admin** (`admin` / `admin` locally, from `KEYCLOAK_ADMIN` in `docker/infra`) logs into the Keycloak admin console in the `master` realm. Only people setting up Keycloak use it; Dwell's code never does.
> - A **Dwell admin** (e.g. `dwell_admin`) is a normal user in the `Dwell` realm with the realm role `ADMIN`. It calls Dwell's admin endpoints through the gateway.

## 1. Start Keycloak

```bash
cd docker/infra && docker compose up -d
```

Open <http://localhost:8080>, click **Administration Console** and log in with the Keycloak admin (`admin` / `admin` locally). In production, use the credentials your deployment set in `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`, and change them after first login.

## 2. Create the realm

1. Top-left realm dropdown (shows `master`) → **Create realm**.
2. **Realm name:** `Dwell` (case matters: it's in every token's issuer, `http://localhost:8080/realms/Dwell`).
3. **Create**.

Everything below happens in the `Dwell` realm. Check that the dropdown shows `Dwell` before each step.

## 3. Create the realm roles

**Realm roles → Create role**, twice:
- `ADMIN`: Dwell administrators.
- `CUSTOMER`: normal users.

(Locally, identity's bootstrap also creates these on startup; creating them by hand is harmless and you need them for step 4.)

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

   Now a user's tokens only ever contain `ADMIN`/`CUSTOMER`, even if someone gives them an admin role by mistake. Add every new role the app checks here too (e.g. `SELLER`), or it never shows up in tokens.
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
5. **Credentials** tab → copy the **Client secret**. This is `KEYCLOAK_SERVICE_CLIENT_SECRET`.

## 6. Lock down the realm

1. **Clients → admin-cli → Settings → Capability config:** untick **Direct access grants** → **Save**. (`admin-cli` is a public client in every realm; with this on, anyone can trade a username and password for a token there.)
2. **Realm settings → User registration → Default roles:** make sure there's **no** `realm-management` role (e.g. `manage-users`). Every user gets the default roles.
   Optional: add `CUSTOMER` here so new users can use the catalog right after registering (otherwise an admin assigns it).

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

A fresh Keycloak generates **new** secrets, so the dev defaults committed in `application.yml` / `application-local.yml` only work against the Keycloak they came from. Always set both secrets after creating the clients.

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

**More admins later:** log in as an admin and call `POST /identity/api/roles/users/{userId}/assign` with `{"roleNames": ["ADMIN"]}` (see [identity endpoints](services/identity.md#roles-admin)). `{userId}` is the user's Keycloak id (the `sub` claim of their token, or the id in *Users* in the console).

## 10. Check it's secure

Each of these should hold (details in [identity → Checking it by hand](services/identity.md#keycloak-realm-security)):

| Try | Expected |
|---|---|
| `client_credentials` token for `dwell-client` | Refused (`unauthorized_client`): no service account |
| Password login on `admin-cli` or `dwell-admin` | Refused (`unauthorized_client`) |
| A user's login token | `realm_access.roles` only `ADMIN`/`CUSTOMER`; no `resource_access.realm-management` |
| That user token on `GET <keycloak>/admin/realms/Dwell/users` | 403 |
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
- New secrets for both clients, set only through the environment (never the committed defaults).
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
| Users get 403 on every route although they have the role | The role isn't in `dwell-client`'s scope (step 4.4), or they logged in before you gave it (log in again) |
| Gateway 401 `invalid_token` | `KEYCLOAK_ISSUER` doesn't match the token's `iss` (e.g. `localhost` vs a hostname) or realm name case |
| Startup log `is insecure: ...` | Each listed problem matches a step: default roles (6.2), admin-cli (6.1), full scope (4.4), service account on `dwell-client` (4.3) |
