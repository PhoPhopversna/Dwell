package com.neo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class GatewayAuthWebFilterTest {

  private static final String HEADER = "X-Gateway-Secret";
  private static final String SECRET = "s3cret";

  private final GatewayAuthWebFilter filter =
      new GatewayAuthWebFilter(
          new GatewayAuthProperties(true, HEADER, SECRET, List.of("/actuator/health")));

  private record Result(HttpStatus status, boolean passed) {}

  private Result run(MockServerHttpRequest.BaseBuilder<?> request) {
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    AtomicBoolean passed = new AtomicBoolean();
    WebFilterChain chain =
        e -> {
          passed.set(true);
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    return new Result((HttpStatus) exchange.getResponse().getStatusCode(), passed.get());
  }

  @Test
  void allowsRequestWithGatewaySecret() {
    Result r = run(MockServerHttpRequest.get("/identity/api/roles").header(HEADER, SECRET));
    assertThat(r.passed()).isTrue();
    assertThat(r.status()).isNull();
  }

  @Test
  void rejectsDirectRequestWithoutHeader() {
    Result r = run(MockServerHttpRequest.post("/identity/api/roles/users/u1/assign"));
    assertThat(r.passed()).isFalse();
    assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rejectsWrongSecret() {
    Result r = run(MockServerHttpRequest.get("/identity/api/roles").header(HEADER, "guess"));
    assertThat(r.passed()).isFalse();
    assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void allowsExcludedPath() {
    Result r = run(MockServerHttpRequest.get("/actuator/health"));
    assertThat(r.passed()).isTrue();
  }

  @Test
  void failsFastWithoutSecret() {
    assertThatThrownBy(
            () -> new GatewayAuthWebFilter(new GatewayAuthProperties(true, HEADER, "", List.of())))
        .isInstanceOf(IllegalStateException.class);
  }
}
