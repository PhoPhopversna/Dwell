package com.neo.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicsProperties {

  private int defaultPartitions = 3;
  private short defaultReplicas = 1;
  private Map<String, TopicDefinition> topics = new HashMap<>();

  public static class TopicDefinition {
    private String name;
    private boolean dlt = false;
    private String groupId;
    private Integer partitions;
    private Short replicas;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public boolean isDlt() {
      return dlt;
    }

    public void setDlt(boolean dlt) {
      this.dlt = dlt;
    }

    public String getGroupId() {
      return groupId;
    }

    public void setGroupId(String groupId) {
      this.groupId = groupId;
    }

    public Integer getPartitions() {
      return partitions;
    }

    public void setPartitions(Integer partitions) {
      this.partitions = partitions;
    }

    public Short getReplicas() {
      return replicas;
    }

    public void setReplicas(Short replicas) {
      this.replicas = replicas;
    }
  }

  public int getDefaultPartitions() {
    return defaultPartitions;
  }

  public void setDefaultPartitions(int defaultPartitions) {
    this.defaultPartitions = defaultPartitions;
  }

  public short getDefaultReplicas() {
    return defaultReplicas;
  }

  public void setDefaultReplicas(short defaultReplicas) {
    this.defaultReplicas = defaultReplicas;
  }

  public Map<String, TopicDefinition> getTopics() {
    return topics;
  }

  public void setTopics(Map<String, TopicDefinition> topics) {
    this.topics = topics;
  }
}
