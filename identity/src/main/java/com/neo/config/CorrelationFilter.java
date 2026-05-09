package com.neo.config;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter implements WebFilter {

  private static final String CORRELATION_ID_KEY = "correlationId";
  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

    String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    final String id = correlationId;

    MDC.put(CORRELATION_ID_KEY, id);

    return chain
        .filter(exchange)
        .doOnEach(
            signal -> {
              String ctxId = signal.getContextView().getOrDefault(CORRELATION_ID_KEY, id);
              MDC.put(CORRELATION_ID_KEY, ctxId);
            })
        .doFinally(signal -> MDC.remove(CORRELATION_ID_KEY))
        .contextWrite(Context.of(CORRELATION_ID_KEY, id));
  }
}
