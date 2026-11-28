package com.neo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/** The complete set of permissions a role should have (the ticked boxes); [] removes them all. */
@Data
public class RolePermissionsRequest {
  @NotNull private List<@NotBlank String> permissions;
}
