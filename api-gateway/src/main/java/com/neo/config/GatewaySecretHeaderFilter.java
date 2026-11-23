package com.neo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stamps every forwarded request with the shared secret that downstream services (e.g.
 * product-catalog) require, so they can reject requests that bypass the gateway. Any value sent by
 * the client is overwritten.
 */
@Component
public class GatewaySecretHeaderFilter implements GlobalFilter, Ordered {

  private final String headerName;
  private final String secret;

  public GatewaySecretHeaderFilter(
      @Value("${app.gateway-auth.header-name:X-Gateway-Secret}") String headerName,
      @Value("${app.gateway-auth.secret}") String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("app.gateway-auth.secret must be set");
    }
    this.headerName = headerName;
    this.secret = secret;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerWebExchange mutated =
        exchange.mutate().request(r -> r.headers(h -> h.set(headerName, secret))).build();
    return chain.filter(mutated);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }
}
