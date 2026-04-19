package com.neo.dto;

import java.util.List;
import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class RegisterRequest {
  private String username;
  private String email;
  private String firstName;
  private String lastName;
  private Boolean enabled;
  private List<Credential> credentials;

  @Getter
  @Data
  public static class Credential {
    private String type;
    private String value;
    private Boolean temporary;
  }
}
