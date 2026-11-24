package com.neo.config;

import com.neo.cosumer.ImageProcessingConsumer;
import com.neo.event.ProductImageUploadedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * What happens when a {@code @KafkaListener} throws (Spring Boot applies this bean to every
 * listener container):
 *
 * <ol>
 *   <li>Retried 3 times with backoff (1s, 2s, 4s), so a short MinIO or database outage heals.
 *   <li>Then logged at ERROR and published to {@code <topic>.DLT} with the exception in the record
 *       headers, so nothing is silently dropped and the message can be inspected or replayed.
 *   <li>A failed image upload event also marks the image FAILED, so the UI stops waiting.
 * </ol>
 *
 * Messages that cannot be deserialized (see {@code ErrorHandlingDeserializer} in application.yml)
 * are not retried: they go straight to the DLT with their original bytes.
 */
@Slf4j
@Configuration
public class KafkaErrorHandlingConfig {

  static final int MAX_RETRIES = 3;

  @Bean
  public CommonErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, Object> jsonTemplate,
      ProducerFactory<?, ?> producerFactory,
      ObjectProvider<ImageProcessingConsumer> imageConsumer) {

    // Records that failed deserialization only have their raw bytes: publish those as-is.
    KafkaTemplate<String, byte[]> bytesTemplate =
        new KafkaTemplate<>(
            new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(),
                new StringSerializer(),
                new ByteArraySerializer()));
    Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
    templates.put(byte[].class, bytesTemplate);
    templates.put(Object.class, jsonTemplate);

    // partition -1: let the producer choose, the DLT may have fewer partitions than the topic
    DeadLetterPublishingRecoverer deadLetter =
        new DeadLetterPublishingRecoverer(
            templates, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
    backOff.setInitialInterval(1_000);
    backOff.setMultiplier(2);
    backOff.setMaxInterval(10_000);

    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (record, ex) -> {
              log.error(
                  "Kafka message failed permanently, sending to {}.DLT: topic={} partition={}"
                      + " offset={} key={}",
                  record.topic(),
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  record.key(),
                  ex);
              if (record.value() instanceof ProductImageUploadedEvent event) {
                markImageFailed(imageConsumer, event);
              }
              deadLetter.accept(record, ex);
            },
            backOff);
    // The per-attempt failure is already logged by CorrelationIdRecordInterceptor.
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.DEBUG);
    // Listeners ack manually (MANUAL_IMMEDIATE): commit the dead-lettered record's offset too,
    // or it is delivered (and dead-lettered) again after every restart or rebalance.
    handler.setCommitRecovered(true);
    return handler;
  }

  private static void markImageFailed(
      ObjectProvider<ImageProcessingConsumer> consumer, ProductImageUploadedEvent event) {
    try {
      consumer.getObject().markFailed(event);
    } catch (RuntimeException e) {
      log.error("Could not mark image {} as FAILED", event.productImageId(), e);
    }
  }
}
