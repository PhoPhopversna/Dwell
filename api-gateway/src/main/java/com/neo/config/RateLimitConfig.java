package com.neo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
public class RateLimitConfig {

  @Bean
  public KeyResolver ipKeyResolver() {
    return exchange -> Mono.just(resolveIp(exchange));
  }

  private String resolveIp(ServerWebExchange exchange) {
    String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    if (exchange.getRequest().getRemoteAddress() != null) {
      return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
    log.info("Rate limiting —  X-Forwarded-For {}", xff);
    return "unknown";
  }
}
