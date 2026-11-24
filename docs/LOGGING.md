# Centralized logging, Loki and correlation IDs

Every service (api-gateway, identity, product-catalog) writes **one JSON object per log line**. Each line carries the request's **`correlationId`**. Logs are collected in **Grafana Loki**, so a single request can be followed through every service, including the Kafka consumer.

## Architecture

```
client ──X-Correlation-ID?──▶ api-gateway ──X-Correlation-ID──▶ identity / product-catalog
                               │  (generates id if missing      │  (reuse id, never regenerate)
                               │   or invalid, returns it in    │        │
                               │   the response header)         │        └─ Kafka record header X-Correlation-ID
                               ▼                                ▼                 ▼
                       logs/api-gateway.log     logs/identity-service.log   logs/product-catalog.log
                               └──────────────── Grafana Alloy (tails files) ──────────┘
                                                        │ push (retries while Loki is down)
                                                        ▼
                                                  Grafana Loki ◀── Grafana (dashboards, Explore)
```

- **No new logging framework.** It uses Logback (the Spring Boot default) and Spring Boot 3.4's built-in structured logging, with one custom formatter: `com.neo.logging.DwellJsonLogFormatter`.
- **Shared code** lives in the `common-logging` module, used by all services:

  | Class | Purpose |
  |---|---|
  | `CorrelationId` | Header and MDC names; validates incoming IDs and generates new ones |
  | `DwellJsonLogFormatter` | The JSON line format |
  | `SensitiveDataMasker` | Masks secrets in messages and fields |
  | `AccessLogger` | The single request/response line, with masked request and response data |
  | `reactive.CorrelationIdWebFilter` + `reactive.ReactorMdcPropagation` | WebFlux services (gateway, identity) |
  | `servlet.CorrelationIdServletFilter` | Servlet services (product-catalog) |
  | `logback-dwell.xml` | Async, non-blocking console and file appenders |

- **The apps never call Loki.** They write to stdout and to a rolling file, both through `AsyncAppender` with `neverBlock=true`, so request threads never wait on log I/O. Alloy ships the files to Loki. If Loki is down, the services carry on normally; Alloy keeps its read positions and retries, so no lines are lost. This has been tested: with Loki stopped, requests kept returning 200 in normal time, and every line written during the outage reached Loki after it restarted.

## Log format

```json
{"timestamp":"2026-11-23T10:30:45.123Z","level":"INFO","service":"product-catalog","environment":"local",
 "correlationId":"abc-123-xyz","logger":"com.neo.logging.access","thread":"http-nio-8084-exec-3",
 "message":"Request completed successfully","event":"http_request_completed",
 "method":"GET","path":"/api/v1/products","status":200,"durationMs":125}
```

| Field | Notes |
|---|---|
| `timestamp` | UTC, millisecond precision |
| `level` | `DEBUG` / `INFO` / `WARN` / `ERROR` |
| `service` | `spring.application.name` (`api-gateway`, `identity-service`, `product-catalog`) |
| `environment` | `APP_ENV` (`local`, `dev`, `staging`, `prod`) |
| `correlationId` | Present on every line that belongs to a request or Kafka message |
| `event` | `http_request_completed`, `http_request_received` (DEBUG), `kafka_message_processed`, `kafka_message_failed` |
| `method`, `path`, `status`, `durationMs` | Access lines only |
| `query`, `contentType`, `requestBody` | Access lines only, masked. See "Request and response data in the access line" |
| `responseContentType`, `responseBody` | Access lines only, masked. Same section |
| `topic`, `partition`, `offset`, `durationMs` | Kafka lines only |
| `exception.type` / `.message` / `.stackTrace` | When an exception is logged. The whole stack trace stays inside one JSON line |

Each request produces **exactly one access line per service**, at a level set by its outcome: 2xx/3xx → INFO, 4xx → WARN, 5xx or unhandled exception → ERROR, with the stack trace. Spring's DEBUG web logging, the gateway error handler's log and Tomcat's "Servlet.service() threw exception" log are switched off, so failures aren't logged twice.

### Request and response data in the access line

