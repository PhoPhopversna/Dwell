package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class LogoutRequest {
  @NotBlank(message = "Refresh token is required")
  @JsonProperty("refresh_token")
  @ToString.Exclude
  private String refreshToken;
}
