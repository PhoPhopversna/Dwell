# common-logging tests (57)

`mvn -pl common-logging test`. No external services needed. See the [testing guide](README.md) for the patterns used. Service behaviour: [common-logging](../services/common-logging.md).

## `CorrelationIdTest` (3)

Calls `CorrelationId.resolve(incoming)` directly.

| Test | How it works | Checks |
|---|---|---|
| `keepsValidIncomingId` | Passes `abc-123_x.y:z` | Returned unchanged |
| `generatesWhenMissing` | Passes `null` twice | A 36-char UUID each time, different each time |
| `replacesInvalidIdsIncludingLogInjectionAttempts` | Passes `""`, an id with a newline (`a\nFAKE LOG LINE`), one with JSON quotes, and one 65 chars long | Each is replaced by a new id with no newline or quote, so a client can't forge log lines |

## `DwellJsonLogFormatterTest` (5)

Builds Logback `LoggingEvent`s by hand (fixed timestamp, MDC) and formats them with `DwellJsonLogFormatter` (service `product-catalog`, environment `staging`). Every test also checks that the output is exactly **one line**, even with a stack trace.

| Test | How it works | Checks |
|---|---|---|
| `writesTheAgreedFields` | INFO event with correlation id and `method`/`path`/`status`/`durationMs` fields | JSON has `timestamp` (UTC ms), `level`, `service`, `environment`, `correlationId`, the fields, `message` |
| `includesExceptionWithStackTraceAndCorrelationId` | ERROR event with an `IllegalStateException` | `exception.type`, `.message` and `.stackTrace` are present, with the correlation id |
| `omitsCorrelationIdWhenAbsent` | Event with an empty MDC (e.g. startup) | No `correlationId` key |
| `masksSecretsInMessagesFieldsAndExceptions` | Message with `password=Hunter2!` and a JWT, an exception with `Bearer <jwt>`, a field `authorization` | Neither the password nor the JWT appear anywhere; `authorization` is `****`; `userName=bob` is kept |
| `structuredFieldsCannotOverrideFixedFields` | Adds fields named `correlationId` and `level` with fake values | The real correlation id and level win |

## `SensitiveDataMaskerTest` (5)

Calls `SensitiveDataMasker.mask(text)` / `maskValue(key, value)`.

| Test | How it works | Checks |
|---|---|---|
| `masksJwtsAnywhereInText` | Text containing a JWT | JWT replaced by `****` |
| `masksAuthorizationSchemes` | `Bearer abc.def-ghi`, `Basic dXNlcjpwYXNz` | Credential gone |
| `masksLombokToStringAndJsonSecrets` | `LoginRequest(username=bob, password=Hunter2!)` and JSON with `client_secret`/`refresh_token` | Secrets gone, `username` and `name` kept |
| `masksStructuredFieldsBySensitiveKey` | `maskValue` for `Authorization`, `password` (an int), `path`, `status` | Sensitive keys give `****` whatever the type; others unchanged |
| `leavesOrdinaryTextAlone` | A normal log message with a path | Unchanged (no false positives) |

## `RequestDataMaskerTest` (8)

Uses `RequestDataMasker` with default settings unless the test says otherwise.

| Test | How it works | Checks |
|---|---|---|
| `masksSensitiveJsonFieldsAtAnyDepthAndKeepsTheRest` | Registration JSON with email, a nested credentials array, a nested phone number, `refresh_token`, and a JWT inside a note | Those become `****`; `user_name`, `enabled` and `city` are kept |
| `loginBodyNeverShowsThePassword` | Login JSON (content type with charset) | Exactly `{"username":"dwell_admin","password":"****"}` |
| `cutOffJsonStillGetsMasked` | Invalid, truncated JSON with a token and half a password | Neither value appears; ends with `...(truncated)` |
| `formBodiesAndQueryStringsAreMaskedByParameterName` | Form body with `password`/`client_secret`; query string with `token` and URL-encoded `Email` | Those parameters become `****`, others kept; a `null` query stays `null` |
| `extraFieldsCanBeConfigured` | Masker configured with `national_id`, `dob` | `nationalId` (different case and underscore) and `dob` masked |
| `longBodiesAreCapped` | Masker with a 20-char limit, 100-char body | Exactly 20 chars + `...(truncated)` |
| `onlyTextLikeBodiesAreLogged` | `isLoggableContentType` for JSON, problem+json, form, multipart, png, octet-stream, null | Only JSON and form are true |
| `errorJsonCarriesTheCorrelationId` | `CorrelationId.errorJson` with and without an id | Exact JSON; no `correlationId` key when there is none |

