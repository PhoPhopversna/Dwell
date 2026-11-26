package com.neo.common.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.common.security.reactive.GatewayAuthWebFilter;
import com.neo.common.security.servlet.GatewayAuthServletFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;

class DwellSecurityAutoConfigurationTest {

  private static final String[] JWT = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://kc/realms/Dwell/certs",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://kc/realms/Dwell"
  };

  private final WebApplicationContextRunner servlet =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DwellSecurityAutoConfiguration.class));
  private final ReactiveWebApplicationContextRunner reactive =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DwellSecurityAutoConfiguration.class));

  @Test
  void servletServiceGetsDecoderConverterAndGatewayFilter() {
    servlet
        .withPropertyValues(JWT)
        .withPropertyValues("app.gateway-auth.enabled=true", "app.gateway-auth.secret=s")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(JwtDecoder.class);
              assertThat(ctx).hasSingleBean(JwtAuthenticationConverter.class);
              assertThat(ctx.getBean(FilterRegistrationBean.class).getFilter())
                  .isInstanceOf(GatewayAuthServletFilter.class);
              assertThat(ctx).doesNotHaveBean(ReactiveJwtDecoder.class);
            });
  }

  @Test
  void reactiveServiceGetsDecoderConverterAndGatewayFilter() {
    reactive
        .withPropertyValues(JWT)
        .withPropertyValues("app.gateway-auth.enabled=true", "app.gateway-auth.secret=s")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(ReactiveJwtDecoder.class);
              assertThat(ctx).hasSingleBean(ReactiveJwtAuthenticationConverterAdapter.class);
              assertThat(ctx).hasSingleBean(GatewayAuthWebFilter.class);
              assertThat(ctx).doesNotHaveBean(JwtDecoder.class);
            });
  }

  @Test
  void gatewayFilterOnlyWhenEnabled() {
    reactive
        .withPropertyValues(JWT)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(GatewayAuthWebFilter.class));
    servlet
        .withPropertyValues(JWT)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(FilterRegistrationBean.class));
  }

  @Test
  void missingIssuerFailsStartup() {
    reactive
        .withPropertyValues(JWT[0])
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("issuer-uri"));
  }
}
