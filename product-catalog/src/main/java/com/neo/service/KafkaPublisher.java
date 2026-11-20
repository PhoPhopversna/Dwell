package com.neo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaPublisher {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  @Value("${app.kafka.image-uploaded.topic}")
  private String imageUploadedTopic;

  @Value("${app.kafka.alert.topic}")
  private String alertTopic;

  public KafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publishImageUploaded(String key, Object event) {
    kafkaTemplate.send(imageUploadedTopic, key, event);
  }

  public void publishAlert(Object event) {
    kafkaTemplate.send(alertTopic, event);
  }
}
