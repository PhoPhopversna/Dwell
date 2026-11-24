package com.neo.config;

import com.neo.logging.CorrelationId;
import com.neo.service.RateLimitService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
                var response = exchange.getResponse();
                response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] body =
                    CorrelationId.errorJson(
                            "Too many requests", exchange.getAttribute(CorrelationId.MDC_KEY))
                        .getBytes(StandardCharsets.UTF_8);
                return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
              }
              return chain.filter(exchange);
            });
  }

  @Override
  public int getOrder() {
    return -1;
  }
}
