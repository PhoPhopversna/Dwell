package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class RegisterRequest {
  @JsonProperty("user_name")
  private String userName;

  private String email;

  @JsonProperty("first_name")
  private String firstName;

  @JsonProperty("last_name")
  private String lastName;

  private Boolean enabled;

  @JsonProperty("create_by")
  private String createdBy;

  private List<Credential> credentials;

  @Getter
  @Data
  public static class Credential {
    private String type;
    private String value;
    private Boolean temporary;
  }
}
