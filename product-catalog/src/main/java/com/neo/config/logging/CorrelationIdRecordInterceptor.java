package com.neo.config.logging;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Consumer side of correlation-id propagation, applied by Spring Boot to every
 * {@code @KafkaListener}. Puts the record's {@code X-Correlation-ID} in the MDC before the listener
 * runs (generating one only for records produced without it), logs one structured line per
 * processed record, and clears the MDC after the listener and any error handling have finished, so
 * the consumer thread never carries an id into the next record.
 */
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
    // Stack trace is logged by the container's error handler (still inside this MDC).
    LOG.atWarn()
        .addKeyValue("event", "kafka_message_failed")
        .addKeyValue("topic", record.topic())
        .addKeyValue("partition", record.partition())
        .addKeyValue("offset", record.offset())
        .addKeyValue("durationMs", elapsedMs())
        .log("Kafka message processing failed: {}", exception.getMessage());
  }

  @Override
  public void afterRecord(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    MDC.remove(CorrelationId.MDC_KEY);
    START.remove();
  }

  private static long elapsedMs() {
    Long start = START.get();
    return start == null ? -1 : (System.nanoTime() - start) / 1_000_000;
  }
}
