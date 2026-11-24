package com.neo.config;

import com.neo.logging.RequestLoggingProperties;
import com.neo.logging.reactive.CorrelationIdWebFilter;
import com.neo.logging.reactive.ReactorMdcPropagation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Correlation id + request/response logging for the gateway.
 *
 * <p>The filter is a WebFilter (not a gateway GlobalFilter) so it covers every request: proxied
 * routes, the gateway's own /api/routes endpoints, and requests rejected by security or with no
 * matching route. It sets X-Correlation-ID on the request before routing, so the proxied request
 * carries exactly this id to the downstream service, and returns it on the response.
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