The completion line also carries **what the client sent and what we answered**, with sensitive values masked:

```json
{"message":"Request completed with client error","method":"POST","path":"/identity/api/auth/login",
 "status":401,"durationMs":212,"query":"lang=en&token=****","contentType":"application/json",
 "requestBody":"{\"username\":\"dwell_admin\",\"password\":\"****\"}",
 "responseContentType":"application/json",
 "responseBody":"{\"success\":false,\"message\":\"Authentication failed\",\"data\":null,\"correlationId\":\"...\"}",
 "correlationId":"..."}
```

- **`query`:** the query string. Parameters with sensitive names are masked.
- **`requestBody`:** logged only for JSON, text, XML and form bodies. Multipart uploads and binary bodies are **never** logged, and are not even wrapped.
  - Masking works on JSON at any depth, by field name.
  - JWTs and `Bearer` tokens are masked inside any text.
  - The body is capped at `maxBodyChars`, and anything cut off is marked `...(truncated)`.
- **`responseBody`:** logged only for JSON, text and XML responses. Binary responses (images, files), compressed responses (`Content-Encoding: gzip`, …) and streams (`text/event-stream`, NDJSON) are **never** logged. The same masking and the same `maxBodyChars` cap apply, so the tokens in a login response appear as `****`.
  - Error bodies written by the framework's last-resort error handler (for example the gateway's 404 "no route") come after the access line and are not included; the `status` still is. Error bodies from the services' own exception handlers and the gateway's 401/403/429 responses are included.
- **Masked field names** (case-insensitive, with `-` and `_` ignored): password, passwd, pwd, new/old/confirm password, secret, client_secret, token, access_token, refresh_token, id_token, session_state, authorization, cookie, credentials, api_key, x-gateway-secret, otp, pin, cvv, cvc, card_number, iban, email, phone, phone_number, mobile.
- **How the body is captured:**
  - WebFlux (gateway, identity): the body is copied *as it streams* to the handler or the proxied service. Nothing is read ahead, and nothing beyond the cap is held in memory.
  - Servlet (catalog): Spring's `ContentCachingRequestWrapper`, with the same cap.

  The real consumer always receives the full, unchanged body.
- **How the response is captured:** the same way, as it is written: the first `maxBodyChars` are copied while every byte goes straight to the client. Nothing is buffered, so large and streamed responses behave as before.
- **Configuration** (`application.yml`, any service):

  ```yaml
  app:
    logging:
      request:
        log-query: true          # default true
        log-body: true           # default true
        log-response-body: true  # default true
        max-body-chars: 2000     # default 2000
        masked-fields: [national_id, passport_no]   # added to the built-in list
  ```

  The gateway and the downstream service both log the request and response bodies of a proxied request. Set `log-body: false` and `log-response-body: false` on the gateway if you only want them once.

### Correlation ID in every response body

Every response, success or error, carries the ID in the **`X-Correlation-ID` header and in the body**:

```json
{"success":true,"message":null,"data":{...},"correlationId":"3f2a..."}
{"success":false,"message":"Resource not found","data":null,"correlationId":"3f2a..."}
```

This covers:
- the response wrappers `ApiResponse` (gateway, identity), `GenericResponse` (gateway) and `CustomApiResponse` (catalog);
- Spring's default error bodies (404 no route, 400 malformed JSON, …);
- the gateway's 401/403 responses and its 429 rate-limit response (which now has a JSON body);
- the direct-access 403s.

To report a problem, quote the `correlationId`. Searching for it in Loki shows the full trace, with the masked request data.

**Breaking change:** the `/api/categories` endpoints now use the same wrapper as the rest of the API. `GET /api/categories` returns `{"success":true,"data":[...],"correlationId":"..."}` instead of a bare array; `GET/POST/PATCH` return the category under `data`; `DELETE` returns `{"success":true,"data":"Delete Successfully",...}` instead of plain text. Clients must read `response.data`.

### What is never logged

