package com.neo.logging.reactive;

import static com.neo.logging.CapturedLogs.correlationId;
import static com.neo.logging.CapturedLogs.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.neo.logging.CapturedLogs;
import com.neo.logging.CorrelationId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class CorrelationIdWebFilterTest {

  private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();
  private final CapturedLogs logs = CapturedLogs.capture();

  @BeforeAll
  static void installPropagation() {
    ReactorMdcPropagation.install();
  }

  @AfterEach
  void tearDown() {
    logs.close();
    MDC.clear();
  }

  private record Run(
      MockServerWebExchange exchange,
      String downstreamHeader,
      String mdcOnOtherThread,
      String threadName) {}

  private Run run(MockServerHttpRequest.BaseBuilder<?> request) {
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    AtomicReference<String> header = new AtomicReference<>();
    AtomicReference<String> mdc = new AtomicReference<>();
    AtomicReference<String> thread = new AtomicReference<>();
    WebFilterChain chain =
        e ->
            Mono.fromRunnable(
                    () -> header.set(e.getRequest().getHeaders().getFirst(CorrelationId.HEADER)))
                // hop to another thread like real I/O does; MDC must follow
                .publishOn(Schedulers.boundedElastic())
                .then(
                    Mono.fromRunnable(
                        () -> {
                          mdc.set(MDC.get(CorrelationId.MDC_KEY));
                          thread.set(Thread.currentThread().getName());
                          LoggerFactory.getLogger("com.neo.Business").info("doing work");
                          e.getResponse().setStatusCode(HttpStatus.CREATED);
                        }))
                .then(e.getResponse().setComplete());
    filter.filter(exchange, chain).block();
    return new Run(exchange, header.get(), mdc.get(), thread.get());
  }

  @Test
  void generatesIdWhenNoneProvided_andReturnsItInResponse() {
    Run r = run(MockServerHttpRequest.get("/api/products"));
    String id = r.exchange().getResponse().getHeaders().getFirst(CorrelationId.HEADER);
    assertThat(CorrelationId.isValid(id)).isTrue();
    assertThat(r.downstreamHeader()).isEqualTo(id);
  }

  @Test
  void preservesExistingValidId_andForwardsExactlyThatIdDownstream() {
    Run r = run(MockServerHttpRequest.get("/api/products").header("x-correlation-id", "abc-123"));
    assertThat(r.downstreamHeader()).isEqualTo("abc-123");
    assertThat(r.exchange().getResponse().getHeaders().get(CorrelationId.HEADER))
        .containsExactly("abc-123");
  }

  @Test
  void replacesInvalidId() {
    Run r = run(MockServerHttpRequest.get("/x").header(CorrelationId.HEADER, "bad\nid"));
    assertThat(r.downstreamHeader()).isNotEqualTo("bad\nid");
    assertThat(CorrelationId.isValid(r.downstreamHeader())).isTrue();
  }

  @Test
  void idIsInMdcOnOtherThreads_andEveryLogLineCarriesIt() {
    Run r = run(MockServerHttpRequest.get("/api/products").header(CorrelationId.HEADER, "trace-1"));
    assertThat(r.threadName()).contains("boundedElastic");
    assertThat(r.mdcOnOtherThread()).isEqualTo("trace-1");
    assertThat(logs.events())
        .isNotEmpty()
        .allSatisfy(e -> assertThat(correlationId(e)).isEqualTo("trace-1"));
  }

  @Test
  void writesExactlyOneCompletionLineWithRequestFields() {
    run(
        MockServerHttpRequest.post("/api/products?email=secret@example.com")
            .header(CorrelationId.HEADER, "t-2"));
    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(done.getLevel()).isEqualTo(Level.INFO);
    assertThat(value(done, "method")).isEqualTo("POST");
    assertThat(value(done, "path")).isEqualTo("/api/products"); // query string never logged
    assertThat(value(done, "status")).isEqualTo(201);
    assertThat((Long) value(done, "durationMs")).isGreaterThanOrEqualTo(0L);
    assertThat(correlationId(done)).isEqualTo("t-2");
  }

  @Test
  void clearsMdcAfterTheRequest() {
    run(MockServerHttpRequest.get("/x").header(CorrelationId.HEADER, "t-3"));
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    // a later request on a reused thread starts clean
    Mono.fromCallable(() -> String.valueOf(MDC.get(CorrelationId.MDC_KEY)))
        .subscribeOn(Schedulers.boundedElastic())
        .as(m -> assertThat(m.block()).isEqualTo("null"));
  }

  @Test
  void unexpectedExceptionIsLoggedOnceAsErrorWithStackTraceAndCorrelationId() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/boom").header(CorrelationId.HEADER, "t-4"));
    WebFilterChain failing = e -> Mono.error(new IllegalStateException("db down"));
    assertThatThrownBy(() -> filter.filter(exchange, failing).block()).hasMessage("db down");

    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(done.getLevel()).isEqualTo(Level.ERROR);
    assertThat(value(done, "status")).isEqualTo(500);
    assertThat(done.getThrowableProxy().getMessage()).isEqualTo("db down");
    assertThat(correlationId(done)).isEqualTo("t-4");
  }

  @Test
  void expectedHttpErrorsAreWarnWithoutStackTrace() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/nope"));
    WebFilterChain notFound = e -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> filter.filter(exchange, notFound).block());

    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(done.getLevel()).isEqualTo(Level.WARN);
    assertThat(value(done, "status")).isEqualTo(404);
    assertThat(done.getThrowableProxy()).isNull();
  }

  @Test
  void neverLogsHeadersOrTokens() {
    run(
        MockServerHttpRequest.get("/api/products")
            .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl")
            .header("Cookie", "SESSION=abc"));
    List<String> rendered =
        logs.events().stream()
            .map(e -> e.getFormattedMessage() + " " + e.getKeyValuePairs())
            .toList();
    assertThat(rendered).noneMatch(s -> s.contains("Bearer") || s.contains("SESSION=abc"));
  }

  @Test
  void requestBodyAndQueryAreLoggedMaskedWhileTheHandlerStillReadsTheWholeBody() {
    String json = "{\"username\":\"dwell_admin\",\"password\":\"Admin@12345\"}";
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/identity/api/auth/login?lang=en&token=abc")
                .header(CorrelationId.HEADER, "body-1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(json));
    AtomicReference<String> handlerSaw = new AtomicReference<>();
    WebFilterChain chain =
        e ->
            org.springframework.core.io.buffer.DataBufferUtils.join(e.getRequest().getBody())
                .doOnNext(b -> handlerSaw.set(b.toString(java.nio.charset.StandardCharsets.UTF_8)))
                .then();
    filter.filter(exchange, chain).block();

    assertThat(handlerSaw.get()).isEqualTo(json); // body unchanged for the real consumer
    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(value(done, "requestBody"))
        .isEqualTo("{\"username\":\"dwell_admin\",\"password\":\"****\"}");
    assertThat(value(done, "query")).isEqualTo("lang=en&token=****");
    assertThat(value(done, "contentType")).isEqualTo("application/json");
  }

  @Test
  void multipartBodiesAreNotCaptured() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/products")
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body("--x\r\nbinary image bytes\r\n--x--"));
    filter
        .filter(
            exchange,
            e ->
                org.springframework.core.io.buffer.DataBufferUtils.join(e.getRequest().getBody())
                    .then())
        .block();
    assertThat(value(logs.singleAccessCompleted(), "requestBody")).isNull();
  }

  private static MockServerWebExchange respond(
      CorrelationIdWebFilter f, String contentType, String contentEncoding, String body) {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/me").header(CorrelationId.HEADER, "resp-1"));
    WebFilterChain chain =
        e -> {
          e.getResponse().setStatusCode(HttpStatus.OK);
          if (contentType != null) {
            e.getResponse().getHeaders().set("Content-Type", contentType);
          }
          if (contentEncoding != null) {
            e.getResponse().getHeaders().set("Content-Encoding", contentEncoding);
          }
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          // two chunks, like a real (or proxied) response
          int half = bytes.length / 2;
          var factory = e.getResponse().bufferFactory();
          return e.getResponse()
              .writeWith(
                  reactor.core.publisher.Flux.just(
                      factory.wrap(java.util.Arrays.copyOfRange(bytes, 0, half)),
                      factory.wrap(java.util.Arrays.copyOfRange(bytes, half, bytes.length))));
        };
    f.filter(exchange, chain).block();
    return exchange;
  }

  @Test
  void responseBodyIsLoggedMasked_whileTheClientGetsItUnchanged() {
    String json =
        "{\"success\":true,\"data\":{\"username\":\"dwell_admin\",\"email\":\"a@b.com\","
            + "\"accessToken\":\"eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl\","
            + "\"refresh_token\":\"r-123\",\"name\":\"Sók\"},\"correlationId\":\"resp-1\"}";
    MockServerWebExchange exchange = respond(filter, "application/json", null, json);

    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(json);
    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(value(done, "responseContentType")).isEqualTo("application/json");
    assertThat(value(done, "responseBody"))
        .isEqualTo(
            "{\"success\":true,\"data\":{\"username\":\"dwell_admin\",\"email\":\"****\","
                + "\"accessToken\":\"****\",\"refresh_token\":\"****\",\"name\":\"Sók\"},"
                + "\"correlationId\":\"resp-1\"}");
  }

  @Test
  void binaryCompressedAndStreamingResponsesAreNotLogged() {
    respond(filter, "image/png", null, "PNG-bytes");
    assertThat(value(logs.singleAccessCompleted(), "responseBody")).isNull();
    logs.events().clear();

    respond(filter, "application/json", "gzip", "gzipped");
    assertThat(value(logs.singleAccessCompleted(), "responseBody")).isNull();
    logs.events().clear();

    respond(filter, "text/event-stream", null, "data: tick");
    assertThat(value(logs.singleAccessCompleted(), "responseBody")).isNull();
  }

  @Test
  void longResponseBodyIsCut_butTheClientGetsAllOfIt() {
    CorrelationIdWebFilter small =
        new CorrelationIdWebFilter(
            new com.neo.logging.RequestLoggingProperties(null, null, null, 10, null));
    String text = "0123456789abcdefghij";
    MockServerWebExchange exchange = respond(small, "text/plain", null, text);

    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(text);
    assertThat(value(logs.singleAccessCompleted(), "responseBody"))
        .isEqualTo("0123456789...(truncated)");
  }

  @Test
  void responseBodyLoggingCanBeSwitchedOff() {
    CorrelationIdWebFilter off =
        new CorrelationIdWebFilter(
            new com.neo.logging.RequestLoggingProperties(null, null, false, null, null));
    MockServerWebExchange exchange = respond(off, "application/json", null, "{\"a\":1}");

    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"a\":1}");
    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(value(done, "responseBody")).isNull();
    assertThat(value(done, "responseContentType")).isNull();
  }
}
