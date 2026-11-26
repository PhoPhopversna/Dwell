package com.neo.common.security.autoconfigure;

import com.neo.common.security.DwellJwt;
import com.neo.common.security.DwellJwtProperties;
import com.neo.common.security.GatewayAuthProperties;
import com.neo.common.security.reactive.GatewayAuthWebFilter;
import com.neo.common.security.servlet.GatewayAuthServletFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;

/**
 * Gives every Dwell service the same JWT rules (signature via the realm's keys, expiry, issuer,
 * allowed client) and Keycloak role mapping, plus the gateway-only check when {@code
 * app.gateway-auth.enabled=true}. Each service still writes its own {@code SecurityConfig} with its
 * URL rules and plugs these beans in.
 */
@AutoConfiguration(
    before = {
      OAuth2ResourceServerAutoConfiguration.class,
      ReactiveOAuth2ResourceServerAutoConfiguration.class
    })
@EnableConfigurationProperties({
  DwellJwtProperties.class,
  GatewayAuthProperties.class,
  OAuth2ResourceServerProperties.class
})
public class DwellSecurityAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = Type.SERVLET)
  static class Servlet {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties resourceServer, DwellJwtProperties props) {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(resourceServer.getJwt().getJwkSetUri()).build();
      decoder.setJwtValidator(DwellJwt.validator(resourceServer.getJwt().getIssuerUri(), props));
      return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter(DwellJwtProperties props) {
      return DwellJwt.authenticationConverter(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.gateway-auth", name = "enabled", havingValue = "true")
    FilterRegistrationBean<GatewayAuthServletFilter> gatewayAuthFilter(
        GatewayAuthProperties properties) {
      FilterRegistrationBean<GatewayAuthServletFilter> registration =
          new FilterRegistrationBean<>(new GatewayAuthServletFilter(properties));
      registration.addUrlPatterns("/*");
      // After the correlation id filter (so rejections are logged with an id), before Security
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
      return registration;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = Type.REACTIVE)
  static class Reactive {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
    ReactiveJwtDecoder reactiveJwtDecoder(
        OAuth2ResourceServerProperties resourceServer, DwellJwtProperties props) {
      NimbusReactiveJwtDecoder decoder =
          NimbusReactiveJwtDecoder.withJwkSetUri(resourceServer.getJwt().getJwkSetUri()).build();
      decoder.setJwtValidator(DwellJwt.validator(resourceServer.getJwt().getIssuerUri(), props));
      return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveJwtAuthenticationConverterAdapter reactiveJwtAuthenticationConverter(
        DwellJwtProperties props) {
      return new ReactiveJwtAuthenticationConverterAdapter(DwellJwt.authenticationConverter(props));
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.gateway-auth", name = "enabled", havingValue = "true")
    GatewayAuthWebFilter gatewayAuthWebFilter(GatewayAuthProperties properties) {
      return new GatewayAuthWebFilter(properties);
    }
  }
}
