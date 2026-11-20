package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class CategoryRequest {
  @JsonProperty("parent_id")
  private UUID parentId;

  private String name;
  private List<AttributeDefinitionRequest> attributeDefinitionRequestList;
}
