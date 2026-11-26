package com.neo.security;

import com.neo.common.security.DwellPermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      // Shared Keycloak role mapping (common-security); the shared decoder also checks issuer +
      // client
      ReactiveJwtAuthenticationConverterAdapter jwtAuthConverter) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .authorizeExchange(
            auth ->
                auth.pathMatchers("/api/routes", "/api/routes/**")
                    .hasAuthority(DwellPermissions.ROUTE_MANAGE)
                    .anyExchange()
                    .permitAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler()))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                    .authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler()))
        .build();
  }

  /** 401 without the decoder detail Spring would put in WWW-Authenticate. */
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

  private ServerAccessDeniedHandler accessDeniedHandler() {
    return (exchange, denied) ->
        Mono.defer(
            () -> {
              log.warn(
                  "Denied {} {}: requires permission {}",
                  exchange.getRequest().getMethod(),
                  exchange.getRequest().getPath(),
                  DwellPermissions.ROUTE_MANAGE);
              return SecurityResponses.write(
                  exchange,
                  HttpStatus.FORBIDDEN,
                  SecurityResponses.ACCESS_DENIED,
                  "Bearer error=\"insufficient_scope\"");
            });
  }
}
