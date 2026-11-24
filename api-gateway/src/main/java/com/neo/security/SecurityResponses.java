package com.neo.security;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The only auth error bodies the gateway sends. They are deliberately general: which role a route
 * needs, how routes are configured, and why a token failed to parse are logged with the request's
 * correlation id, never returned to the client.
 */
final class SecurityResponses {

  static final String AUTHENTICATION_REQUIRED = "Authentication required";
  static final String AUTHENTICATION_FAILED = "Authentication failed";
  static final String ACCESS_DENIED = "Access denied";

  private SecurityResponses() {}

  /**
   * @param wwwAuthenticate RFC 6750 challenge without error_description (no parser/decoder detail)
   */
  static Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String message, String wwwAuthenticate) {
    // Deferred: Spring Security builds some of these Monos eagerly without subscribing to them
    // (e.g. the authentication fallback when an authenticated user is denied), so nothing may
    // touch the response until subscription.
    return Mono.defer(() -> doWrite(exchange, status, message, wwwAuthenticate));
  }

  private static Mono<Void> doWrite(
      ServerWebExchange exchange, HttpStatus status, String message, String wwwAuthenticate) {
    ServerHttpResponse response = exchange.getResponse();
    if (response.isCommitted()) {
      return Mono.empty();
    }
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    if (wwwAuthenticate != null) {
      response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);
    }
    String correlationId = exchange.getAttribute(CorrelationId.MDC_KEY);
    byte[] body = CorrelationId.errorJson(message, correlationId).getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }

  static boolean hasBearerToken(ServerWebExchange exchange) {
    String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7);
  }
}
