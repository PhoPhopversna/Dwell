package com.neo.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationHeaderConfig implements GlobalFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");

    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    exchange.getResponse().getHeaders().add("X-Correlation-Id", correlationId);

    return chain.filter(exchange);
  }
}
