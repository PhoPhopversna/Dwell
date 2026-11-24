package com.neo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class RouteAuthorizationFilterTest {

  private final RouteAuthorizationFilter filter =
      new RouteAuthorizationFilter(
          new GatewaySecurityProperties("ADMIN", List.of("/identity/api/auth/login")));

  private static Route route(String roles) {
    return Route.async()
        .id("r1")
        .uri("http://localhost:8084")
        .predicate(e -> true)
        .metadata(RouteRoles.METADATA_KEY, roles)
        .build();
  }

  private static JwtAuthenticationToken userWithRoles(String... roles) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .claim("sub", "u1")
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    return new JwtAuthenticationToken(jwt, new KeycloakRealmRoleConverter().convert(jwt), "u1");
  }

  private record Result(HttpStatus status, boolean forwarded) {}

  private Result run(HttpMethod method, String path, Route route, Principal principal) {
    ServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
    if (principal != null) {
      exchange = exchange.mutate().principal(Mono.just(principal)).build();
    }
    if (route != null) {
      exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
    }
    AtomicBoolean forwarded = new AtomicBoolean();
    GatewayFilterChain chain =
        e -> {
          forwarded.set(true);
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    return new Result((HttpStatus) exchange.getResponse().getStatusCode(), forwarded.get());
  }

  @Test
  void userWithRequiredRoleIsForwarded() {
    Result r =
        run(HttpMethod.GET, "/api/categories", route("CUSTOMER,ADMIN"), userWithRoles("CUSTOMER"));
    assertThat(r.forwarded()).isTrue();
    assertThat(r.status()).isNull();
  }

  @Test
  void userWithoutRequiredRoleGets403() {
    Result r = run(HttpMethod.POST, "/api/categories", route("ADMIN"), userWithRoles("CUSTOMER"));
    assertThat(r.forwarded()).isFalse();
    assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void missingTokenGets401() {
    Result r = run(HttpMethod.GET, "/api/categories", route("CUSTOMER"), null);
    assertThat(r.forwarded()).isFalse();
    assertThat(r.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void anonymousAuthenticationGets401EvenIfRouteListsAnonymousRole() {
    var anon =
        new AnonymousAuthenticationToken(
            "k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    Result r = run(HttpMethod.GET, "/api/categories", route("ANONYMOUS"), anon);
    assertThat(r.forwarded()).isFalse();
    assertThat(r.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void routeWithoutRolesIsDenied() {
    Result r = run(HttpMethod.GET, "/api/categories", route(""), userWithRoles("ADMIN"));
    assertThat(r.forwarded()).isFalse();
    assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void publicRouteNeedsNoToken() {
    Result r = run(HttpMethod.GET, "/api/categories", route("PUBLIC"), null);
    assertThat(r.forwarded()).isTrue();
  }

  @Test
  void configuredPublicPathNeedsNoTokenEvenWithoutRoles() {
    Result r = run(HttpMethod.POST, "/identity/api/auth/login", route(""), null);
    assertThat(r.forwarded()).isTrue();
  }

  @Test
  void deniedResponsesNeverRevealRequiredRolesOrRouteConfig() {
    for (String roles : new String[] {"ADMIN", ""}) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.method(HttpMethod.POST, "/api/categories"));
      ServerWebExchange withUser =
          exchange.mutate().principal(Mono.just(userWithRoles("CUSTOMER"))).build();
      withUser.getAttributes().put(GATEWAY_ROUTE_ATTR, route(roles));
      filter.filter(withUser, e -> Mono.empty()).block();
      String body = exchange.getResponse().getBodyAsString().block();
      assertThat(body).isEqualTo("{\"success\":false,\"message\":\"Access denied\",\"data\":null}");
    }
  }
}
