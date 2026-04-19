package com.neo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties {
  private String url;
  private String realm;
  private String clientId;
  private String clientSecret;
  private String adminUsername;
  private String adminPassword;
  private String adminClientId = "admin-cli";

  public String getTokenEndpoint() {
    return url + "/realms/" + realm + "/protocol/openid-connect/token";
  }

  public String getLogoutEndpoint() {
    return url + "/realms/" + realm + "/protocol/openid-connect/logout";
  }

  public String getUserInfoEndpoint() {
    return url + "/realms/" + realm + "/protocol/openid-connect/userinfo";
  }

  public String getAdminTokenEndpoint() {
    return url + "/realms/master/protocol/openid-connect/token";
  }

  public String getRealmRolesEndpoint() {
    return url + "/admin/realms/" + realm + "/roles";
  }

  public String getRealmRoleByNameEndpoint(String roleName) {
    return url + "/admin/realms/" + realm + "/roles/" + roleName;
  }

  public String getUsersEndpoint() {
    return url + "/admin/realms/" + realm + "/users";
  }

  public String getUserByIdEndpoint(String userId) {
    return url + "/admin/realms/" + realm + "/users/" + userId;
  }

  public String getUserRoleMappingsEndpoint(String userId) {
    return url + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
  }

  public String getRegisterEndpoint() {
    return url + "/admin/realms/" + realm + "/users";
  }

  public String getRealmToken() {
    return url + "/realms/" + realm + "/protocol/openid-connect/token";
  }
}
