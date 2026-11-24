package com.neo.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

public class DwellJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final JsonWriter<ILoggingEvent> writer;

  public DwellJsonLogFormatter(Environment environment) {
    String service = environment.getProperty("spring.application.name", "unknown-service");
    String env = environment.getProperty("app.environment", "local");
    this.writer =
        JsonWriter.<ILoggingEvent>of(
                members -> {
                  members.add(
                      "timestamp", e -> TIMESTAMP.format(Instant.ofEpochMilli(e.getTimeStamp())));
                  members.add("level", e -> e.getLevel().toString());
                  members.add("service", service);
                  members.add("environment", env);
                  members
                      .add("correlationId", e -> e.getMDCPropertyMap().get(CorrelationId.MDC_KEY))
                      .whenHasLength();
                  members.add("logger", ILoggingEvent::getLoggerName);
                  members.add("thread", ILoggingEvent::getThreadName);
                  members.add("message", e -> SensitiveDataMasker.mask(e.getFormattedMessage()));
                  members.addMapEntries(DwellJsonLogFormatter::fields);
                  members.add("exception", e -> exception(e.getThrowableProxy())).whenNotNull();
                })
            .withNewLineAtEnd();
  }

  @Override
  public String format(ILoggingEvent event) {
    return writer.writeToString(event);
  }

  private static Map<String, Object> fields(ILoggingEvent event) {
    if (event.getKeyValuePairs() == null || event.getKeyValuePairs().isEmpty()) {
      return Map.of();
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (KeyValuePair pair : event.getKeyValuePairs()) {
      if (pair.key != null && !RESERVED.contains(pair.key)) {
        fields.put(pair.key, SensitiveDataMasker.maskValue(pair.key, pair.value));
      }
    }
    return fields;
  }

  private static final java.util.Set<String> RESERVED =
      java.util.Set.of(
          "timestamp",
          "level",
          "service",
          "environment",
          "correlationId",
          "logger",
          "thread",
          "message",
          "exception");

  private static Map<String, String> exception(IThrowableProxy proxy) {
    if (proxy == null) {
      return null;
    }
    Map<String, String> ex = new LinkedHashMap<>();
    ex.put("type", proxy.getClassName());
    ex.put("message", SensitiveDataMasker.mask(proxy.getMessage()));
    ex.put("stackTrace", SensitiveDataMasker.mask(ThrowableProxyUtil.asString(proxy)));
    return ex;
  }
}
