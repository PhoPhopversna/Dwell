package com.neo.logging.autoconfigure;

import com.neo.logging.CorrelationId;
import com.neo.logging.RequestLoggingProperties;
import com.neo.logging.context.CorrelationTaskDecorator;
import com.neo.logging.http.CorrelationIdClientHttpRequestInterceptor;
import com.neo.logging.http.CorrelationIdExchangeFilterFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * Applies correlation-id propagation to every service that depends on common-logging, without any
 * code in the service:
 *
 * <ul>
 *   <li>{@code @Async} / injected {@code TaskExecutor} ({@code applicationTaskExecutor}): runs with
 *       the submitting request's id.
 *   <li>{@code @Scheduled}: each execution gets its own new id.
 *   <li>{@code WebClient}, {@code RestTemplate}, {@code RestClient} built from Spring Boot's
 *       builders: send {@code X-Correlation-ID} to the called service.
 * </ul>
 *
 * Runs before Boot's own task/client auto-configurations so their builders pick these beans up.
 */
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration",
      "org.springframework.boot.autoconfigure.web.reactive.error.ErrorWebFluxAutoConfiguration"
    },
    before = {
      TaskExecutionAutoConfiguration.class,
      TaskSchedulingAutoConfiguration.class,
      WebClientAutoConfiguration.class,
      RestTemplateAutoConfiguration.class,
      RestClientAutoConfiguration.class
    })
@EnableConfigurationProperties(RequestLoggingProperties.class)
public class CorrelationLoggingAutoConfiguration {

  /** Picked up by Boot's ThreadPoolTaskExecutorBuilder (applicationTaskExecutor, @Async). */
  @Bean
  @ConditionalOnMissingBean(TaskDecorator.class)
  public CorrelationTaskDecorator correlationTaskDecorator() {
    return new CorrelationTaskDecorator();
  }

  /** Boot's scheduler builder has no TaskDecorator hook, so set it through a customizer. */
  @Bean
  public ThreadPoolTaskSchedulerCustomizer correlationTaskSchedulerCustomizer() {
    CorrelationTaskDecorator decorator = new CorrelationTaskDecorator();
    return scheduler -> scheduler.setTaskDecorator(decorator);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
  static class WebClientPropagation {

    @Bean
    public WebClientCustomizer correlationIdWebClientCustomizer() {
      CorrelationIdExchangeFilterFunction filter = new CorrelationIdExchangeFilterFunction();
      return builder -> builder.filter(filter);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
  static class BlockingClientPropagation {

    @Bean
    public RestTemplateCustomizer correlationIdRestTemplateCustomizer() {
      CorrelationIdClientHttpRequestInterceptor interceptor =
          new CorrelationIdClientHttpRequestInterceptor();
      return restTemplate -> restTemplate.getInterceptors().add(interceptor);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
    public RestClientCustomizer correlationIdRestClientCustomizer() {
      CorrelationIdClientHttpRequestInterceptor interceptor =
          new CorrelationIdClientHttpRequestInterceptor();
      return builder -> builder.requestInterceptor(interceptor);
    }
  }

  /** Spring's default error body ({"timestamp","status","error","path"}) + "correlationId". */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.HandlerExceptionResolver")
  static class ServletErrorBody {

    @Bean
    @ConditionalOnMissingBean(org.springframework.boot.web.servlet.error.ErrorAttributes.class)
    public org.springframework.boot.web.servlet.error.DefaultErrorAttributes
        correlationErrorAttributes() {
      return new org.springframework.boot.web.servlet.error.DefaultErrorAttributes() {
        @Override
        public java.util.Map<String, Object> getErrorAttributes(
            org.springframework.web.context.request.WebRequest request,
            org.springframework.boot.web.error.ErrorAttributeOptions options) {
          java.util.Map<String, Object> attributes = super.getErrorAttributes(request, options);
          Object id =
              request.getAttribute(
                  CorrelationId.MDC_KEY,
                  org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
          if (id != null) {
            attributes.put("correlationId", id);
          }
          return attributes;
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveErrorBody {

    @Bean
    @ConditionalOnMissingBean(org.springframework.boot.web.reactive.error.ErrorAttributes.class)
    public org.springframework.boot.web.reactive.error.DefaultErrorAttributes
        correlationErrorAttributes() {
      return new org.springframework.boot.web.reactive.error.DefaultErrorAttributes() {
        @Override
        public java.util.Map<String, Object> getErrorAttributes(
            org.springframework.web.reactive.function.server.ServerRequest request,
            org.springframework.boot.web.error.ErrorAttributeOptions options) {
          java.util.Map<String, Object> attributes = super.getErrorAttributes(request, options);
          request
              .attribute(CorrelationId.MDC_KEY)
              .ifPresent(id -> attributes.put("correlationId", id));
          return attributes;
        }
      };
    }
  }
}
