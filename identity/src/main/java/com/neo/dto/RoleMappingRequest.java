package com.neo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class RoleMappingRequest {
  @NotBlank(message = "User ID is required")
  private String userId;

  @NotNull(message = "Roles list is required")
  private List<String> roles;
}