- Request or response headers, multipart or binary request bodies, binary, compressed or streamed response bodies, and unmasked tokens, passwords, secrets, emails, phone numbers or card data.
- `LoginRequest`, `RegisterRequest`, `RefreshRequest`, `LogoutRequest` and `TokenResponse` exclude passwords, tokens and email from `toString()`.
- `SensitiveDataMasker` also masks, in every message, field and stack trace: JWTs, `Bearer`/`Basic` credentials, and `password=` / `"client_secret":` / `token=` style key-value pairs. Fields whose name is sensitive (for example `authorization` or `password`) are always written as `****`.
- The rate limiter no longer logs client IPs.

## Errors and exceptions

Every error reaches the client in the service's normal wrapper (`success:false`, `message`, `correlationId`) and is logged once with its reason: **WARN** for 4xx with the detail, **ERROR** with the stack trace for 5xx. 5xx clients only ever see a general message.

| Situation | Status | Where it is handled |
|---|---|---|
| Malformed JSON, wrong type in a path or query (`/api/categories/abc`) | 400 | each service's `GlobalExceptionHandler` |
| `@Valid` failure | 400; catalog lists the fields (`name: must not be blank`), never the rejected values | same |
| Upload that isn't a non-empty image | 400, before anything is stored | `ImageFileValidator` (catalog) |
| Upload over the size limit | 413 | catalog handler |
| Invalid or duplicate route (`/api/routes`) | 400 (was 500) | `RouteServiceImpl` |
| Route id not found | 404 (was 500) | `RouteServiceImpl` |
| Downstream service down: connection refused or connect timeout | 503 | gateway `DownstreamErrorFilter` |
| Downstream service closed the connection early | 502 | gateway `DownstreamErrorFilter` |
| No response in time (`GATEWAY_RESPONSE_TIMEOUT`, default 30s) | 504 | Spring Cloud Gateway |
| Keycloak unreachable, or a Keycloak 5xx | 503 | identity handler |
| MinIO unavailable during upload | 503 | `ProductImageServiceImpl` |
| Anything unexpected | 500 | catch-all in each handler |

**Kafka** (product-catalog, `KafkaErrorHandlingConfig`):
- A failing listener is retried 3 times (after 1s, 2s and 4s).
- After that the message is logged at ERROR and published to `<topic>.DLT`, with the exception in the headers.
- A message that can't be deserialized goes straight to the DLT with its original bytes (`ErrorHandlingDeserializer`), so it never blocks the partition.
- A dead-lettered image upload, or one that isn't a readable image, marks the image `FAILED` and notifies the frontend.
- Publish failures are logged at ERROR with topic, key and correlation ID. They don't fail the request, because the data is already committed.

## Correlation ID propagation

Header: **`X-Correlation-ID`** (HTTP headers are case-insensitive). MDC key: `correlationId`.

1. **Gateway** (`CorrelationIdWebFilter`, the first `WebFilter`, so it also covers `/api/routes`, requests rejected by security and requests with no matching route):
   - It reuses the incoming ID if it is valid: 1–64 characters from `A-Z a-z 0-9 . _ : -`. Otherwise it generates a UUID. Invalid IDs are replaced, which also blocks log injection.
   - It **sets** the header on the request that is forwarded downstream. It sets rather than adds, so the downstream service always gets exactly one value.
   - It puts the ID in the Reactor context, so the MDC holds it on every thread.
   - It returns the ID in the **response** header, as a single value even when the downstream service also returns it.
2. **identity** (WebFlux): the same filter. It reuses the forwarded ID and only generates one if the header is missing. Its WebClient forwards the ID on calls to Keycloak.
3. **product-catalog** (servlet): `CorrelationIdServletFilter` puts the ID in the MDC for the request thread, returns it, and always clears the MDC in `finally`.
4. **Kafka**:
   - `CorrelationIdProducerInterceptor` copies the MDC ID into the record header. It is set with `spring.kafka.producer.properties.interceptor.classes`.
   - `CorrelationIdRecordInterceptor`, which Spring Boot applies to every `@KafkaListener`, puts the header into the MDC before the listener runs. It logs one `kafka_message_processed` or `kafka_message_failed` line, and clears the MDC after the listener and error handling have finished.