## `CorrelationContextTest` (5)

Uses a single-thread pool and puts ids in the MDC by hand to check they reach other threads and don't stay behind.

| Test | How it works | Checks |
|---|---|---|
| `completableFutureOnCommonPoolGetsCallersId` | `supplyAsync(wrapSupplier(...))` with `req-cf` in the MDC | The task sees `req-cf` |
| `wrappedExecutorCarriesIdAndCleansUpThePooledThread` | Runs on `wrapExecutor(pool)`, then asks the same pool thread for its MDC | The task saw the id; afterwards the thread's MDC is empty |
| `callableAndRunnableAreWrappedToo` | `wrapCallable` and `wrap(Runnable)` | Both see the caller's id |
| `noCallerIdMeansANewIdForTheTask` | Caller's MDC empty | The task gets a new valid id |
| `callerMdcIsUntouched` | `runWith("other", ...)` while the caller has `caller` | Caller still has `caller` afterwards |

## `CorrelationLoggingAutoConfigurationTest` (6)

Starts a small Spring context (`ApplicationContextRunner`) with `CorrelationLoggingAutoConfiguration` plus Spring Boot's real task, scheduling, WebClient, RestTemplate and RestClient auto-configurations, exactly as a service gets them.

| Test | How it works | Checks |
|---|---|---|
| `plainAsyncMethodRunsWithTheRequestsId` | `@EnableAsync` bean with an `@Async` method; caller MDC `req-async-1` | Ran on Boot's executor (`task-*` thread) and saw `req-async-1` |
| `injectedTaskExecutorCarriesIdAndLeavesNothingBehind` | Runs a task on `applicationTaskExecutor`, clears the MDC, runs another | First sees the id; second does **not** get the old id |
| `eachScheduledRunGetsItsOwnId` | `@Scheduled(fixedDelay = 20)` job records its MDC | Two runs have two different valid ids |
| `restTemplateFromBuilderSendsTheHeader` | `RestTemplate` from Boot's builder, with `MockRestServiceServer` expecting the header | Header sent with the MDC id; no header when the MDC is empty |
| `restClientFromBuilderSendsTheHeader_withoutOverridingAnExplicitOne` | `RestClient` from Boot's builder, two calls | Header from the MDC; an explicitly set header is kept |
| `webClientFromBuilderSendsTheHeader_fromReactorContextOrMdc` | `WebClient` with a recording exchange function; three calls: id in the Reactor context, id in the MDC, no id | Headers sent: `req-reactive`, `req-servlet`, none |

## `CorrelationIdWebFilterTest` (15): WebFlux

Runs `CorrelationIdWebFilter` on a `MockServerWebExchange` with a lambda chain that records the header seen downstream, **hops to another thread** like real I/O, logs a line, and sets 201. Logs are read with `CapturedLogs`.

