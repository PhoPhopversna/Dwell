package com.neo.config;

import com.neo.logging.CorrelationId;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

@Slf4j
@Component
public class DownstreamErrorFilter implements GlobalFilter, Ordered {

  static final String UNAVAILABLE = "Service temporarily unavailable";
  static final String BAD_GATEWAY = "Bad gateway";

  @Override
  public int getOrder() {
    // Wraps every other gateway filter, including routing.
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain.filter(exchange).onErrorResume(e -> handle(exchange, e));
  }

  static Mono<Void> handle(ServerWebExchange exchange, Throwable e) {
    HttpStatus status = statusFor(e);
    ServerHttpResponse response = exchange.getResponse();
    if (status == null || response.isCommitted()) {
      return Mono.error(e); // not a connectivity problem, or too late to change the answer
    }
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
    log.error(
        "Downstream call failed ({}) route={} target={}: {}: {}",
        status.value(),
        route != null ? route.getId() : null,
        target != null ? target.getScheme() + "://" + target.getAuthority() : null,
        e.getClass().getSimpleName(),
        e.getMessage());

    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    String message = status == HttpStatus.BAD_GATEWAY ? BAD_GATEWAY : UNAVAILABLE;
    byte[] body =
        CorrelationId.errorJson(message, exchange.getAttribute(CorrelationId.MDC_KEY))
            .getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }

  /** null = not a connectivity failure. */
  static HttpStatus statusFor(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof ConnectException || t instanceof UnknownHostException) {
        return HttpStatus.SERVICE_UNAVAILABLE;
      }
      if (t instanceof PrematureCloseException) {
        return HttpStatus.BAD_GATEWAY;
      }
    }
    return null;
  }
}
