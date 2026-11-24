package com.neo.service;

import com.neo.logging.CorrelationId;
import com.neo.logging.context.CorrelationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Runs after commit, so a failed publish is logged instead of failing the request. */
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
    // The callback runs on the producer thread: carry the request's id over
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
      log.error("Failed to publish {} to topic {} (key {})", type, topic, key, ex);
    }
  }
}
