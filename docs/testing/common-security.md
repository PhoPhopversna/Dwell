# common-security tests (24)

`mvn -pl common-security test`. No external services needed. See the [testing guide](README.md) for the patterns used. Module behaviour: [common-security](../services/common-security.md).

## `DwellJwtTest` (7): the token rules

**Setup:** an RSA key pair generated per run, and a `NimbusJwtDecoder` built from its public key with `DwellJwt.validator("http://kc/realms/Dwell", defaults)`. This is the same validator the services use; only the key source differs (a fixed key instead of the realm's `jwk-set-uri`). Tokens are really signed (Nimbus `RSASSASigner`), with `azp`, `iss`, `exp`, `preferred_username` and `realm_access.roles = [ADMIN]`.

| Test | How it works | Checks |
|---|---|---|
| `validLoginTokenIsAcceptedAndMapped` | Valid token for `dwell-client` | Decodes; the converter gives name `alice`, authority `ROLE_ADMIN` |
| `tokenForAnotherClientIsRejected` | Correct signature and issuer, `azp = dwell-admin` | `JwtValidationException` "allowed client" (a service-account token can't be used as a user token) |
| `tokenWithoutAzpIsRejected` | No `azp` claim | Rejected |
| `tokenFromAnotherIssuerIsRejected` | `iss` = the `master` realm | Rejected |
| `expiredTokenIsRejected` | `exp` 10 minutes ago | Rejected |
| `tokenSignedWithAnotherKeyIsRejected` | Signed with a different private key | `BadJwtException` (forged token) |
| `missingIssuerConfigFailsStartup` | `DwellJwt.validator(" ", ...)` | `IllegalStateException` naming `issuer-uri` |

## `KeycloakRoleConverterTest` (4): token to authorities

Builds `Jwt`s with Spring's `Jwt.withTokenValue(...)` builder (no signature needed).

| Test | How it works | Checks |
|---|---|---|
| `mapsRealmRolesToRoleAuthorities` | `realm_access.roles = [ADMIN, CUSTOMER]` | `ROLE_ADMIN`, `ROLE_CUSTOMER` |
| `noRealmAccessClaimMeansNoAuthorities` | No claim | Empty, no error |
| `permissionClientRolesBecomePlainAuthorities` | Permission client `dwell-api` with `product:read`; `realm-management` with `manage-users` | `ROLE_CUSTOMER`, `product:read`; `manage-users` ignored |
| `otherClientsRolesAreIgnoredWithoutAPermissionClient` | Permission client blank | Empty |

## `GatewayAuthWebFilterTest` (5): gateway secret, WebFlux

**Setup:** `GatewayAuthWebFilter` with header `X-Gateway-Secret`, secret `s3cret`, excluded path `/actuator/health`. The chain lambda records whether the request **passed**.

| Test | How it works | Checks |
|---|---|---|
| `allowsRequestWithGatewaySecret` | Correct header | Passed |
| `rejectsDirectRequestWithoutHeader` | No header | 403, not passed |
| `rejectsWrongSecret` | Header `guess` | 403 |
| `allowsExcludedPath` | `/actuator/health` without header | Passed |
| `failsFastWithoutSecret` | Empty secret | `IllegalStateException`: the service refuses to start unprotected |

## `GatewayAuthServletFilterTest` (4): gateway secret, servlet

**Setup:** `GatewayAuthServletFilter`, secret `s3cret`, excluded path `/swagger-ui/**`. `chain.getRequest()` is null when the request was stopped.

| Test | How it works | Checks |
|---|---|---|
| `allowsRequestWithGatewaySecret` | Correct header | Passed, 200 |
| `rejectsMissingOrWrongSecretWithJson` | No header; wrong header | 403 with `"success":false` and `Access denied`; not passed |
| `allowsExcludedPath` | `/swagger-ui/index.html` | Passed |
| `failsFastWithoutSecret` | Blank secret | `IllegalStateException` |

## `DwellSecurityAutoConfigurationTest` (4): what a service gets

Uses Spring Boot's `WebApplicationContextRunner` (servlet) and `ReactiveWebApplicationContextRunner` (WebFlux) with only `DwellSecurityAutoConfiguration`.

| Test | How it works | Checks |
|---|---|---|
| `servletServiceGetsDecoderConverterAndGatewayFilter` | Servlet app, JWT properties, gateway auth on | `JwtDecoder`, `JwtAuthenticationConverter`, a `FilterRegistrationBean` for `GatewayAuthServletFilter`; no reactive decoder |
| `reactiveServiceGetsDecoderConverterAndGatewayFilter` | Reactive app, same properties | `ReactiveJwtDecoder`, `ReactiveJwtAuthenticationConverterAdapter`, `GatewayAuthWebFilter`; no servlet decoder |
| `gatewayFilterOnlyWhenEnabled` | Gateway auth not enabled (like the gateway itself) | No gateway filter in either stack |
| `missingIssuerFailsStartup` | `jwk-set-uri` without `issuer-uri` | Context fails; cause names `issuer-uri` |
