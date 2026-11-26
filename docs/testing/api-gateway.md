# api-gateway tests (22)

`mvn -pl api-gateway test`. No database, Redis or Keycloak needed. See the [testing guide](README.md) for the patterns used. Service behaviour: [api-gateway](../services/api-gateway.md).

## `RouteAuthorizationFilterTest` (9)

**Setup:** `RouteAuthorizationFilter` is configured with public path `/identity/api/auth/login`. Each test builds:
- a `MockServerWebExchange` for the request;
- a gateway `Route` carrying a `permissions` value in its metadata (as the real route loader does);
- optionally a caller: a `JwtAuthenticationToken` with realm role `ADMIN` and the given `dwell-api` permissions, converted by the real `KeycloakRoleConverter` (common-security).

The chain lambda records whether the request was **forwarded**.

| Test | Route permissions | Caller | Checks |
|---|---|---|---|
| `userWithOneOfTheRoutesPermissionsIsForwarded` | `category:read,category:write` | `category:read` | Forwarded, no status set |
| `userWithoutTheRoutesPermissionGets403` | `category:write` | `category:read` | Not forwarded, 403 |
| `missingTokenGets401` | `category:read` | none | Not forwarded, 401 |
| `anonymousAuthenticationGets401EvenIfRouteListsAnonymousRole` | `ANONYMOUS` | Spring's anonymous token | 401: the anonymous token never counts as logged in |
| `routeWithoutPermissionsIsDenied` | empty | every permission | 403: a route with no permissions is closed for everyone |
| `publicRouteNeedsNoToken` | `PUBLIC` | none | Forwarded |
| `configuredPublicPathNeedsNoTokenEvenWithoutPermissions` | empty, path `/identity/api/auth/login` | none | Forwarded: configured public paths always pass |
| `deniedResponsesNeverRevealRequiredPermissionsOrRouteConfig` | `category:write`, then empty | `category:read` | Body is exactly `{"success":false,"message":"Access denied","data":null}`: no permission names |
| `realmRoleNamesNeverSatisfyARoute` | `ADMIN` (an old value) | realm role ADMIN, `category:read` | 403: only permissions count, so old route values fail closed |

## `RoutePermissionsTest` (2)

Calls the static helpers that read and write the comma-separated `routes.permissions` column.

| Test | How it works | Checks |
|---|---|---|
| `parseTrimsAndDropsBlanks` | `" product:read, ,product:write "`, `null`, `"  "` | `[product:read, product:write]`, empty, empty |
| `joinDedupesAndReturnsNullWhenEmpty` | `[product:read, " product:read ", product:write]`, blanks, `null` | `"product:read,product:write"`, `null`, `null` (no permission = deny) |

The role converter tests moved to common-security: see [KeycloakRoleConverterTest](common-security.md#keycloakroleconvertertest-4-token-to-authorities).

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
