package com.neo.config;

import com.neo.logging.RequestLoggingProperties;
import com.neo.logging.reactive.CorrelationIdWebFilter;
import com.neo.logging.reactive.ReactorMdcPropagation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Correlation id + request/response logging. Reuses the X-Correlation-ID forwarded by the gateway
 * (only generates one if missing), keeps it in the MDC across Reactor threads, forwards it on
 * outgoing Keycloak calls (see WebClientConfig) and returns it on the response.
 */
@Configuration
public class LoggingConfig {

  static {
    ReactorMdcPropagation.install();
  }

  @Bean
  public CorrelationIdWebFilter correlationIdWebFilter(RequestLoggingProperties properties) {
    return new CorrelationIdWebFilter(properties);
  }
}
