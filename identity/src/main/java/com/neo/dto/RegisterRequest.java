package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

@Getter
@Data
public class RegisterRequest {
  @NotBlank
  @Size(max = 255)
  @JsonProperty("user_name")
  private String userName;

  @NotBlank @Email @ToString.Exclude private String email;

  @JsonProperty("first_name")
  private String firstName;

  @JsonProperty("last_name")
  private String lastName;

  private Boolean enabled;

  @JsonProperty("created_by")
  private String createdBy;

  @NotEmpty @Valid @ToString.Exclude private List<Credential> credentials;

  @Getter
  @Data
  public static class Credential {
    @NotBlank private String type;
    @NotBlank @ToString.Exclude private String value;
    private Boolean temporary;
  }
}
