package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

@Data
public class TokenResponse {
  @JsonProperty("access_token")
  @ToString.Exclude
  private String accessToken;

  @JsonProperty("refresh_token")
  @ToString.Exclude
  private String refreshToken;

  @JsonProperty("expires_in")
  private Long expiresIn;

  @JsonProperty("refresh_expires_in")
  private Long refreshExpiresIn;

  @JsonProperty("token_type")
  private String tokenType;

  @JsonProperty("not-before-policy")
  private Integer notBeforePolicy;

  @JsonProperty("session_state")
  private String sessionState;

  @JsonProperty("scope")
  private String scope;
}