### Background threads and calls to other services

`common-logging` includes a Spring Boot **auto-configuration** (`CorrelationLoggingAutoConfiguration`). Every service that depends on the module gets the following without writing any code:

| You write | Correlation ID |
|---|---|
| `@Async` method (needs `@EnableAsync`), or an injected `TaskExecutor` / `applicationTaskExecutor` | Runs with the **ID of the request that called it** |
| `@Scheduled` method (needs `@EnableScheduling`) | **Each run gets its own new ID**, so all logs of one run can be found together |
| `WebClient` built from the injected `WebClient.Builder` | Sends `X-Correlation-ID`, taken from the Reactor context or the MDC |
| `RestTemplate` built from the injected `RestTemplateBuilder` | Sends `X-Correlation-ID` |
| `RestClient` built from the injected `RestClient.Builder` | Sends `X-Correlation-ID` |

```java
@Service
@RequiredArgsConstructor
class InventoryClient {
  private final RestClient.Builder builder;          // inject Boot's builder...
  private RestClient client() { return builder.baseUrl("http://inventory").build(); }

  int stock(UUID id) {                               // ...and the header is sent automatically
    return client().get().uri("/api/stock/{id}", id).retrieve().body(Integer.class);
  }
}

@Async
public void sendWelcomeEmail(String userId) {
  log.info("Sending welcome email to {}", userId);   // same correlationId as the HTTP request
}
```

Outside a request, nothing is invented for HTTP calls: no header is sent. A header the caller sets explicitly is never overwritten.

**Not covered automatically. Use `com.neo.logging.context.CorrelationContext`:**

| Situation | What to do |
|---|---|
| `CompletableFuture.supplyAsync(...)` / `runAsync(...)` **without** an executor (common pool) | `CompletableFuture.supplyAsync(CorrelationContext.wrapSupplier(() -> work()))` |
| Your own `ExecutorService` or thread pool | `CorrelationContext.wrapExecutor(myExecutor)`, or `wrap(runnable)` / `wrapCallable(callable)` per task |
| `new Thread(...)` | `new Thread(CorrelationContext.wrap(() -> work()))` |
| A client built with the static `WebClient.builder()` / `RestClient.builder()` / `new RestTemplate()` | Inject Boot's builder instead, or add the filter yourself: `.filter(new CorrelationIdExchangeFilterFunction())` / `.requestInterceptor(new CorrelationIdClientHttpRequestInterceptor())`. identity's Keycloak `WebClient` does the former |
| Your own `TaskDecorator` bean | Ours steps back (`@ConditionalOnMissingBean`). Delegate to `CorrelationTaskDecorator` inside yours |

**Reactive code (gateway, identity): log inside operators, not in `subscribe(...)` callbacks.** Reactor puts the correlation ID back into the MDC inside operators such as `doOnNext`, `doOnError`, `map` and `flatMap` on any thread, but **not** inside the lambdas passed to `subscribe(...)` when the value arrives on another thread (for example a database or Netty thread). For a fire-and-forget pipeline, started for example from `@Scheduled`, do the work and the logging in operators, add `.contextCapture()` to carry the caller's ID, and call a bare `subscribe()`. `RateLimitServiceImpl.refreshPatternCache` is an example.

Every wrapper restores the worker thread's previous MDC value afterwards, so pooled threads never leak an ID. Tests: `CorrelationContextTest`, and `CorrelationLoggingAutoConfigurationTest`, which boots Boot's real task and client auto-configurations with real `@Async` / `@Scheduled` beans.

### What was wrong before (root cause)

- **Gateway and identity:** `Hooks.enableAutomaticContextPropagation()` was enabled, but **no `ThreadLocalAccessor` was registered for the MDC key**. Reactor therefore never copied the ID into the MDC on the Netty and bounded-elastic threads that do the work. The gateway set the MDC once, on the starting thread, so most lines showed an empty or stale ID.
  - The gateway's correlation filter was a gateway `GlobalFilter`, so the gateway's own endpoints and rejected requests had no ID.
  - The ID was never returned to the client.
  - The header was *added* rather than set.