| Test | How it works | Checks |
|---|---|---|
| `generatesIdWhenNoneProvided_andReturnsItInResponse` | Request without the header | Response header is a valid new id; downstream got the same id |
| `preservesExistingValidId_andForwardsExactlyThatIdDownstream` | Header `x-correlation-id: abc-123` (lower case) | Downstream and response both get exactly one `abc-123` |
| `replacesInvalidId` | Header with a newline | Replaced by a valid id |
| `idIsInMdcOnOtherThreads_andEveryLogLineCarriesIt` | The chain logs on a `boundedElastic` thread | That thread's MDC has the id; **every** captured line has it |
| `writesExactlyOneCompletionLineWithRequestFields` | POST, chain returns 201 | One access line: INFO, method, path without query, status 201, duration, correlation id |
| `clearsMdcAfterTheRequest` | After the request, check the MDC and a reused thread | Both empty |
| `unexpectedExceptionIsLoggedOnceAsErrorWithStackTraceAndCorrelationId` | Chain throws `IllegalStateException("db down")` | One line: ERROR, status 500, the exception attached, correlation id |
| `expectedHttpErrorsAreWarnWithoutStackTrace` | Chain throws `ResponseStatusException(404)` | WARN, status 404, no stack trace |
| `neverLogsHeadersOrTokens` | Request with `Authorization: Bearer <jwt>` and a cookie | No log line contains `Bearer` or the cookie |
| `requestBodyAndQueryAreLoggedMaskedWhileTheHandlerStillReadsTheWholeBody` | Login JSON body + `?lang=en&token=abc`; the chain reads the body | Handler got the **original** body; the log has the masked body and query |
| `multipartBodiesAreNotCaptured` | Multipart request | No `requestBody` field |
| `responseBodyIsLoggedMasked_whileTheClientGetsItUnchanged` | Chain writes a JSON response in two chunks with email, access token and refresh token | Client receives the original bytes; the log has those three masked and the rest kept (including `Sók`, UTF-8) |
| `binaryCompressedAndStreamingResponsesAreNotLogged` | Responses with `image/png`, `gzip` encoding, `text/event-stream` | No `responseBody` for any of them |
| `longResponseBodyIsCut_butTheClientGetsAllOfIt` | 10-char limit, 20-char response | Client gets all 20; the log has 10 + `...(truncated)` |
| `responseBodyLoggingCanBeSwitchedOff` | `log-response-body=false` | Client still gets the body; no response fields in the log |

## `CorrelationIdServletFilterTest` (10): Spring MVC

Runs `CorrelationIdServletFilter` with `MockHttpServletRequest`/`Response` and a lambda chain.

| Test | How it works | Checks |
|---|---|---|
| `reusesForwardedId_putsItInMdc_andReturnsIt` | Header `X-Correlation-Id` (different case); the chain records the MDC and logs | Handler saw the id; response header has it; every line has it |
| `generatesOnlyWhenNoIdProvided` | No header | Response has a valid new id |
| `logsOneCompletionLineWithFields_andClearsMdc` | DELETE, chain sets 404 | One WARN line with method, path, 404, id; MDC cleared |
| `unhandledExceptionIsLoggedAsErrorWithCorrelationId_andMdcStillCleared` | Chain throws | Exception reaches the caller; one ERROR line, 500, with the exception; MDC cleared |
| `doesNotLeakIdIntoNextRequestOnSameThread` | Two requests in a row on the same thread | The second sees its own id, not the first |
| `requestBodyIsLoggedMaskedAfterTheControllerReadsIt` | JSON body with `secret`; query with `password` | Controller read the original; the log has `secret` and `password` masked |
| `responseBodyWrittenToTheStreamIsLoggedMasked_andTheClientGetsItUnchanged` | Chain writes JSON in two `getOutputStream()` writes | Client gets the original; the log has `phone` and `token` masked |
| `responseBodyWrittenWithTheWriterIsLogged` | Chain uses `getWriter()` with a non-ASCII dash | Logged text is identical (charset handled) |
| `binaryAndCompressedResponsesAreNotLogged` | `image/png` response; gzip JSON response | No `responseBody` |
| `longResponseBodyIsCut_butTheClientGetsAllOfIt` | 10-char limit | Client gets all; the log is cut and marked |
