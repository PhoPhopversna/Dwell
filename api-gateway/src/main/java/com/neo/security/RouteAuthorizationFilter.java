package com.neo.security;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authorizes every proxied request against the roles of the route it matched.
 *
 * <ul>
 *   <li>configured public path, or route roles contain {@code PUBLIC}: allowed without a token
 *   <li>route has no roles: denied (403), so a new route is closed until someone configures it
 *   <li>no valid token: 401
 *   <li>token lacks every listed role: 403
 * </ul>
 *
 * Methods are restricted per role by registering one route per method (each row has its own roles).
 */
@Slf4j
@Component
public class RouteAuthorizationFilter implements GlobalFilter, Ordered {

  private final GatewaySecurityProperties properties;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public RouteAuthorizationFilter(GatewaySecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  public int getOrder() {
    // After correlation id / secret header, before rate limiting (-1).
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (properties.publicPaths().stream().anyMatch(p -> pathMatcher.match(p, path))) {
      return chain.filter(exchange);
    }

    Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    Set<String> required =
        route == null
            ? Set.of()
            : RouteRoles.parse(
                String.valueOf(route.getMetadata().getOrDefault(RouteRoles.METADATA_KEY, "")));

    if (required.contains(RouteRoles.PUBLIC)) {
      return chain.filter(exchange);
    }
    String method = exchange.getRequest().getMethod().name();
    if (required.isEmpty()) {
      log.warn(
          "Denied {} {}: route {} has no roles configured",
          method,
          path,
          route == null ? "?" : route.getId());
      return reject(exchange, HttpStatus.FORBIDDEN, "Access to this route is not configured");
    }

    return exchange
        .getPrincipal()
        .filter(Authentication.class::isInstance)
        .cast(Authentication.class)
        // No token = AnonymousAuthenticationToken, which Spring reports as "authenticated".
        .filter(a -> a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken))
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            auth -> {
              if (auth.isEmpty()) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
              }
              Set<String> userRoles =
                  auth.get().getAuthorities().stream()
                      .map(GrantedAuthority::getAuthority)
                      .filter(a -> a.startsWith("ROLE_"))
                      .map(a -> a.substring(5))
                      .collect(Collectors.toSet());
              if (required.stream().anyMatch(userRoles::contains)) {
                return chain.filter(exchange);
              }
              log.warn(
                  "Denied {} {} for user {}: needs one of {}, has {}",
                  method,
                  path,
                  auth.get().getName(),
                  required,
                  userRoles);
              return reject(
                  exchange,
                  HttpStatus.FORBIDDEN,
                  "Insufficient role: requires one of "
                      + required
                      + " (log in again after a role is assigned)");
            });
  }

  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    if (status == HttpStatus.UNAUTHORIZED) {
      response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
    String body = "{\"success\":false,\"message\":\"" + message + "\",\"data\":null}";
    DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }
}
