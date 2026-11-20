package com.neo.config;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaTopicConfig {

  private final KafkaTopicsProperties props;

  public KafkaTopicConfig(KafkaTopicsProperties props) {
    this.props = props;
  }

  @Bean
  public KafkaAdmin.NewTopics dynamicTopics() {
    List<NewTopic> newTopics = new ArrayList<>();

    props
        .getTopics()
        .forEach(
            (key, def) -> {
              int partitions =
                  def.getPartitions() != null ? def.getPartitions() : props.getDefaultPartitions();
              short replicas =
                  def.getReplicas() != null ? def.getReplicas() : props.getDefaultReplicas();

              newTopics.add(
                  TopicBuilder.name(def.getName())
                      .partitions(partitions)
                      .replicas(replicas)
                      .build());

              if (def.isDlt()) {
                newTopics.add(
                    TopicBuilder.name(def.getName() + ".DLT")
                        .partitions(1)
                        .replicas(replicas)
                        .build());
              }
            });

    return new KafkaAdmin.NewTopics(newTopics.toArray(new NewTopic[0]));
  }
}
