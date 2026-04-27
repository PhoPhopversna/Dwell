package com.neo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class RoleNamesRequest {

  @NotEmpty(message = "Role names must not be empty")
  private List<@NotBlank(message = "Role name must not be blank") String> roleNames;
}
