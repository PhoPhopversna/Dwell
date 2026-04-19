package com.neo.config; // package rizz.smartland.api_gateway.config;
//
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.security.config.Customizer;
// import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
// import org.springframework.security.config.web.server.ServerHttpSecurity;
// import org.springframework.security.web.server.SecurityWebFilterChain;
// import
// org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
//
// @Configuration
// @EnableWebFluxSecurity
// public class GatewaySecurityConfig {
//    @Bean
//    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
//        return http
//                .csrf(ServerHttpSecurity.CsrfSpec::disable)
//                .authorizeExchange(auth -> auth
//                        .pathMatchers("/actuator/**").permitAll()
//                        .pathMatchers("/routes/**").hasRole("ADMIN")
//                        .anyExchange().authenticated()
//                )
//                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
// jwt.jwkSetUri("http://localhost:8080/realms/rizz-realm/protocol/openid-connect/certs")))
//                .build();
//    }
// }
