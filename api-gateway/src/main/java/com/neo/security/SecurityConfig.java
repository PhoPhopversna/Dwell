package com.neo.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

/**
 * Validates the Keycloak JWT on every request (an invalid or expired token is rejected with 401).
 * The gateway's own route-admin API needs the admin role. Proxied routes are authorized per route
 * by {@link RouteAuthorizationFilter}, because the matched route is only known after this chain has
 * run.
 */
@Slf4j
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
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler(properties.adminRole())))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
                    .authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler(properties.adminRole())))
        .build();
  }

  /**
   * 401 without detail. Spring's default puts the decoder error (e.g.
   * "java.lang.IllegalStateException: Expected BEGIN_OBJECT ...") in WWW-Authenticate; that detail
   * is only logged here.
   */
  private ServerAuthenticationEntryPoint authenticationEntryPoint() {
    return (exchange, ex) ->
        Mono.defer(
            () -> {
              boolean tokenSent = SecurityResponses.hasBearerToken(exchange);
              if (tokenSent) {
                log.warn("Invalid JWT rejected: {}", ex.getMessage());
              }
              return SecurityResponses.write(
                  exchange,
                  HttpStatus.UNAUTHORIZED,
                  tokenSent
                      ? SecurityResponses.AUTHENTICATION_FAILED
                      : SecurityResponses.AUTHENTICATION_REQUIRED,
                  tokenSent ? "Bearer error=\"invalid_token\"" : "Bearer");
            });
  }

  /** 403 without naming the required role; the role is logged instead. */
  private ServerAccessDeniedHandler accessDeniedHandler(String adminRole) {
    return (exchange, denied) ->
        Mono.defer(
            () -> {
              log.warn(
                  "Denied {} {}: requires realm role {}",
                  exchange.getRequest().getMethod(),
                  exchange.getRequest().getPath(),
                  adminRole);
              return SecurityResponses.write(
                  exchange,
                  HttpStatus.FORBIDDEN,
                  SecurityResponses.ACCESS_DENIED,
                  "Bearer error=\"insufficient_scope\"");
            });
  }

  private ReactiveJwtAuthenticationConverterAdapter jwtAuthConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    converter.setPrincipalClaimName("preferred_username");
    return new ReactiveJwtAuthenticationConverterAdapter(converter);
  }
}
