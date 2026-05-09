package com.neo.config;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class CorrelationHeaderConfig implements GlobalFilter, Ordered {

  private static final String CORRELATION_ID_KEY = "correlationId";
  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    final String id = correlationId;

    MDC.put(CORRELATION_ID_KEY, id);

    ServerWebExchange mutatedExchange =
        exchange.mutate().request(r -> r.header(CORRELATION_HEADER, id)).build();

    return chain
        .filter(mutatedExchange)
        .doOnEach(
            signal -> {
              String ctxId = signal.getContextView().getOrDefault(CORRELATION_ID_KEY, id);
              MDC.put(CORRELATION_ID_KEY, ctxId);
            })
        .doFinally(signal -> MDC.remove(CORRELATION_ID_KEY))
        .contextWrite(Context.of(CORRELATION_ID_KEY, id));
  }
}
