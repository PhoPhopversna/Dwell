package com.neo.logging.servlet;

import static com.neo.logging.CapturedLogs.correlationId;
import static com.neo.logging.CapturedLogs.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.neo.logging.CapturedLogs;
import com.neo.logging.CorrelationId;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdServletFilterTest {

  private final CorrelationIdServletFilter filter = new CorrelationIdServletFilter();
  private final CapturedLogs logs = CapturedLogs.capture();

  @AfterEach
  void tearDown() {
    logs.close();
    MDC.clear();
  }

  @Test
  void reusesForwardedId_putsItInMdc_andReturnsIt() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
    req.addHeader("X-Correlation-Id", "from-gateway-1"); // case-insensitive
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicReference<String> mdcInHandler = new AtomicReference<>();
    FilterChain chain =
        (rq, rs) -> {
          mdcInHandler.set(MDC.get(CorrelationId.MDC_KEY));
          LoggerFactory.getLogger("com.neo.Business").info("handling");
        };

    filter.doFilter(req, res, chain);

    assertThat(mdcInHandler.get()).isEqualTo("from-gateway-1");
    assertThat(res.getHeader(CorrelationId.HEADER)).isEqualTo("from-gateway-1");
    assertThat(logs.events())
        .allSatisfy(e -> assertThat(correlationId(e)).isEqualTo("from-gateway-1"));
  }

  @Test
  void generatesOnlyWhenNoIdProvided() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/x"), res, (rq, rs) -> {});
    assertThat(CorrelationId.isValid(res.getHeader(CorrelationId.HEADER))).isTrue();
  }

  @Test
  void logsOneCompletionLineWithFields_andClearsMdc() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/v1/products/9");
    req.setQueryString("token=abc");
    req.addHeader(CorrelationId.HEADER, "t-9");
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(
        req, res, (rq, rs) -> ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(404));

    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(done.getLevel()).isEqualTo(Level.WARN);
    assertThat(value(done, "method")).isEqualTo("DELETE");
    assertThat(value(done, "path")).isEqualTo("/api/v1/products/9");
    assertThat(value(done, "status")).isEqualTo(404);
    assertThat(correlationId(done)).isEqualTo("t-9");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void unhandledExceptionIsLoggedAsErrorWithCorrelationId_andMdcStillCleared() {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/boom");
    req.addHeader(CorrelationId.HEADER, "t-err");
    FilterChain failing =
        (rq, rs) -> {
          throw new IllegalStateException("kaboom");
        };

    assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), failing))
        .hasMessage("kaboom");

    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(done.getLevel()).isEqualTo(Level.ERROR);
    assertThat(value(done, "status")).isEqualTo(500);
    assertThat(done.getThrowableProxy().getMessage()).isEqualTo("kaboom");
    assertThat(correlationId(done)).isEqualTo("t-err");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void doesNotLeakIdIntoNextRequestOnSameThread() throws Exception {
    MockHttpServletRequest first = new MockHttpServletRequest("GET", "/a");
    first.addHeader(CorrelationId.HEADER, "first");
    filter.doFilter(first, new MockHttpServletResponse(), (rq, rs) -> {});

    AtomicReference<String> seen = new AtomicReference<>();
    MockHttpServletRequest second = new MockHttpServletRequest("GET", "/b");
    second.addHeader(CorrelationId.HEADER, "second");
    filter.doFilter(
        second,
        new MockHttpServletResponse(),
        (rq, rs) -> seen.set(MDC.get(CorrelationId.MDC_KEY)));
    assertThat(seen.get()).isEqualTo("second");
  }

  @Test
  void requestBodyIsLoggedMaskedAfterTheControllerReadsIt() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/categories");
    req.setContentType("application/json");
    req.setQueryString("debug=true&password=x");
    req.setContent(
        "{\"name\":\"Laptops\",\"secret\":\"s3\"}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    AtomicReference<String> controllerSaw = new AtomicReference<>();
    filter.doFilter(
        req,
        new MockHttpServletResponse(),
        (rq, rs) ->
            controllerSaw.set(
                new String(
                    rq.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(controllerSaw.get()).isEqualTo("{\"name\":\"Laptops\",\"secret\":\"s3\"}");
    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(value(done, "requestBody")).isEqualTo("{\"name\":\"Laptops\",\"secret\":\"****\"}");
    assertThat(value(done, "query")).isEqualTo("debug=true&password=****");
  }

  // ---------- response body ----------

  @Test
  void responseBodyWrittenToTheStreamIsLoggedMasked_andTheClientGetsItUnchanged() throws Exception {
    String json =
        "{\"success\":true,\"data\":{\"name\":\"Laptops\",\"phone\":\"012345\","
            + "\"token\":\"abc\"}}";
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/categories");
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(
        req,
        res,
        (rq, rs) -> {
          rs.setContentType("application/json");
          byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          rs.getOutputStream().write(bytes, 0, 10);
          rs.getOutputStream().write(bytes, 10, bytes.length - 10);
        });

    assertThat(res.getContentAsString()).isEqualTo(json);
    ILoggingEvent done = logs.singleAccessCompleted();
    assertThat(value(done, "responseContentType")).isEqualTo("application/json");
    assertThat(value(done, "responseBody"))
        .isEqualTo(
            "{\"success\":true,\"data\":{\"name\":\"Laptops\",\"phone\":\"****\","
                + "\"token\":\"****\"}}");
  }

  @Test
  void responseBodyWrittenWithTheWriterIsLogged() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("DELETE", "/api/categories/1"),
        res,
        (rq, rs) -> {
          rs.setContentType("text/plain;charset=UTF-8");
          rs.getWriter().print("Delete Successfully – ok");
          rs.getWriter().flush();
        });

    assertThat(res.getContentAsString()).isEqualTo("Delete Successfully – ok");
    assertThat(value(logs.singleAccessCompleted(), "responseBody"))
        .isEqualTo("Delete Successfully – ok");
  }

  @Test
  void binaryAndCompressedResponsesAreNotLogged() throws Exception {
    filter.doFilter(
        new MockHttpServletRequest("GET", "/img"),
        new MockHttpServletResponse(),
        (rq, rs) -> {
          rs.setContentType("image/png");
          rs.getOutputStream().write(new byte[] {1, 2, 3});
        });
    assertThat(value(logs.singleAccessCompleted(), "responseBody")).isNull();
    logs.events().clear();

    filter.doFilter(
        new MockHttpServletRequest("GET", "/zip"),
        new MockHttpServletResponse(),
        (rq, rs) -> {
          rs.setContentType("application/json");
          ((jakarta.servlet.http.HttpServletResponse) rs).setHeader("Content-Encoding", "gzip");
          rs.getOutputStream().write(new byte[] {31, -117, 8});
        });
    assertThat(value(logs.singleAccessCompleted(), "responseBody")).isNull();
  }

  @Test
  void longResponseBodyIsCut_butTheClientGetsAllOfIt() throws Exception {
    CorrelationIdServletFilter small =
        new CorrelationIdServletFilter(
            new com.neo.logging.RequestLoggingProperties(null, null, null, 10, null));
    MockHttpServletResponse res = new MockHttpServletResponse();
    small.doFilter(
        new MockHttpServletRequest("GET", "/long"),
        res,
        (rq, rs) -> {
          rs.setContentType("text/plain");
          rs.getOutputStream().write("0123456789abcdefghij".getBytes());
        });

    assertThat(res.getContentAsString()).isEqualTo("0123456789abcdefghij");
    assertThat(value(logs.singleAccessCompleted(), "responseBody"))
        .isEqualTo("0123456789...(truncated)");
  }
}
