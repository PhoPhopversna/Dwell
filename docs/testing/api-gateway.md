# api-gateway tests (23)

`mvn -pl api-gateway test`. No database, Redis or Keycloak needed. See the [testing guide](README.md) for the patterns used. Service behaviour: [api-gateway](../services/api-gateway.md).

## `RouteAuthorizationFilterTest` (8)

**Setup:** `RouteAuthorizationFilter` is configured with admin role `ADMIN` and public path `/identity/api/auth/login`. Each test builds:
- a `MockServerWebExchange` for the request;
- a gateway `Route` carrying a `roles` value in its metadata (as the real route loader does);
- optionally a caller: a `JwtAuthenticationToken` whose `realm_access.roles` claim is converted by the real `KeycloakRealmRoleConverter`.

The chain lambda records whether the request was **forwarded**.

| Test | Route roles | Caller | Checks |
|---|---|---|---|
| `userWithRequiredRoleIsForwarded` | `CUSTOMER,ADMIN` | CUSTOMER | Forwarded, no status set |
| `userWithoutRequiredRoleGets403` | `ADMIN` | CUSTOMER | Not forwarded, 403 |
| `missingTokenGets401` | `CUSTOMER` | none | Not forwarded, 401 |
| `anonymousAuthenticationGets401EvenIfRouteListsAnonymousRole` | `ANONYMOUS` | Spring's anonymous token | 401: the anonymous token never counts as logged in |
| `routeWithoutRolesIsDenied` | empty | ADMIN | 403: a route with no roles is closed, even for admins |
| `publicRouteNeedsNoToken` | `PUBLIC` | none | Forwarded |
| `configuredPublicPathNeedsNoTokenEvenWithoutRoles` | empty, path `/identity/api/auth/login` | none | Forwarded: configured public paths always pass |
| `deniedResponsesNeverRevealRequiredRolesOrRouteConfig` | `ADMIN`, then empty | CUSTOMER | Body is exactly `{"success":false,"message":"Access denied","data":null}`: no role names |

## `RouteRolesTest` (2)

Calls the static helpers that read and write the comma-separated `routes.roles` column.

| Test | How it works | Checks |
|---|---|---|
| `parseTrimsAndDropsBlanks` | `" ADMIN, ,CUSTOMER "`, `null`, `"  "` | `[ADMIN, CUSTOMER]`, empty, empty |
| `joinDedupesAndReturnsNullWhenEmpty` | `[ADMIN, " ADMIN ", CUSTOMER]`, blanks, `null` | `"ADMIN,CUSTOMER"`, `null`, `null` (no role = deny) |

## `KeycloakRealmRoleConverterTest` (2)

Builds `Jwt`s with Spring's `Jwt.withTokenValue(...)` builder.

| Test | How it works | Checks |
|---|---|---|
| `mapsRealmRolesToRoleAuthorities` | Token with `realm_access.roles = [ADMIN, CUSTOMER]` | Authorities `ROLE_ADMIN`, `ROLE_CUSTOMER` |
| `noRealmAccessClaimMeansNoAuthorities` | Token without the claim | Empty (no error) |

## `DownstreamErrorFilterTest` (3)

Runs `DownstreamErrorFilter` with a chain lambda that **fails** the way the proxy call would.

| Test | How it works | Checks |
|---|---|---|
| `connectionRefusedIs503WithTheStandardBody` | Chain fails with a `ConnectException` wrapped in another exception; correlation id `c-1` | 503, body `{"success":false,"message":"Service temporarily unavailable","data":null,"correlationId":"c-1"}` (finds the cause through the wrapper) |
| `connectTimeoutIs503` | Netty `ConnectTimeoutException` | 503 |
| `otherErrorsPassThroughUnchanged` | `IllegalStateException("bug")` | The same exception is rethrown (not turned into 503) |

## `RouteServiceImplTest` (3)

`RouteServiceImpl` with a mocked `RouteRepository` and `RouteMapper`. Uses `StepVerifier` to check the `Mono` result.

| Test | How it works | Checks |
|---|---|---|
| `missingRouteIs404_notADatabaseError` | `findFirstById` returns empty | `RouteNotFoundException` with status 404. **Regression test:** this used to become a 500 logged as a database failure |
| `databaseFailureOnGetIs500` | `findFirstById` fails | `DataBaseException` (500) |
| `invalidOrDuplicateRouteDataIs400` | `save` fails with `DataIntegrityViolationException` (e.g. null `uri`) | `AppException` with status 400 |

## `GlobalExceptionHandlerTest` (4)

Calls the handler methods directly and reads the `ResponseEntity<GenericResponse>`.

| Test | How it works | Checks |
|---|---|---|
| `notFoundKeepsItsStatusAndMessage` | `RouteNotFoundException(404, "Route with id 9 is not found")` | 404, same message, `success:false` |
| `badInputIs400WithAGeneralMessage` | `ServerWebInputException` caused by a JSON decoding error | 400 `Invalid request` |
| `frameworkStatusExceptionsKeepTheirStatus` | `MethodNotAllowedException` | 405 `Method Not Allowed` |
| `unexpectedErrorsAre500AndNeverRevealDetails` | `IllegalStateException` mentioning a DB host; an `AppException` 503 mentioning Redis | 500 general message; the 503 message doesn't mention Redis |

## `SchedulingConfigTest` (1)

Starts a small Spring context (`ApplicationContextRunner`) with `SchedulingConfig`, Boot's task scheduling, `RateLimitServiceImpl`, and mocked Redis and repository.

| Test | How it works | Checks |
|---|---|---|
| `pathPatternCacheRefreshIsActuallyScheduledEvery60Seconds` | Reads the scheduled tasks Spring registered | Exactly one `refreshPatternCache` task, fixed delay 60 000 ms. Without `@EnableScheduling` it would silently never run |
