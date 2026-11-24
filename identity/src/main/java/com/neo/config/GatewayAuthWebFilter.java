package com.neo.config;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
public class GatewayAuthWebFilter implements WebFilter, Ordered {

  private final GatewayAuthProperties properties;
  private final byte[] expectedSecret;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public GatewayAuthWebFilter(GatewayAuthProperties properties) {
    if (properties.secret() == null || properties.secret().isBlank()) {
      throw new IllegalStateException(
          "app.gateway-auth.secret must be set when app.gateway-auth.enabled=true");
    }
    this.properties = properties;
    this.expectedSecret = properties.secret().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public int getOrder() {
    // Right after the correlation id filter, before Spring Security.
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (properties.excludedPaths().stream().anyMatch(p -> pathMatcher.match(p, path))) {
      return chain.filter(exchange);
    }

    String provided = exchange.getRequest().getHeaders().getFirst(properties.headerName());
    if (provided != null
        && MessageDigest.isEqual(expectedSecret, provided.getBytes(StandardCharsets.UTF_8))) {
      return chain.filter(exchange);
    }

    log.warn(
        "Rejected direct access to {} {} from {}",
        exchange.getRequest().getMethod(),
        exchange.getRequest().getPath(),
        exchange.getRequest().getRemoteAddress());
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.FORBIDDEN);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body =
        CorrelationId.errorJson("Access denied", exchange.getAttribute(CorrelationId.MDC_KEY))
            .getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
