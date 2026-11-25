# identity tests (46)

`mvn -pl identity test`. No Keycloak, database or Redis needed: Keycloak is replaced by a stub `WebClient` or a mocked connector, and the database by mocked repositories. Reactive results are read with `.block()`. See the [testing guide](README.md) for the patterns used. Service behaviour: [identity](../services/identity.md).

## `KeycloakAuthServiceImplTest` (6): register and login flows

**Setup:**
- `KeycloakAuthServiceImpl` gets a mocked `KeycloakGenericConnector`, a mocked `KeycloakAdminServiceImpl` (admin token `admin-token`) and a mocked `UserService`.
- Its `WebClient` is a stub that answers every request as Keycloak's "create user" does: **201 with `Location: .../users/kc-new`**.
- Login tests use real-looking tokens: a JWT built in the test with `sub = kc-1`.

| Test | How it works | Checks |
|---|---|---|
| `localDuplicateIsRejectedBeforeKeycloakIsCalled` | `ensureAvailable` fails with 409 | `ConflictException`; the admin token is never even fetched (Keycloak untouched) |
| `failedLocalSaveDeletesTheKeycloakUser` | Keycloak creates `kc-new`, then `saveUser` fails ("db down") | The same "db down" error is returned; `DELETE .../users/kc-new` is called; audit `REGISTER_ROLLBACK` |
| `failedRollbackIsAuditedAndTheOriginalErrorIsKept` | Same, but the delete also fails (503) | Still the original "db down" error; audit `REGISTER_ROLLBACK_FAILED` |
| `loginReturnsTokensOnceTheLocalUserIsInSync` | Keycloak returns tokens; `syncAuthenticatedUser` succeeds for `sub = kc-1` | The same tokens are returned |
| `refusedLoginRevokesTheKeycloakSession` | Tokens issued, but `syncAuthenticatedUser` fails with 403 (inactive) | 403 returned **and** the Keycloak logout endpoint is called, so no session is left |
| `wrongPasswordIsAuditedWithoutTouchingAppUser` | Keycloak answers 401 | 401 returned; a failed `LOGIN` audit is written; `syncAuthenticatedUser` is never called |

## `UserServiceImplTest` (7): `app_user` rules

**Setup:** `UserServiceImpl` with a mocked `AppUserRepository` and `UserLogAuditRepository` (audit saves always succeed). The token's claims are `kc-1` / `alice` / `alice@example.com`.

| Test | How it works | Checks |
|---|---|---|
| `loginUpdatesLastLoginAndAudits` | User 7 exists and is active | `last_login_at` set; one audit row: action `LOGIN`, user 7 |
| `missingRowIsCreatedFromTheToken` | `findByKeycloakId` empty; action `REFRESH` | New row with the token's id and email, `KEYCLOAK_SYNC`, active; **no** `last_login_at` update (refresh isn't a login) |
| `concurrentInsertFallsBackToTheExistingRow` | First lookup empty, `save` fails with a duplicate (another login inserted it), second lookup finds user 9 | User 9 returned (no error) |
| `inactiveUserIs403AndAudited` | User exists with `active = false` | `AuthException` 403; `last_login_at` not updated; refusal audited |
| `databaseDownIs503` | Lookup fails with `DataAccessResourceFailureException` | `AuthException` 503 |
| `takenUsernameOrEmailIs409` | Username free, email taken | `ConflictException` |
| `freeUsernameAndEmailPass` | Both free | Completes without error |

## `UserAdminServiceImplTest` (6): activate and deactivate

**Setup:**
- `UserAdminServiceImpl` gets a mocked connector (all Keycloak calls succeed), a mocked admin service (token `admin-token`) and a mocked repository (user 5).
- For deactivate, the test puts the calling admin's JWT in the security context (`ReactiveSecurityContextHolder.withAuthentication`).

| Test | How it works | Checks |
|---|---|---|
| `deactivateDisablesInKeycloak_endsSessions_updatesAppUser_andAudits` | Another admin deactivates user `ID` | `PUT .../users/ID` (enabled=false), `POST .../users/ID/logout`, `updateActive(ID, false)`, audit `DEACTIVATE` for user 5 |
| `activateEnablesWithoutTouchingSessions` | Activate | `PUT` called; **no** logout call; `updateActive(ID, true)` |
| `adminCannotDeactivateThemselves` | The caller's `sub` equals `ID` | 409; Keycloak never called |
| `nonUuidIdIsRejectedBeforeAnyCall` | Id `../roles` (would change the admin URL) | 400; neither Keycloak nor the database touched |
| `unknownKeycloakUserIs404AndAppUserIsUntouched` | Keycloak `PUT` answers 404 | 404; `updateActive` **not** called. This also caught a bug where the update was prepared before the Keycloak step |
| `expiredAdminTokenIsRefreshedOnce` | `PUT` with the cached token → 401; `forceRefresh` gives `fresh-token`, which works | `forceRefresh` called exactly once; the update goes through |

