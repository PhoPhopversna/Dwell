package com.neo.dto;

import java.util.List;
import lombok.Data;

@Data
public class UserRolesResponse {
  private String userId;
  private String username;
  private List<RoleRepresentation> realmRoles;
}
