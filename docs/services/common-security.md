# common-security

Shared request security for every Dwell service (a Maven module like [common-logging](common-logging.md), not a running service). It gives each service the same **two locks**:

1. **Gateway secret:** the request must carry `X-Gateway-Secret` (the gateway adds it), or it's rejected with **403**.
2. **JWT:** the user's own Keycloak token (forwarded by the gateway in `Authorization: Bearer ...`) must be valid, or it's rejected with **401**. Then the service's own permission rules apply (**403** if the user lacks the permission).

So a leaked gateway secret alone gets nobody in: they'd also need a valid token for a user with the right permission. And a valid token alone can't skip the gateway (rate limiting, route rules), because the secret is checked first.

**Keycloak admin calls are deliberately not in this module.** Only identity holds the `dwell-admin` client (it can create, delete and promote users). Another service that needs something like "give this user a role" calls identity's API instead of getting that secret.

## What every token must pass

| Check | Rejects | Where |
|---|---|---|
| Signature with the realm's public keys (`jwk-set-uri`) | Forged or modified tokens | Spring (Nimbus) |
| `exp` / `nbf` | Expired tokens | Spring |
| `iss` = `issuer-uri` | Tokens from another realm (e.g. `master`) | Spring |
| `azp` in `app.security.jwt.allowed-clients` (default `dwell-client`) | Real Keycloak tokens issued to **another client**, e.g. the `dwell-admin` service account | `AuthorizedPartyValidator` |

A service fails to start if `issuer-uri` is missing, so it can't run without the issuer check.

## Roles and permissions

