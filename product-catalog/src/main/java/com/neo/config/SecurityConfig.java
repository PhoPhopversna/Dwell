package com.neo.config;

import static com.neo.common.security.DwellPermissions.CATEGORY_READ;
import static com.neo.common.security.DwellPermissions.CATEGORY_WRITE;
import static com.neo.common.security.DwellPermissions.PRODUCT_READ;
import static com.neo.common.security.DwellPermissions.PRODUCT_WRITE;

import com.neo.common.security.servlet.ServletSecurityErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * product-catalog checks the user's JWT itself instead of trusting the gateway alone (the gateway
 * secret is checked first, by common-security). Permission-based, with the same permissions as the
 * gateway routes: {@code category:read/write} for categories and their attributes, {@code
 * product:read/write} for products, variants and images.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter jwtAuthConverter) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // API docs, health
                    .requestMatchers(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/health",
                        "/actuator/health/**")
                    .permitAll()
                    // SockJS can't send headers: the token is checked on STOMP CONNECT instead
                    // (WebSocketAuthInterceptor)
                    .requestMatchers("/ws/**")
                    .permitAll()
                    // Error dispatches (e.g. a 404 rendered by /error) keep the original status
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/categories/**")
                    .hasAuthority(CATEGORY_READ)
                    .requestMatchers("/api/categories/**")
                    .hasAuthority(CATEGORY_WRITE)
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/**")
                    .hasAuthority(PRODUCT_READ)
                    .requestMatchers("/api/v1/products/**")
                    .hasAuthority(PRODUCT_WRITE)
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(ServletSecurityErrors.authenticationEntryPoint())
                    .accessDeniedHandler(ServletSecurityErrors.accessDeniedHandler()))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                    .authenticationEntryPoint(ServletSecurityErrors.authenticationEntryPoint())
                    .accessDeniedHandler(ServletSecurityErrors.accessDeniedHandler()))
        .build();
  }
}
