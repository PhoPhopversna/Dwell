package com.neo.config.logging;

import com.neo.logging.RequestLoggingProperties;
import com.neo.logging.servlet.CorrelationIdServletFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Correlation id + request/response logging. Runs before every other filter (including the
 * gateway-secret check), so even rejected requests are logged with the id.
 */
@Configuration
public class LoggingConfig {

  @Bean
  public FilterRegistrationBean<CorrelationIdServletFilter> correlationIdFilter(
      RequestLoggingProperties properties) {
    FilterRegistrationBean<CorrelationIdServletFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdServletFilter(properties));
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
