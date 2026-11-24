The ID gets there like this:

incoming request ──▶ CorrelationIdWebFilter puts ID in Reactor context ──▶ your code
│ webClient.get()...
▼
CorrelationIdExchangeFilterFunction reads it from the context
│ adds X-Correlation-ID
▼
the called service
(its filter reuses the ID)

- The ID is read at subscription time, when the call actually runs, not when the client is built. So one WebClient bean can serve every request, and each call carries its own request's ID.
- Outside a request no header is sent, and a header you set yourself always wins.

How to use it. There are two ways; you don't call the filter directly in either.

1. Recommended: inject Spring Boot's WebClient.Builder. common-logging's auto-configuration registers a WebClientCustomizer that adds the filter to every client built from it:

@Service
@RequiredArgsConstructor
public class InventoryClient {
private final WebClient.Builder builder;   // Boot's builder, filter already attached

private WebClient client() {
return builder.baseUrl("http://inventory-service").build();
}

public Mono<Integer> stock(UUID productId) {
return client().get().uri("/api/stock/{id}", productId)
.retrieve().bodyToMono(Integer.class);   // X-Correlation-ID sent automatically
}
}

2. Building it yourself with WebClient.builder(). The static builder skips Spring Boot's customizers, so add the filter by hand. Identity's Keycloak client does this, because it needs its own connection pool and timeouts:

// identity: config/WebClientConfig.java
return WebClient.builder()
.filter(new CorrelationIdExchangeFilterFunction())   // ← this one line
.clientConnector(new ReactorClientHttpConnector(httpClient))
.build();

Rules that keep it working:
- Return the Mono/Flux up the chain (return client.get()...). The ID lives in the Reactor context of the request's pipeline. A separate .subscribe() started inside your code detaches from that pipeline: add .contextCapture() before it, and log in doOnNext, not in the subscribe lambda.
- Servlet services (product-catalog) can call .block(). The filter falls back to the request thread's MDC, so it still works.
- To send a specific ID, set the header yourself, as in .header(CorrelationId.HEADER, "my-id"). The filter won't override it.

Checking it: call a service through the gateway with -H 'X-Correlation-ID: test-123', then search Loki with {service_name=~".+"} | json | correlationId="test-123". Lines from every service involved should appear. The test CorrelationLoggingAutoConfigurationTest.webClientFromBuilderSendsTheHeader_fromReactorContextOrMdc checks the three cases: ID from the Reactor context, ID from the MDC, and no ID outside a request.

RestTemplate and RestClient work the same way through CorrelationIdClientHttpRequestInterceptor: inject Boot's RestTemplateBuilder or RestClient.Builder and the header is added automatically.