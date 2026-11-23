package com.neo.security;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

/**
 * Validates the Keycloak JWT on every request (an invalid or expired token is rejected with 401).
 * The gateway's own route-admin API needs the admin role. Proxied routes are authorized per route
 * by {@link RouteAuthorizationFilter}, because the matched route is only known after this chain has
 * run.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, GatewaySecurityProperties properties) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .authorizeExchange(
            auth ->
                auth.pathMatchers("/api/routes", "/api/routes/**")
                    .hasRole(properties.adminRole())
                    .anyExchange()
                    .permitAll())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
                    .accessDeniedHandler(adminRequiredHandler(properties.adminRole())))
        .build();
  }

  /** Explains the 403 instead of returning an empty body. */
  private ServerAccessDeniedHandler adminRequiredHandler(String adminRole) {
    return (exchange, denied) -> {
      ServerHttpResponse response = exchange.getResponse();
      response.setStatusCode(HttpStatus.FORBIDDEN);
      response
          .getHeaders()
          .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
      response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      String body =
          "{\"success\":false,\"message\":\"Requires realm role "
              + adminRole
              + " (log in again after the role is assigned)\",\"data\":null}";
      return response.writeWith(
          Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    };
  }

  private ReactiveJwtAuthenticationConverterAdapter jwtAuthConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    converter.setPrincipalClaimName("preferred_username");
    return new ReactiveJwtAuthenticationConverterAdapter(converter);
  }
}
