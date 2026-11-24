package com.neo.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.logging.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Correlation id survives HTTP request -> Kafka producer -> Kafka consumer. */
class KafkaCorrelationIdTest {

  private final CorrelationIdProducerInterceptor producer = new CorrelationIdProducerInterceptor();
  private final CorrelationIdRecordInterceptor consumer = new CorrelationIdRecordInterceptor();

  @AfterEach
  void clear() {
    MDC.clear();
  }

  private static String header(org.apache.kafka.common.header.Headers headers) {
    var h = headers.lastHeader(CorrelationId.HEADER);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  @Test
  void producerCopiesRequestIdFromMdcIntoRecordHeader() {
    MDC.put(CorrelationId.MDC_KEY, "req-42");
    ProducerRecord<Object, Object> sent = producer.onSend(new ProducerRecord<>("topic", "k", "v"));
    assertThat(header(sent.headers())).isEqualTo("req-42");
  }

  @Test
  void producerNeverOverwritesExistingHeaderAndSkipsWhenNoRequestContext() {
    MDC.put(CorrelationId.MDC_KEY, "req-42");
    ProducerRecord<Object, Object> record = new ProducerRecord<>("topic", "k", "v");
    record.headers().add(CorrelationId.HEADER, "original".getBytes(StandardCharsets.UTF_8));
    assertThat(header(producer.onSend(record).headers())).isEqualTo("original");

    MDC.clear();
    assertThat(header(producer.onSend(new ProducerRecord<>("topic", "k", "v")).headers())).isNull();
  }

  @Test
  void endToEnd_consumerRunsListenerWithTheProducersIdAndClearsItAfterwards() {
    MDC.put(CorrelationId.MDC_KEY, "trace-e2e");
    ProducerRecord<Object, Object> produced =
        producer.onSend(new ProducerRecord<>("topic", "k", "v"));
    MDC.clear(); // consumer runs on a different thread with no request context

    RecordHeaders headers = new RecordHeaders(produced.headers().toArray());
    ConsumerRecord<Object, Object> received =
        new ConsumerRecord<>(
            "topic",
            0,
            7L,
            0L,
            org.apache.kafka.common.record.TimestampType.CREATE_TIME,
            0,
            0,
            "k",
            "v",
            headers,
            java.util.Optional.empty());

    AtomicReference<String> inListener = new AtomicReference<>();
    consumer.intercept(received, null);
    inListener.set(MDC.get(CorrelationId.MDC_KEY)); // what the @KafkaListener sees
    consumer.success(received, null);
    consumer.afterRecord(received, null);

    assertThat(inListener.get()).isEqualTo("trace-e2e");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void consumerGeneratesIdOnlyForRecordsWithoutOne() {
    ConsumerRecord<Object, Object> legacy = new ConsumerRecord<>("topic", 0, 1L, "k", "v");
    consumer.intercept(legacy, null);
    assertThat(CorrelationId.isValid(MDC.get(CorrelationId.MDC_KEY))).isTrue();
    consumer.failure(legacy, new RuntimeException("x"), null);
    consumer.afterRecord(legacy, null);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }
}
