package com.neo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
@ConditionalOnProperty(prefix = "app.gateway-auth", name = "enabled", havingValue = "true")
public class GatewayAuthConfig {

  @Bean
  public FilterRegistrationBean<GatewayAuthFilter> gatewayAuthFilter(
      GatewayAuthProperties properties, ObjectMapper objectMapper) {
    FilterRegistrationBean<GatewayAuthFilter> registration =
        new FilterRegistrationBean<>(new GatewayAuthFilter(properties, objectMapper));
    registration.addUrlPatterns("/*");
    // after the correlation-id filter, so rejected direct calls are logged with an id
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
