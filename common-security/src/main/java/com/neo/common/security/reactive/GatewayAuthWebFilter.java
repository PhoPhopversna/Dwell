package com.neo.common.security.reactive;

import com.neo.common.security.GatewayAuthProperties;
import com.neo.common.security.GatewaySecretCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Rejects (403) requests without the gateway secret. Runs before Spring Security. */
public class GatewayAuthWebFilter implements WebFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(GatewayAuthWebFilter.class);

  private final GatewaySecretCheck check;

  public GatewayAuthWebFilter(GatewayAuthProperties properties) {
    this.check = new GatewaySecretCheck(properties);
  }

  @Override
  public int getOrder() {
    // Right after the correlation id filter, before Spring Security.
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (check.isExcluded(path)
        || check.matches(exchange.getRequest().getHeaders().getFirst(check.headerName()))) {
      return chain.filter(exchange);
    }
    log.warn(
        "Rejected direct access to {} {} from {}",
        exchange.getRequest().getMethod(),
        exchange.getRequest().getPath(),
        exchange.getRequest().getRemoteAddress());
    return ReactiveSecurityErrors.write(exchange, HttpStatus.FORBIDDEN, "Access denied", null);
  }
}
