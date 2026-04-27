package com.neo.config;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class CorrelationFilter implements WebFilter {

  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  private static final String CORRELATION_ID_MDC_KEY = "correlationId";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    String finalCorrelationId = correlationId;

    return chain
        .filter(exchange)
        .contextWrite(Context.of(CORRELATION_ID_MDC_KEY, finalCorrelationId))
        .doOnEach(
            signal -> {
              if (!signal.isOnComplete()) {
                MDC.put(CORRELATION_ID_MDC_KEY, finalCorrelationId);
              }
            })
        .doFinally(signal -> MDC.clear());
  }
}
