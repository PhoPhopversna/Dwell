package com.neo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neo.logging.CorrelationId;
import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class DownstreamErrorFilterTest {

  private final DownstreamErrorFilter filter = new DownstreamErrorFilter();

  private MockServerWebExchange run(Throwable downstreamError) {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/categories"));
    exchange.getAttributes().put(CorrelationId.MDC_KEY, "c-1");
    GatewayFilterChain chain = e -> Mono.error(downstreamError);
    filter.filter(exchange, chain).block();
    return exchange;
  }

  @Test
  void connectionRefusedIs503WithTheStandardBody() {
    MockServerWebExchange exchange =
        run(new RuntimeException("wrapped", new ConnectException("Connection refused")));
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(exchange.getResponse().getBodyAsString().block())
        .isEqualTo(
            "{\"success\":false,\"message\":\"Service temporarily unavailable\",\"data\":null,"
                + "\"correlationId\":\"c-1\"}");
  }

  @Test
  void connectTimeoutIs503() {
    assertThat(run(new ConnectTimeoutException("timed out")).getResponse().getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void otherErrorsPassThroughUnchanged() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
    IllegalStateException boom = new IllegalStateException("bug");
    assertThatThrownBy(() -> filter.filter(exchange, e -> Mono.error(boom)).block()).isSameAs(boom);
  }
}
