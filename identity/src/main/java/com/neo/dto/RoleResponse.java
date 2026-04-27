package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RoleResponse {
  private String id;
  private String name;
  private String description;
  private Boolean composite;

  @JsonProperty("clientRole")
  private Boolean clientRole;

  @JsonProperty("containerId")
  private String containerId;
}
