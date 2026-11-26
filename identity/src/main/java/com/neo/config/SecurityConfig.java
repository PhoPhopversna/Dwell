package com.neo.config;

import static com.neo.common.security.DwellPermissions.ROLE_READ;
import static com.neo.common.security.DwellPermissions.ROLE_WRITE;
import static com.neo.common.security.DwellPermissions.USER_MANAGE;
import static com.neo.common.security.DwellPermissions.USER_ROLE_ASSIGN;

import com.neo.common.security.reactive.ReactiveSecurityErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * identity checks the user's JWT itself instead of trusting the gateway alone (the gateway secret
 * is checked first, by common-security). Token rules (signature, issuer, expiry, client) and role
 * mapping come from common-security. Permission-based (see DwellPermissions). Paths are relative to
 * the /identity base path.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, ReactiveJwtAuthenticationConverterAdapter jwtAuthConverter) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .authorizeExchange(
            exchanges ->
                exchanges
                    // Called before the user has a token
                    .pathMatchers("/api/auth/**", "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // A user's roles: before /api/roles/{roleName}, so "users" isn't a role name
                    .pathMatchers(HttpMethod.GET, "/api/roles/users/**")
                    .hasAuthority(ROLE_READ)
                    .pathMatchers("/api/roles/users/**")
                    .hasAuthority(USER_ROLE_ASSIGN)
                    // The realm's roles
                    .pathMatchers(HttpMethod.GET, "/api/roles", "/api/roles/**")
                    .hasAuthority(ROLE_READ)
                    .pathMatchers("/api/roles", "/api/roles/**")
                    .hasAuthority(ROLE_WRITE)
                    // Activate / deactivate users
                    .pathMatchers("/api/users/**")
                    .hasAuthority(USER_MANAGE)
                    // Ops endpoints (env, refresh, ...) aren't an app feature: realm admins only
                    .pathMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    // Anything not listed above
                    .anyExchange()
                    .denyAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(ReactiveSecurityErrors.authenticationEntryPoint())
                    .accessDeniedHandler(ReactiveSecurityErrors.accessDeniedHandler()))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                    .authenticationEntryPoint(ReactiveSecurityErrors.authenticationEntryPoint())
                    .accessDeniedHandler(ReactiveSecurityErrors.accessDeniedHandler()))
        .build();
  }
}