- **Identity:**
  - A hand-written `Hooks.onEachOperator` captured the context at the wrong time and *removed* the MDC entry whenever an operator lacked it.
  - The exception handler called `MDC.put` without ever removing it, so IDs leaked into later requests on the same thread.
  - The WebClient used JBoss's `MDC` and sent an empty header when there was no ID.
- **product-catalog** had no correlation handling at all: no MDC, and nothing on Kafka records or in the consumer.

The fix is `ReactorMdcPropagation.install()`, which registers the accessor and turns propagation on once. The new filters replace all the old correlation code.

## Running Loki, Alloy and Grafana

```bash
cd docker/observability
cp .env.example .env            # adjust ports, retention, Grafana password, DWELL_LOG_DIR
docker compose up -d
```

| UI | URL |
|---|---|
| Grafana | http://localhost:3001 (admin / `GRAFANA_ADMIN_PASSWORD`). Dashboard: **Dwell → Dwell - Centralized Logs** |
| Loki API | http://localhost:3100 (`/ready`) |
| Alloy UI | http://localhost:12345 (pipeline graph, targets) |

Start the services with `scripts/dwell.sh`. It writes logs to `<repo>/logs`, the directory Alloy mounts by default; override it with `LOG_DIR` in `.env`.

```bash
scripts/dwell.sh start all                 # build if needed, start identity, product-catalog, api-gateway; wait until up
scripts/dwell.sh stop all                  # stop all (gateway first)
scripts/dwell.sh restart all               # stop + start all
scripts/dwell.sh restart all --build       # compile first (nothing stopped if it fails), then stop, rebuild, start
scripts/dwell.sh restart product-catalog   # any subset: identity, product-catalog, api-gateway
scripts/dwell.sh stop all --force          # also stop this project's services started outside the script (IDE, mvn, java -jar)
scripts/dwell.sh status
scripts/dwell.sh logs product-catalog -f   # readable view of the JSON log (timestamp, level, [correlationId], message)
```

`all` is the default when no service is named. An unknown service or option stops the script with exit code 2. A stop that leaves something running exits with 1.

**macOS:** Docker Desktop needs permission to read `~/Documents`. Without it, Loki and Alloy fail with `open /etc/loki/loki-config.yaml: operation not permitted`. Fix it in **System Settings → Privacy & Security → Files and Folders → Docker → Documents Folder** (or Full Disk Access), then restart Docker Desktop. The alternative is to keep the repo outside `~/Documents`.

### Loki configuration

All of it is set through the environment (`docker/observability/.env`):

| Variable | Default | Meaning |
|---|---|---|
| `LOKI_RETENTION_PERIOD` | `168h` | How long logs are kept. The compactor deletes older ones |
| `LOKI_INGESTION_RATE_MB` / `_BURST_MB` | `8` / `16` | Ingestion limits |
| `LOKI_PUSH_URL` | `http://loki:3100/loki/api/v1/push` | Where Alloy pushes. Point it at a remote or managed Loki |
| `LOKI_QUERY_URL` | `http://loki:3100` | Where Grafana queries |
| `DWELL_LOG_DIR` | `../../logs` | Host directory holding the services' `*.log` files |
| `GRAFANA_PORT` / `LOKI_PORT` / `ALLOY_UI_PORT` | `3001` / `3100` / `12345` | Ports |

**Storage:** local filesystem (TSDB, schema v13) in the `loki-data` volume. The application's own files roll at `LOG_MAX_FILE_SIZE` (50 MB), with `LOG_MAX_HISTORY` days kept (7) and a cap of `LOG_TOTAL_SIZE_CAP` (1 GB).

**Labels:** only `service_name`, `environment` and `level`. High-cardinality values such as `correlationId`, `path` and `status` stay in the JSON and are filtered with `| json`.

### Service environment variables

See [`.env.example`](../.env.example). The logging ones are:

