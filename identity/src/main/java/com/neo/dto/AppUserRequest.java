package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AppUserRequest {
  private String username;
  private String email;
  private Boolean active;

  @JsonProperty("keycloak_id")
  private String keycloakId;

  @JsonProperty("first_name")
  private String firstName;

  @JsonProperty("last_name")
  private String lastName;

  @JsonProperty("created_by")
  private String createdBy;
}
