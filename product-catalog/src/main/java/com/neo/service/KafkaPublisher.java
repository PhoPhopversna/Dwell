package com.neo.service;

import com.neo.logging.CorrelationId;
import com.neo.logging.context.CorrelationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes domain events. Callers run after their database transaction has committed, so a failed
 * publish must not fail the request (the data is already saved and a client retry would duplicate
 * it). Every failure is logged at ERROR with topic, key and the request's correlation id, so the
 * missing event can be found and replayed.
 */
@Slf4j
@Service
public class KafkaPublisher {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  @Value("${app.kafka.image-uploaded.topic}")
  private String imageUploadedTopic;

  @Value("${app.kafka.image-deleted.topic}")
  private String imageDeletedTopic;

  @Value("${app.kafka.alert.topic}")
  private String alertTopic;

  public KafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publishImageUploaded(String key, Object event) {
    send(imageUploadedTopic, key, event);
  }

  public void deleteImage(String key, Object event) {
    send(imageDeletedTopic, key, event);
  }

  public void publishAlert(Object event) {
    send(alertTopic, null, event);
  }

  private void send(String topic, String key, Object event) {
    String type = event == null ? "null" : event.getClass().getSimpleName();
    // The send callback runs on the producer's I/O thread: carry the request's id over.
    String correlationId = CorrelationId.current();
    try {
      kafkaTemplate
          .send(topic, key, event)
          .whenComplete(
              (result, ex) ->
                  CorrelationContext.runWith(
                      correlationId,
                      () -> {
                        if (ex != null) {
                          log.error(
                              "Failed to publish {} to topic {} (key {})", type, topic, key, ex);
                        } else {
                          log.debug(
                              "Published {} to {}-{}@{}",
                              type,
                              topic,
                              result.getRecordMetadata().partition(),
                              result.getRecordMetadata().offset());
                        }
                      }));
    } catch (RuntimeException ex) {
      // Synchronous failure: serialization error, or broker metadata not available in time.
      log.error("Failed to publish {} to topic {} (key {})", type, topic, key, ex);
    }
  }
}
