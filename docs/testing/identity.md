# identity tests (73)

`mvn -pl identity test`. No Keycloak, database or Redis needed: Keycloak is replaced by a stub `WebClient` or a mocked connector, and the database by mocked repositories. Reactive results are read with `.block()`. See the [testing guide](README.md) for the patterns used. Service behaviour: [identity](../services/identity.md).

## `KeycloakAuthServiceImplTest` (10): register and login flows

**Setup:**
- `KeycloakAuthServiceImpl` gets a mocked `KeycloakGenericConnector`, a mocked `KeycloakAdminServiceImpl` (admin token `admin-token`), a mocked `UserService`, a mocked `RoleService` (role assignment succeeds unless a test says otherwise) and real `RegistrationProperties` (default roles `[CUSTOMER]`).
- Its `WebClient` is a stub that answers every request as Keycloak's "create user" does: **201 with `Location: .../users/kc-new`**.
- Login tests use real-looking tokens: a JWT built in the test with `sub = kc-1`.

| Test | How it works | Checks |
|---|---|---|
| `localDuplicateIsRejectedBeforeKeycloakIsCalled` | `ensureAvailable` fails with 409 | `ConflictException`; the admin token is never even fetched (Keycloak untouched) |
| `failedLocalSaveDeletesTheKeycloakUser` | Keycloak creates `kc-new`, then `saveUser` fails ("db down") | The same "db down" error is returned; `DELETE .../users/kc-new` is called; audit `REGISTER_ROLLBACK` |
| `failedRollbackIsAuditedAndTheOriginalErrorIsKept` | Same, but the delete also fails (503) | Still the original "db down" error; audit `REGISTER_ROLLBACK_FAILED` |
| `selfRegisteredUserGetsCustomerBeforeTheLocalRowIsSaved` | Successful registration | `assignRolesToUser("kc-new", [CUSTOMER])` is called **before** `saveUser` (`InOrder`); no rollback |
| `failedRoleAssignmentDeletesTheKeycloakUserAndGives503` | `assignRolesToUser` fails with "Role not found: CUSTOMER" (404) | `AuthException` 503 `Registration is temporarily unavailable` (the role name isn't revealed); `DELETE .../users/kc-new` is called; `saveUser` never called |
| `configuredRolesAreUsedAndAnEmptyListAssignsNone` | Default roles `[CUSTOMER, NEWSLETTER]`, then `[]` | Both roles assigned the first time; no assignment the second time |
| `explicitRolesReplaceTheRegistrationDefaults` | `register(request, [ADMIN])` (the bootstrap admin) | Only `ADMIN` is assigned, not the registration default `CUSTOMER` |
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

## `KeycloakRealmSecurityCheckTest` (8): startup security check

**Setup:** `KeycloakRealmSecurityCheck` with a mocked admin service (token `t`) and a mocked connector. The connector answers the realm (default role `default-roles-dwell`), the `realm-management` client (id `rm-id`), the default role's `realm-management` roles, and the `admin-cli` client.

| Test | How it works | Checks |
|---|---|---|
| `safeRealmHasNoProblems` | No admin roles in the default role; `admin-cli` without direct access grants | No problems |
| `adminRoleInTheDefaultRolesIsReported` | Default role contains `manage-users` | One problem naming "every user" and `manage-users` |
| `passwordLoginOnAdminCliIsReported` | `admin-cli` public with direct access grants | One problem naming `admin-cli` |
| `bothProblemsAreReportedTogether` | Both, with two admin roles | Two problems; the first lists both roles |
| `keycloakDownNeverStopsStartup` | Getting the admin token fails (connection refused) | `run()` doesn't throw: startup goes on, a WARN is logged |
| `fullScopeOnTheLoginClientIsReported` | `dwell-client` has full scope on | One problem naming `dwell-client` and "full scope" |
| `serviceAccountOnTheLoginClientIsReported` | `dwell-client` still has a service account | One problem naming `dwell-client`, "service account" and `dwell-admin` |
| `revokedAdminTokenIsRefreshedOnce` | The cached token gets 401 (e.g. all sessions were ended) | Retries with a fresh token and still finds the problem |

## `KeycloakPropertiesTest` (3): the admin service client is required

**Setup:** `KeycloakProperties` with login client `dwell-client`; each test sets the service client and calls `afterPropertiesSet()` (what Spring runs after binding).

| Test | How it works | Checks |
|---|---|---|
| `aSeparateServiceClientIsAccepted` | `dwell-admin` with a secret | No exception |
| `startupFailsWithoutAServiceClient` | Id and secret missing; blank id; blank secret | `IllegalStateException` naming `KEYCLOAK_SERVICE_CLIENT_ID`: identity doesn't start |
| `theLoginClientCannotBeTheServiceClient` | Service client id = `dwell-client` | `IllegalStateException`: no running admin calls through the login client |

## `SecurityConfigTest` (7): permission rules

**Setup:** `@WebFluxTest` with `RoleController` and `UserController`, the real `SecurityConfig` and common-security's auto-configuration (gateway secret off for this test). The services are mocks that return empty results. The token is faked with `mockJwt()`: realm role `ADMIN` (which must not matter) plus the given `dwell-api` permissions, mapped by the real `KeycloakRoleConverter`. Paths are without the `/identity` base path.

| Test | How it works | Checks |
|---|---|---|
| `noTokenIs401` | `GET /api/roles`, no token | 401 |
| `realmRoleAloneIsNotEnough` | Realm role ADMIN, no permissions | 403 |
| `roleReadListsRolesAndAUsersRolesButChangesNothing` | `role:read`: list roles, a user's roles, create a role, assign | 200, 200, 403, 403 |
| `roleWriteCreatesAndDeletesRolesButCannotAssignThem` | `role:write`: create, delete, assign | 201, 204, 403 |
| `userRoleAssignAssignsAndRemovesButCannotChangeTheRealmsRoles` | `user-role:assign`: assign, remove; `role:write` on remove; `user-role:assign` on delete role | 200, 204, 403 (the `/users/...` path isn't a role called `users`), 403 |
| `userManageActivatesAndDeactivates` | `user:manage` vs `role:write` + `user-role:assign` on deactivate | 200 vs 403 |
| `roleReadSeesPermissionsAndTheMatrixButCannotTickThem` | `role:read` on the list, matrix and a role's ticks; `PUT` ticks with `role:read`, `role:write`, `user-role:assign` | 200, 200, 200; 403, 200, 403 |

## `RolePermissionServiceImplTest` (7): ticking permissions on roles

**Setup:** `RolePermissionServiceImpl` with a mocked admin service (token `t`), a mocked `KeycloakGenericConnector` and `UserService`. The connector answers the permission client `dwell-api` (id `api`) with four permissions (`category:read`, `product:read`, `product:write`, `role:write`), each with id `id-<name>`; a test's roles and their current ticks are stubbed per test. The caller (for the lockout guard and the audit) is put in the Reactor security context.

| Test | How it works | Checks |
|---|---|---|
| `tickingAddsWhatsMissingAndRemovesTheRest` | Role `backend` has `category:read`, `product:read`; set to `product:read`, `product:write` | Result is the new list; one `POST .../roles/backend/composites`; one `DELETE` with exactly `{id-category:read, category:read}`; audit `ROLE_PERMISSIONS` "by alice: added [product:write], removed [category:read]" |
| `theSameTicksChangeNothing` | Set to the current ticks | No Keycloak write at all |
| `anUnknownPermissionIsRejectedAndNothingChanges` | `product:fly` | 400 naming it; no write |
| `anUnknownRoleIs404` | Keycloak answers 404 for the role | `Role not found: ghost` |
| `youCannotRemoveRoleWriteFromARoleYouHave` | Caller has role `backend`, unticks `role:write` from it; then a caller with `ADMIN` does the same | 409 and nothing sent (the Keycloak calls are only built after the guard); then the delete happens |
| `theMatrixShowsEveryBusinessRoleAndEveryPermission` | Realm roles incl. `offline_access`, `uma_authorization`, `default-roles-dwell` | Columns are the 4 permissions; rows only `ADMIN` and `backend`, each with its ticks |
| `theScopeHolderIsNeverListedOrTickable` | Keycloak also has the holder `permission-scope` | Not in the permission list; ticking it → 400 "Unknown permissions" |

## `KeycloakPermissionSyncTest` (9): creating permissions at startup

**Setup:** a small in-memory Keycloak behind a mocked connector: the `dwell-api` roles, what the holder `permission-scope` bundles, what realm roles `ADMIN`/`CUSTOMER` bundle and `dwell-client`'s scope. Every write (create a role, add to the holder, grant to a role) changes that state and is recorded, so each test checks exactly what the sync did. Initial grants: `ADMIN: [*]`, `CUSTOMER: [category:read, product:read]`.

| Test | How it works | Checks |
|---|---|---|
| `aNewKeycloakGetsEveryPermissionTheHolderAndTheInitialGrants` | Empty `dwell-api`; holder in scope | Holder + all 9 created; holder bundles all 9; `ADMIN` all 9, `CUSTOMER` the 2 reads; no problems |
| `aKeycloakInSyncIsNotWrittenTo` | Everything present | No write at all |
| `aNewPermissionInTheCodeIsCreatedAndOnlyItIsGranted` | `route:manage` missing; `ADMIN` had `user:manage` unticked | Exactly: create `route:manage`, add to holder, grant to `ADMIN`; `user:manage` stays unticked; `CUSTOMER` gets nothing |
| `aPermissionCreatedByHandReachesTokensButIsGrantedToNobody` | Extra `report:export` made in the console | Added to the holder only; nobody granted |
| `theHolderMissingFromTheLoginClientsScopeIsReported` | Scope empty | One problem naming `permission-scope`, `dwell-client` and step 4.4 |
| `anOlderSetupWithEveryPermissionDirectlyInScopeIsFine` | All 9 directly in scope, no holder there | No problem |
| `switchedOffItOnlyReports` | `enabled = false`, `route:manage` missing | No write; the missing permission is reported |
| `aMissingRoleForTheInitialGrantsIsReportedAndTheRestGoesOn` | Realm role `CUSTOMER` doesn't exist | `ADMIN` still gets all 9; one problem naming `CUSTOMER` |
| `keycloakRefusingGivesAClearHint` | Keycloak answers 403 | Error says identity's admin client needs `manage` on `dwell-api` (step 5.6) |

The gateway-secret filter tests moved to common-security: see [GatewayAuthWebFilterTest](common-security.md#gatewayauthwebfiltertest-5-gateway-secret-webflux).

## `SensitiveToStringTest` (3): DTOs never print secrets

Builds request and response objects and checks their Lombok `toString()`, which is what ends up in a log if someone logs the object.

| Test | How it works | Checks |
|---|---|---|
| `loginRequestHidesPassword` | `LoginRequest(bob, Hunter2!)` | Contains `bob`, not the password |
| `registerRequestHidesPasswordAndEmail` | `RegisterRequest` with email and a password credential | No password, no email (also in `Credential.toString()`) |
| `tokensAreHidden` | `RefreshRequest`, `LogoutRequest`, `TokenResponse` with tokens | No token values |
