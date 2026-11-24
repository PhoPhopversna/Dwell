package com.neo.config;

import com.neo.logging.RequestLoggingProperties;
import com.neo.logging.reactive.CorrelationIdWebFilter;
import com.neo.logging.reactive.ReactorMdcPropagation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
