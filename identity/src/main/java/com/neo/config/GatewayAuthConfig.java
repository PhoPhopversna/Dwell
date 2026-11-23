package com.neo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
@ConditionalOnProperty(prefix = "app.gateway-auth", name = "enabled", havingValue = "true")
public class GatewayAuthConfig {

  @Bean
  public GatewayAuthWebFilter gatewayAuthWebFilter(GatewayAuthProperties properties) {
    return new GatewayAuthWebFilter(properties);
  }
}
