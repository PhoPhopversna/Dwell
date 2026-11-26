package com.neo.security;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Route permissions: PUBLIC = no token needed, none = denied, otherwise the user needs one of the
 * route's permissions (from the token's {@code dwell-api} client roles) or gets 401/403.
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
            : RoutePermissions.parse(
                String.valueOf(
                    route.getMetadata().getOrDefault(RoutePermissions.METADATA_KEY, "")));

    if (required.contains(RoutePermissions.PUBLIC)) {
      return chain.filter(exchange);
    }
    String method = exchange.getRequest().getMethod().name();
    if (required.isEmpty()) {
      log.warn(
          "Denied {} {}: route {} has no permissions configured",
          method,
          path,
          route == null ? "?" : route.getId());
      return reject(exchange, HttpStatus.FORBIDDEN, SecurityResponses.ACCESS_DENIED);
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
                return reject(
                    exchange, HttpStatus.UNAUTHORIZED, SecurityResponses.AUTHENTICATION_REQUIRED);
              }
              // Permissions only: realm roles (ROLE_*) can't satisfy a route
              Set<String> userPermissions =
                  auth.get().getAuthorities().stream()
                      .map(GrantedAuthority::getAuthority)
                      .filter(a -> !a.startsWith("ROLE_"))
                      .collect(Collectors.toSet());
              if (required.stream().anyMatch(userPermissions::contains)) {
                return chain.filter(exchange);
              }
              log.warn(
                  "Denied {} {} for user {}: needs one of {}, has {}",
                  method,
                  path,
                  auth.get().getName(),
                  required,
                  userPermissions);
              return reject(exchange, HttpStatus.FORBIDDEN, SecurityResponses.ACCESS_DENIED);
            });
  }

  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
    return SecurityResponses.write(
        exchange, status, message, status == HttpStatus.UNAUTHORIZED ? "Bearer" : null);
  }
}