**Dwell checks permissions, not roles.** A permission is a client role of the Keycloak client `dwell-api`; the realm roles `ADMIN` and `CUSTOMER` are composite roles that bundle permissions (setup: [Keycloak setup, step 3](../keycloak-setup.md#3-create-the-permission-client-and-the-roles)). Keycloak expands the bundle when it issues the token, so the token lists the user's actual permissions.

`KeycloakRoleConverter` turns the token into Spring authorities:

| In the token | Becomes | Check with |
|---|---|---|
| `resource_access.dwell-api.roles`: e.g. `product:read` | `product:read` | `hasAuthority(DwellPermissions.PRODUCT_READ)` |
| `realm_access.roles`: `ADMIN`, `CUSTOMER` | `ROLE_ADMIN`, `ROLE_CUSTOMER` | Not used for app rules (only identity's ops endpoints) |

Client roles are only read from `app.security.jwt.permission-client` (default `dwell-api`). Roles of any other client, such as `realm-management`, are ignored. Permission names are constants in `DwellPermissions`:

| Permission | Checked by |
|---|---|
| `category:read`, `category:write` | product-catalog (`/api/categories/**`), gateway routes |
| `product:read`, `product:write` | product-catalog (`/api/v1/products/**`, WebSocket), gateway routes |
| `role:read`, `role:write`, `user-role:assign` | identity (`/api/roles/**`), gateway routes |
| `user:manage` | identity (`/api/users/**`), gateway routes |
| `route:manage` | gateway (`/api/routes`) |

A user without the permission gets 403, whatever realm role they have.

**Roles are yours to define.** The permission list belongs to the system (each permission matches something the code checks), but which role bundles which permissions is data: admins create roles and tick permissions through identity's API ([identity → Roles and permissions](identity.md#roles-and-permissions)), like Jenkins' role strategy.

### Adding a service or a permission

There's no Keycloak step: identity creates missing permissions in Keycloak at startup (`KeycloakPermissionSync`).

1. **Name it** in `DwellPermissions`: a constant plus a line in `DESCRIPTIONS` (the description shows in the matrix).
   ```java
   public static final String ORDER_READ = "order:read";
   d.put(ORDER_READ, "Read orders");
   ```
2. **Use it** in the service's `SecurityConfig` (a new service: add the `common-security` dependency and its settings, see [Using it in a service](#using-it-in-a-service)):
   ```java
   .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAuthority(DwellPermissions.ORDER_READ)
   ```
3. **Deploy identity** with the new `common-security`. On startup it creates `order:read` in Keycloak, puts it in the token scope (through the `permission-scope` holder) and gives it to the roles in `app.keycloak.permission-sync.initial-grants` (by default `ADMIN`). Its log says `Keycloak permissions synced: created [order:read] ...`.
4. **Add the gateway route** (`POST /api/routes` with `"permissions": ["order:read"]`): routes are data, per environment.
5. **Tick it** on roles in the matrix (`/portal/auth/permissions`) for everyone else who needs it.

identity never deletes or renames a permission: removing one from the code leaves it in Keycloak (harmless; delete it there if you want). A permission created by hand in the `dwell-api` client also reaches tokens: identity bundles every `dwell-api` role into the holder.

The user's name (`Authentication#getName`, used in logs) is the `preferred_username` claim.

## Using it in a service

1. Add the dependency:
   ```xml
   <dependency>
       <groupId>com.neo</groupId>
       <artifactId>common-security</artifactId>
       <version>${project.version}</version>
   </dependency>
   ```
2. Configure Keycloak and the gateway secret in `application.yml`:
   ```yaml
   spring:
     security:
       oauth2:
         resourceserver:
           jwt:
             jwk-set-uri: ${KEYCLOAK_URL:http://localhost:8080}/realms/${KEYCLOAK_REALM:Dwell}/protocol/openid-connect/certs
             issuer-uri: ${KEYCLOAK_ISSUER:http://localhost:8080/realms/Dwell}
   app:
     gateway-auth:
       enabled: ${GATEWAY_AUTH_ENABLED:true}
       secret: ${GATEWAY_SECRET:dwell-dev-gateway-secret}
       excluded-paths: [] # e.g. /swagger-ui/**
   ```
3. Write the service's own `SecurityConfig` with its URL rules and plug in the shared beans: `JwtAuthenticationConverter` (servlet) or `ReactiveJwtAuthenticationConverterAdapter` (WebFlux), plus `ServletSecurityErrors` / `ReactiveSecurityErrors` for JSON 401/403 bodies. See `product-catalog/.../config/SecurityConfig.java` (servlet) and `identity/.../config/SecurityConfig.java` (WebFlux).

The auto-configuration (`DwellSecurityAutoConfiguration`) registers, for the service's web stack:

| Bean | When |
|---|---|
| `JwtDecoder` / `ReactiveJwtDecoder` with the checks above | `jwk-set-uri` is set (replaces Spring Boot's decoder) |
| `JwtAuthenticationConverter` / `ReactiveJwtAuthenticationConverterAdapter` | Always |
| `GatewayAuthServletFilter` / `GatewayAuthWebFilter` (runs before Spring Security) | `app.gateway-auth.enabled=true` |

## What each service enforces

| Service | Without a token | Rules |
|---|---|---|
| api-gateway | Public paths only | Each route's `permissions` from the `routes` table (any one is enough); `route:manage` for `/api/routes` |
| identity | `/api/auth/**`, `/actuator/health` | `GET /api/roles`, `GET /api/roles/users/{id}`: `role:read`; create/delete roles: `role:write`; assign/remove a user's roles: `user-role:assign`; `/api/users/**`: `user:manage`; other actuator: realm role `ADMIN`; anything else: denied |
| product-catalog | Swagger, `/actuator/health`, `/ws/**` handshake | `GET /api/categories/**`: `category:read`, other methods: `category:write`; `GET /api/v1/products/**`: `product:read`, other methods: `product:write`; anything else: denied |

The service rules repeat the gateway's route permissions on purpose. If a route is misconfigured in the gateway's table, the service still refuses.

**WebSockets (product-catalog):** SockJS can't send HTTP headers, so the client sends the token as a STOMP header on CONNECT (`Authorization: Bearer <token>`). It goes through the same checks, and the user needs `product:read`. Frames from a session that didn't authenticate are refused.

## Configuration

| Property / env var | Default | Purpose |
|---|---|---|
| `KEYCLOAK_URL`, `KEYCLOAK_REALM` | `http://localhost:8080`, `Dwell` | Where the realm's public keys come from |
| `KEYCLOAK_ISSUER` | `http://localhost:8080/realms/Dwell` | Must equal the tokens' `iss` exactly (scheme, host, port, realm case) |
| `app.security.jwt.allowed-clients` | `dwell-client` | Clients whose tokens are accepted |
| `app.security.jwt.permission-client` | `dwell-api` | Client whose roles are the permissions |
| `GATEWAY_SECRET`, `GATEWAY_AUTH_ENABLED` | dev secret, `true` | Gateway-only access (the gateway itself only sends the secret) |

## Code map

| Package | Main classes |
|---|---|
| `com.neo.common.security` | `DwellPermissions` (permission names and descriptions; `SCOPE_ROLE` holder name), `KeycloakRoleConverter`, `AuthorizedPartyValidator`, `DwellJwt` (validator + converter), `DwellJwtProperties`, `GatewayAuthProperties`, `GatewaySecretCheck` |
| `reactive` | `GatewayAuthWebFilter`, `ReactiveSecurityErrors` |
| `servlet` | `GatewayAuthServletFilter`, `ServletSecurityErrors` |
| `autoconfigure` | `DwellSecurityAutoConfiguration` |

## Tests (24)

For how each test works, see [testing/common-security.md](../testing/common-security.md). Run with `mvn -pl common-security test`; nothing external is needed.

- **`DwellJwtTest`**: real RSA-signed tokens through a decoder set up like the services' one. Valid tokens are accepted and mapped: realm roles and `dwell-api` permissions, never `realm-management` roles. A token for another client, without `azp`, from another issuer, expired, or signed with another key is rejected. A missing issuer fails startup.
- **`KeycloakRoleConverterTest`**: realm roles become `ROLE_*`; only the permission client's roles become permissions; `realm-management` roles never do.
- **`GatewayAuthWebFilterTest`** / **`GatewayAuthServletFilterTest`**: the right secret passes; a missing or wrong secret gets 403 (JSON body); excluded paths pass; startup fails without a secret.
- **`DwellSecurityAutoConfigurationTest`**: each web stack gets its decoder, converter and gateway filter; the filter only when enabled; a missing issuer fails startup.
