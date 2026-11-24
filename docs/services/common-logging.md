# common-logging

A shared Maven module (not a service) that every service depends on. Adding the dependency gives a service, with no code of its own:
- one JSON log line per event, with `service`, `environment` and `correlationId`;
- an `X-Correlation-ID` on every request, response, outgoing HTTP call, `@Async`/`@Scheduled` task and Kafka message;
- one **access line** per request with the masked request and response data;
- masking of passwords, tokens, emails and similar secrets everywhere in the logs.

Full details (log format, Loki and Grafana, error rules): [LOGGING.md](../LOGGING.md).

## Using it in a service

1. Add the dependency (`com.neo:common-logging`) and include `com/neo/logging/logback-dwell.xml` from the service's `logback-spring.xml`.
2. Register the correlation filter:
   - WebFlux: `CorrelationIdWebFilter` bean, plus `ReactorMdcPropagation.install()` once at startup.
   - Servlet: `CorrelationIdServletFilter` with `Ordered.HIGHEST_PRECEDENCE`.

   Copy `LoggingConfig` from an existing service.
3. Done. Thread pools, `@Scheduled`, and `WebClient`/`RestTemplate`/`RestClient` built from Spring Boot's builders are set up automatically by `CorrelationLoggingAutoConfiguration`.

To pass the correlation id to work you start yourself (e.g. `CompletableFuture`), wrap it with `CorrelationContext.wrap(...)` / `wrapSupplier(...)` / `wrapExecutor(...)`. To call another service, see [WebclientUsage.md](../WebclientUsage.md).

## Main classes

| Class | What it does |
|---|---|
| `CorrelationId` | Header and MDC names; reuses a valid incoming id or makes a new one (1–64 chars of `[A-Za-z0-9._:-]`, anything else replaced, which also blocks log injection); the standard error JSON |
| `reactive.CorrelationIdWebFilter` | First WebFlux filter: sets the id on the request, the Reactor context and the response; writes the access line; copies up to `max-body-chars` of the request and response bodies as they stream |
| `reactive.ReactorMdcPropagation` | Copies the id from the Reactor context into the MDC on every thread |
| `servlet.CorrelationIdServletFilter` | Same for Spring MVC; always clears the MDC so pooled threads don't leak an id |
| `AccessLogger` | Writes the access line: level by status (2xx/3xx INFO, 4xx WARN, 5xx ERROR) |
| `RequestDataMasker` | Masks sensitive fields in JSON (any depth), forms and query strings; caps body length |
| `SensitiveDataMasker` | Masks JWTs, `Bearer`/`Basic` credentials and `password=`-style pairs in any message |
| `DwellJsonLogFormatter` | The JSON line format (Spring Boot structured logging) |
| `BoundedCapture` | Keeps the first N bytes of a body without buffering it |
| `context.CorrelationContext`, `CorrelationTaskDecorator` | Carry the id to other threads |
| `http.*` | Add the header to outgoing `WebClient`/`RestTemplate`/`RestClient` calls |
| `autoconfigure.CorrelationLoggingAutoConfiguration` | Wires all of the above in automatically; adds `correlationId` to Spring's default error body |

## Configuration (`app.logging.request.*`)

| Property | Default | Purpose |
|---|---|---|
| `log-query` | `true` | Log the query string (masked) |
| `log-body` | `true` | Log text/JSON/form request bodies (masked) |
| `log-response-body` | `true` | Log text/JSON/XML response bodies (masked); binary, compressed and streamed bodies never are |
| `max-body-chars` | `2000` | Longer bodies are cut and marked `...(truncated)` |
| `masked-fields` | – | Extra field names to mask, on top of the built-in list |

Log settings shared by all services: `APP_ENV`, `LOG_DIR`, `LOG_LEVEL_ROOT`, `LOG_LEVEL_APP`, `LOG_LEVEL_ACCESS`, `LOG_MAX_FILE_SIZE`, `LOG_MAX_HISTORY`, `LOG_TOTAL_SIZE_CAP` (see `.env.example`).

## Tests (57)

For how each test works (setup, action and checks), see [testing/common-logging.md](../testing/common-logging.md).

Run with `mvn -pl common-logging test`.

**`CorrelationIdTest`**
- A valid incoming id is kept; a missing one is generated.
- Invalid ids, including log-injection attempts (newlines), are replaced.

**`DwellJsonLogFormatterTest`**
- Writes the agreed fields; includes exceptions with stack trace and correlation id; leaves out the correlation id when there is none.
- Masks secrets in messages, fields and exceptions.
- Structured fields can't override the fixed ones.

**`SensitiveDataMaskerTest`**
- JWTs are masked anywhere in text, along with `Bearer`/`Basic` values and secrets in Lombok `toString` or JSON.
- Ordinary text is left alone.

**`RequestDataMaskerTest`**
- Sensitive JSON fields are masked at any depth while the rest is kept; a login body never shows the password, even when the JSON is cut off.
- Forms and query strings are masked by parameter name; extra field names can be configured.
- Long bodies are capped; only text-like bodies are logged.

**`CorrelationIdWebFilterTest`** (15, WebFlux)
- Creates or reuses the id, forwards exactly that id downstream and returns it; invalid ids are replaced.
- The id is in the MDC on other threads and on every log line, and cleared afterwards.
- Exactly one access line per request: unexpected errors at ERROR with stack trace, expected HTTP errors at WARN without.
- Headers and tokens are never logged.
- Request and response bodies are logged masked while the handler and the client still get the full, unchanged body.
- Multipart, binary, compressed and streamed bodies are not captured; long bodies are cut; response logging can be switched off.

**`CorrelationIdServletFilterTest`** (10, servlet)
- The same rules for Spring MVC, including that an id never leaks into the next request on the same thread.
- Response bodies are captured whether written through the stream or the writer.

**`CorrelationContextTest`**
- `CompletableFuture`, executors, `Callable` and `Runnable` run with the caller's id; the pooled thread is cleaned up.
- No caller id means a new one; the caller's MDC is untouched.

**`CorrelationLoggingAutoConfigurationTest`** (boots Spring Boot's real auto-configuration)
- `@Async` and an injected `TaskExecutor` carry the request's id; each `@Scheduled` run gets its own id.
- `RestTemplate`, `RestClient` and `WebClient` built from Boot's builders send the header, without overriding one set explicitly.
