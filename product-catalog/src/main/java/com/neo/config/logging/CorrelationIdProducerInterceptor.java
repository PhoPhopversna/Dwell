package com.neo.config.logging;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

/**
 * Adds the current request's correlation id (from the MDC) as the {@code X-Correlation-ID} record
 * header, so the consumer logs under the same id as the HTTP request that produced the event.
 *
 * <p>Registered through {@code spring.kafka.producer.properties.interceptor.classes}. {@code
 * onSend} runs on the thread calling {@code KafkaTemplate.send}, i.e. the request thread, where the
 * MDC is set. An id already present on the record is never overwritten.
 */
public class CorrelationIdProducerInterceptor implements ProducerInterceptor<Object, Object> {

  @Override
  public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
    String id = MDC.get(CorrelationId.MDC_KEY);
    if (id != null && record.headers().lastHeader(CorrelationId.HEADER) == null) {
      record.headers().add(CorrelationId.HEADER, id.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }

  @Override
  public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}

  @Override
  public void close() {}

  @Override
  public void configure(Map<String, ?> configs) {}
}
