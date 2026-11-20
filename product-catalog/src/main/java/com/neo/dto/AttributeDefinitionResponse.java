package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neo.entity.AttributeDefinition;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class AttributeDefinitionResponse {
  private UUID id;
  private String key;
  private String label;

  @JsonProperty("data_type")
  private AttributeDefinition.DataType dataType;

  private List<String> options;
  private boolean required;
  private boolean filterable;
}
