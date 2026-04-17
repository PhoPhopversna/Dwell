package com.neo.config;

import com.neo.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

  private final RateLimitService rateLimitService;
  private final KeyResolver keyResolver;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    String method = exchange.getRequest().getMethod().name();

    return keyResolver
        .resolve(exchange)
        .flatMap(identifier -> rateLimitService.verifyRatingLimit(path, method, identifier))
        .flatMap(
            allowed -> {
              if (!allowed) {
                log.warn("Rate limit exceeded — path: {}, method: {}", path, method);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                return exchange.getResponse().setComplete();
              }
              return chain.filter(exchange);
            });
  }

  @Override
  public int getOrder() {
    return -1;
  }
}