## `KeycloakGenericConnectorTest` (5): Keycloak HTTP answers

**Setup:** a real `KeycloakGenericConnector` with a stub `WebClient` (`exchangeFunction`) that returns a chosen status and body. Each test makes a login-style POST.

| Test | Keycloak answers | Checks |
|---|---|---|
| `emptyBody4xxIsAnError` | 400, **empty body** | `AuthException` 400. **Regression test:** an empty error body used to count as success (login "succeeded" with no tokens) |
| `emptyBody5xxIsAnError` | 500, empty body | `AuthException` 503 |
| `successStillWorks` | 200 with a token | The body is returned |
| `invalidGrantIs401` | 400 `{"error":"invalid_grant"}` (revoked or expired token, refused login) | `AuthException` 401 |
| `otherBadRequestsStay400` | 400 `{"error":"invalid_request"}` | `AuthException` 400 |

## `GlobalExceptionHandlerTest` (8): what the client sees

Calls the handler methods directly. A helper `assertGeneral` checks the status, `success:false`, the exact public message, and that given secret words are **not** in the message.

| Test | How it works | Checks |
|---|---|---|
| `existingUserIsNotRevealed` | `ConflictException("User with this username or email already exists")` | 409 `Request could not be completed`: doesn't confirm the account exists |
| `keycloakDetailsAndNamesAreNotRevealed` | Keycloak error JSON, a role name, an expired token, "Keycloak is unreachable" | 404 / 404 / 401 / 503 with general messages; no role name, JSON or "Keycloak" |
| `validationAndMalformedInputAre400NotServerErrors` | `WebExchangeBindException` on `password`; `ServerWebInputException` | 400 `Invalid request`; field name not revealed |
| `unexpectedErrorsAndAccessDeniedAreGeneral` | `NullPointerException` naming a method; `AccessDeniedException("needs ADMIN")` | 500 / 403, general messages |
| `unmappedStatusesFallBackToGeneralMessages` | `publicMessage(418)`, `publicMessage(502)` | Client-error and server-error defaults |
| `keycloakUnreachableIs503` | `WebClientRequestException` (connection refused to the Keycloak URL) | 503; no port or realm in the message |
| `unmappedKeycloakErrors_5xxIs503_otherwise500` | `WebClientResponseException` 502, then 400 `invalid_client` | 503, then 500; Keycloak's error text not shown |
| `localDuplicateIs409` | `DataIntegrityViolationException` (unique constraint) | 409; constraint name not shown |

## `KeycloakRealmSecurityCheckTest` (6): startup security check

**Setup:** `KeycloakRealmSecurityCheck` with a mocked admin service (token `t`) and a mocked connector. The connector answers the realm (default role `default-roles-dwell`), the `realm-management` client (id `rm-id`), the default role's `realm-management` roles, and the `admin-cli` client.

| Test | How it works | Checks |
|---|---|---|
| `safeRealmHasNoProblems` | No admin roles in the default role; `admin-cli` without direct access grants | No problems |
| `adminRoleInTheDefaultRolesIsReported` | Default role contains `manage-users` | One problem naming "every user" and `manage-users` |
| `passwordLoginOnAdminCliIsReported` | `admin-cli` public with direct access grants | One problem naming `admin-cli` |
| `bothProblemsAreReportedTogether` | Both, with two admin roles | Two problems; the first lists both roles |
| `keycloakDownNeverStopsStartup` | Getting the admin token fails (connection refused) | `run()` doesn't throw: startup goes on, a WARN is logged |
| `revokedAdminTokenIsRefreshedOnce` | The cached token gets 401 (e.g. all sessions were ended) | Retries with a fresh token and still finds the problem |

## `GatewayAuthWebFilterTest` (5): gateway-only access

**Setup:** `GatewayAuthWebFilter` with header `X-Gateway-Secret`, secret `s3cret`, excluded path `/actuator/health`. The chain lambda records whether the request **passed**.

| Test | How it works | Checks |
|---|---|---|
| `allowsRequestWithGatewaySecret` | Correct header | Passed |
| `rejectsDirectRequestWithoutHeader` | No header (someone calling identity directly to self-assign roles) | 403, not passed |
| `rejectsWrongSecret` | Header `guess` | 403 |
| `allowsExcludedPath` | `/actuator/health` without header | Passed |
| `failsFastWithoutSecret` | Filter created with an empty secret | `IllegalStateException`: the service refuses to start unprotected |

## `SensitiveToStringTest` (3): DTOs never print secrets

Builds request and response objects and checks their Lombok `toString()`, which is what ends up in a log if someone logs the object.

| Test | How it works | Checks |
|---|---|---|
| `loginRequestHidesPassword` | `LoginRequest(bob, Hunter2!)` | Contains `bob`, not the password |
| `registerRequestHidesPasswordAndEmail` | `RegisterRequest` with email and a password credential | No password, no email (also in `Credential.toString()`) |
| `tokensAreHidden` | `RefreshRequest`, `LogoutRequest`, `TokenResponse` with tokens | No token values |
