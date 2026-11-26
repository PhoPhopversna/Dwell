package com.neo.common.security.reactive;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** JSON 401/403 bodies (same shape as every other error) for WebFlux services. */
public final class ReactiveSecurityErrors {

  private static final Logger log = LoggerFactory.getLogger(ReactiveSecurityErrors.class);

  private ReactiveSecurityErrors() {}

  public static ServerAuthenticationEntryPoint authenticationEntryPoint() {
    return (exchange, ex) -> {
      boolean tokenSent =
          exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null;
      if (tokenSent) {
        log.warn("Invalid JWT rejected: {}", ex.getMessage());
      }
      return write(
          exchange,
          HttpStatus.UNAUTHORIZED,
          tokenSent ? "Authentication failed" : "Authentication required",
          tokenSent ? "Bearer error=\"invalid_token\"" : "Bearer");
    };
  }

  public static ServerAccessDeniedHandler accessDeniedHandler() {
    return (exchange, denied) ->
        exchange
            .getPrincipal()
            .map(p -> p.getName())
            .defaultIfEmpty("?")
            .flatMap(
                user -> {
                  log.warn(
                      "Denied {} {} for user {}",
                      exchange.getRequest().getMethod(),
                      exchange.getRequest().getPath(),
                      user);
                  return write(
                      exchange,
                      HttpStatus.FORBIDDEN,
                      "Access denied",
                      "Bearer error=\"insufficient_scope\"");
                });
  }

  static Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String message, String wwwAuthenticate) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    if (wwwAuthenticate != null) {
      response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);
    }
    byte[] body =
        CorrelationId.errorJson(message, exchange.getAttribute(CorrelationId.MDC_KEY))
            .getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
