package com.neo.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Captures root-logger events, freezing each event's MDC at log time. */
public final class CapturedLogs extends AppenderBase<ILoggingEvent> implements AutoCloseable {

  private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
  private final Logger root;

  private CapturedLogs() {
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.setLevel(ch.qos.logback.classic.Level.DEBUG);
    setContext(root.getLoggerContext());
    start();
    root.addAppender(this);
  }

  public static CapturedLogs capture() {
    return new CapturedLogs();
  }

  @Override
  protected void append(ILoggingEvent event) {
    event.prepareForDeferredProcessing();
    events.add(event);
  }

  public List<ILoggingEvent> events() {
    return events;
  }

  public List<ILoggingEvent> fromLogger(String name) {
    return events.stream().filter(e -> e.getLoggerName().equals(name)).toList();
  }

  public ILoggingEvent singleAccessCompleted() {
    List<ILoggingEvent> completed =
        fromLogger(AccessLogger.LOGGER_NAME).stream()
            .filter(e -> "http_request_completed".equals(value(e, "event")))
            .toList();
    if (completed.size() != 1) {
      throw new AssertionError("Expected exactly 1 completion log, got " + completed.size());
    }
    return completed.get(0);
  }

  public static Object value(ILoggingEvent event, String key) {
    if (event.getKeyValuePairs() == null) {
      return null;
    }
    return event.getKeyValuePairs().stream()
        .filter(kv -> kv.key.equals(key))
        .map(kv -> kv.value)
        .findFirst()
        .orElse(null);
  }

  public static String correlationId(ILoggingEvent event) {
    return event.getMDCPropertyMap().get(CorrelationId.MDC_KEY);
  }

  @Override
  public void close() {
    root.detachAppender(this);
    stop();
  }
}
