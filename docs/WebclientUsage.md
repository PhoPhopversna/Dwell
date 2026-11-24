# Calling another service with WebClient

When one service calls another, the request's correlation id (`X-Correlation-ID`) must go with it. Then the logs of both services share the same id, and one search in Grafana shows the whole request. `common-logging` does this for you through `CorrelationIdExchangeFilterFunction`, as long as the `WebClient` is built in one of the two ways below.

## How the id gets there

```
incoming request
   │
   ▼
CorrelationIdWebFilter ── puts the id in the Reactor context
   │
   ▼
your code: webClient.get()...
   │
   ▼
CorrelationIdExchangeFilterFunction ── reads the id from the context, adds X-Correlation-ID
   │
   ▼
the called service ── its filter reuses the same id
```

- **The id is read when the call runs,** not when the client is built. One `WebClient` bean can serve every request, and each call carries its own request's id.
- **Where it comes from:** the Reactor context first, then the thread's MDC (for servlet code that calls `.block()`).
- **Outside a request** (no id anywhere), no header is sent.
- **A header you set yourself always wins.**

## How to use it

You never call the filter directly. Pick one of these:

### 1. Inject Spring Boot's `WebClient.Builder` (recommended)

common-logging's auto-configuration registers a `WebClientCustomizer` that adds the filter to every client built from Boot's builder:

```java
@Service
@RequiredArgsConstructor
public class InventoryClient {

  private final WebClient.Builder builder; // Boot's builder: the filter is already attached

  private WebClient client() {
    return builder.baseUrl("http://inventory-service").build();
  }

  public Mono<Integer> stock(UUID productId) {
    return client()
        .get()
        .uri("/api/stock/{id}", productId)
        .retrieve()
        .bodyToMono(Integer.class); // X-Correlation-ID is sent automatically
  }
}
```

### 2. Build it yourself with `WebClient.builder()`

The static `WebClient.builder()` **skips** Spring Boot's customizers, so add the filter yourself. identity's Keycloak client does this because it needs its own connection pool and timeouts (`identity/.../config/WebClientConfig.java`):

```java
return WebClient.builder()
    .filter(new CorrelationIdExchangeFilterFunction()) // ← this one line
    .clientConnector(new ReactorClientHttpConnector(httpClient))
    .build();
```

## Rules that keep it working

- **Return the `Mono`/`Flux` up the chain** (`return client.get()...`). The id lives in the Reactor context of the request's pipeline.
- **A separate `.subscribe()` inside your code detaches from that pipeline.** If you must start one, add `.contextCapture()` before it, and log in `doOnNext`, not in the `subscribe` lambda.
- **Servlet services** (product-catalog) can call `.block()`. The filter falls back to the request thread's MDC, so it still works.
- **To send a specific id,** set the header yourself and the filter won't override it:

  ```java
  client.get().uri("/api/x").header(CorrelationId.HEADER, "my-id")
  ```

## Checking it

1. Call a service through the gateway with your own id:

   ```bash
   curl -H 'X-Correlation-ID: test-123' http://localhost:8081/...
   ```

2. Search for it in Grafana → Explore (Loki):

   ```logql
   {service_name=~".+"} | json | correlationId="test-123"
   ```

   Lines from every service involved should appear.

The test `CorrelationLoggingAutoConfigurationTest.webClientFromBuilderSendsTheHeader_fromReactorContextOrMdc` covers the three cases: the id from the Reactor context, the id from the MDC, and no id outside a request. See [testing/common-logging.md](testing/common-logging.md#correlationloggingautoconfigurationtest-6).

## RestTemplate and RestClient

These work the same way through `CorrelationIdClientHttpRequestInterceptor`. Inject Boot's `RestTemplateBuilder` or `RestClient.Builder` and the header is added automatically. If you build them with `new RestTemplate()` or `RestClient.create()`, add the interceptor yourself.
