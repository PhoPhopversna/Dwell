package com.neo.config.logging;

import com.neo.logging.RequestLoggingProperties;
import com.neo.logging.servlet.CorrelationIdServletFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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
