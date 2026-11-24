package com.neo.config.logging;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.KafkaBackoffException;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

@Component
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

  private static final Logger LOG = LoggerFactory.getLogger("com.neo.logging.kafka");

  private static final ThreadLocal<Long> START = new ThreadLocal<>();

  @Override
  public ConsumerRecord<Object, Object> intercept(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    Header header = record.headers().lastHeader(CorrelationId.HEADER);
    String incoming = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.resolve(incoming));
    START.set(System.nanoTime());
    return record;
  }

  @Override
  public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    LOG.atInfo()
        .addKeyValue("event", "kafka_message_processed")
        .addKeyValue("topic", record.topic())
        .addKeyValue("partition", record.partition())
        .addKeyValue("offset", record.offset())
        .addKeyValue("durationMs", elapsedMs())
        .log("Kafka message processed");
  }

  @Override
  public void failure(
      ConsumerRecord<Object, Object> record,
      Exception exception,
      Consumer<Object, Object> consumer) {
    if (isBackoff(exception)) {
      // Not an attempt: a retry-topic record that arrived before its delay is up
      LOG.debug("Kafka message on {} waits for its retry delay", record.topic());
      return;
    }
    // Stack trace is logged by the container's error handler
    LOG.atWarn()
        .addKeyValue("event", "kafka_message_failed")
        .addKeyValue("topic", record.topic())
        .addKeyValue("partition", record.partition())
        .addKeyValue("offset", record.offset())
        .addKeyValue("durationMs", elapsedMs())
        .log("Kafka message processing failed: {}", rootCause(exception).getMessage());
  }

  @Override
  public void afterRecord(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    MDC.remove(CorrelationId.MDC_KEY);
    START.remove();
  }

  private static boolean isBackoff(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof KafkaBackoffException) {
        return true;
      }
    }
    return false;
  }

  private static Throwable rootCause(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root;
  }

  private static long elapsedMs() {
    Long start = START.get();
    return start == null ? -1 : (System.nanoTime() - start) / 1_000_000;
  }
}
