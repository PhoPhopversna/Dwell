package com.neo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;

class DwellJsonLogFormatterTest {

  private final DwellJsonLogFormatter formatter =
      new DwellJsonLogFormatter(
          new MockEnvironment()
              .withProperty("spring.application.name", "product-catalog")
              .withProperty("app.environment", "staging"));

  private final Logger logger = new LoggerContext().getLogger("com.neo.Test");

  private LoggingEvent event(Level level, String message, Throwable t, Map<String, String> mdc) {
    LoggingEvent e = new LoggingEvent("fqcn", logger, level, message, t, null);
    e.setTimeStamp(1795429845123L);
    e.setMDCPropertyMap(mdc);
    return e;
  }

  private DocumentContext json(LoggingEvent e) {
    String line = formatter.format(e);
    assertThat(line).endsWith("\n").doesNotContain("\n\n");
    assertThat(line.trim()).doesNotContain("\n"); // one event = one line, even with a stack trace
    return JsonPath.parse(line);
  }

  @Test
  void writesTheAgreedFields() {
    LoggingEvent e =
        event(
            Level.INFO, "Request completed successfully", null, Map.of("correlationId", "abc-123"));
    e.addKeyValuePair(new KeyValuePair("method", "GET"));
    e.addKeyValuePair(new KeyValuePair("path", "/api/products"));
    e.addKeyValuePair(new KeyValuePair("status", 200));
    e.addKeyValuePair(new KeyValuePair("durationMs", 125L));

    DocumentContext doc = json(e);
    assertThat(doc.read("$.timestamp", String.class)).isEqualTo("2026-11-23T10:30:45.123Z");
    assertThat(doc.read("$.level", String.class)).isEqualTo("INFO");
    assertThat(doc.read("$.service", String.class)).isEqualTo("product-catalog");
    assertThat(doc.read("$.environment", String.class)).isEqualTo("staging");
    assertThat(doc.read("$.correlationId", String.class)).isEqualTo("abc-123");
    assertThat(doc.read("$.method", String.class)).isEqualTo("GET");
    assertThat(doc.read("$.path", String.class)).isEqualTo("/api/products");
    assertThat(doc.read("$.status", Integer.class)).isEqualTo(200);
    assertThat(doc.read("$.durationMs", Integer.class)).isEqualTo(125);
    assertThat(doc.read("$.message", String.class)).isEqualTo("Request completed successfully");
  }

  @Test
  void includesExceptionWithStackTraceAndCorrelationId() {
    LoggingEvent e =
        event(
            Level.ERROR,
            "boom",
            new IllegalStateException("db down"),
            Map.of("correlationId", "err-1"));
    DocumentContext doc = json(e);
    assertThat(doc.read("$.correlationId", String.class)).isEqualTo("err-1");
    assertThat(doc.read("$.exception.type", String.class))
        .isEqualTo("java.lang.IllegalStateException");
    assertThat(doc.read("$.exception.message", String.class)).isEqualTo("db down");
    assertThat(doc.read("$.exception.stackTrace", String.class))
        .contains("DwellJsonLogFormatterTest");
  }

  @Test
  void omitsCorrelationIdWhenAbsent() {
    String line = formatter.format(event(Level.INFO, "startup", null, Map.of()));
    assertThat(line).doesNotContain("correlationId");
  }

  @Test
  void masksSecretsInMessagesFieldsAndExceptions() {
    String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU";
    LoggingEvent e =
        event(
            Level.WARN,
            "Create User Request : RegisterRequest(userName=bob, password=Hunter2!) token " + jwt,
            new RuntimeException("Authorization: Bearer " + jwt),
            Map.of());
    e.addKeyValuePair(new KeyValuePair("authorization", "Bearer " + jwt));
    String line = formatter.format(e);
    assertThat(line).doesNotContain("Hunter2").doesNotContain(jwt).contains("userName=bob");
    assertThat(JsonPath.parse(line).read("$.authorization", String.class)).isEqualTo("****");
  }

  @Test
  void structuredFieldsCannotOverrideFixedFields() {
    LoggingEvent e = event(Level.INFO, "x", null, Map.of("correlationId", "real"));
    e.addKeyValuePair(new KeyValuePair("correlationId", "spoofed"));
    e.addKeyValuePair(new KeyValuePair("level", "ERROR"));
    DocumentContext doc = json(e);
    assertThat(doc.read("$.correlationId", String.class)).isEqualTo("real");
    assertThat(doc.read("$.level", String.class)).isEqualTo("INFO");
  }
}