| Variable | Default | Meaning |
|---|---|---|
| `APP_ENV` | `local` | Environment label and field |
| `LOG_DIR` | `logs` | Where `<service>.log` is written |
| `LOG_LEVEL_ROOT` / `LOG_LEVEL_APP` / `LOG_LEVEL_ACCESS` | `INFO` | `LOG_LEVEL_ACCESS=DEBUG` also logs "Request received" |
| `LOG_MAX_FILE_SIZE` / `LOG_MAX_HISTORY` / `LOG_TOTAL_SIZE_CAP` | `50MB` / `7` / `1GB` | File rolling |
| `LOG_CONSOLE_FORMAT` | `com.neo.logging.DwellJsonLogFormatter` | Console format (`logstash` and `ecs` are also JSON). The file format is always Dwell JSON |

## Viewing logs in Grafana

- **Dashboard "Dwell - Centralized Logs":** filter by environment, service, level, correlation ID and free text. It shows log volume by level, p95 request duration per service, and the logs themselves.
- **Explore → Loki:** every log line with a `correlationId` has a link, **"Trace this request across all services"**, that opens all lines for that ID.

### Example LogQL

```logql
# Everything for one request, across all services (gateway → identity/catalog → Kafka consumer)
{service_name=~".+"} | json | correlationId="e2e-product-002"

# All errors, with the exception type
{level="ERROR"} | json | line_format "{{.service}} {{.correlationId}} {{.message}} {{.exception_type}}"

# Errors per service over 5 minutes
sum by (service_name) (count_over_time({level="ERROR"}[5m]))

# Slow requests (> 1 s) in production
{environment="prod"} | json | event="http_request_completed" | durationMs > 1000

# 5xx responses at the gateway
{service_name="api-gateway"} | json | event="http_request_completed" | status >= 500

# p95 latency per service
quantile_over_time(0.95, {service_name=~".+"} | json | event="http_request_completed" | unwrap durationMs [5m]) by (service_name)

# Request rate per route at the gateway
sum by (path) (rate({service_name="api-gateway"} | json | event="http_request_completed" [1m]))

# Kafka consumer failures
{service_name="product-catalog"} | json | event="kafka_message_failed"
```

**From a client:** every gateway response carries `X-Correlation-ID`. Send your own ID to choose it, as in `curl -H 'X-Correlation-ID: my-trace-1' …`, then search for it in Grafana.

## Tests

| Requirement | Test |
|---|---|
| Request/response log for every route, with fields | `CorrelationIdWebFilterTest`, `CorrelationIdServletFilterTest` (`writesExactlyOneCompletionLine…`, `logsOneCompletionLine…`) |
| Gateway generates an ID when none is given; invalid ones are replaced | `CorrelationIdWebFilterTest.generatesIdWhenNoneProvided…`, `replacesInvalidId`, `CorrelationIdTest` |
| An existing ID is preserved and forwarded exactly | `CorrelationIdWebFilterTest.preservesExistingValidId_andForwardsExactlyThatIdDownstream` |
| The ID reaches the MDC on other Reactor threads | `CorrelationIdWebFilterTest.idIsInMdcOnOtherThreads…` |
| Kafka keeps the ID from producer to consumer | `KafkaCorrelationIdTest` |
| The MDC is cleaned up (HTTP, reactive, Kafka) | `clearsMdcAfterTheRequest`, `doesNotLeakIdIntoNextRequestOnSameThread`, `KafkaCorrelationIdTest` |
| Exceptions are logged with the correlation ID and stack trace | `unexpectedExceptionIsLogged…`, `unhandledExceptionIsLogged…`, `DwellJsonLogFormatterTest.includesException…` |
| No sensitive data | `SensitiveDataMaskerTest`, `DwellJsonLogFormatterTest.masksSecrets…`, `CorrelationIdWebFilterTest.neverLogsHeadersOrTokens`, identity `SensitiveToStringTest` |
| JSON format | `DwellJsonLogFormatterTest` |
| Works while Loki is unavailable | By design (the apps never talk to Loki; Alloy retries) and verified live. See above |

The full cross-service flow was also verified against running services and Loki:
- the same ID appeared in the gateway, identity, product-catalog and the Kafka consumer;
- 60 concurrent requests produced 0 mismatched or missing IDs;
- no secrets were found in any log file.
