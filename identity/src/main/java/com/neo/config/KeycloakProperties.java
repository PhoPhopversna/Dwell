package com.neo.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties implements InitializingBean {
  private String url;
  private String realm;
  private String clientId;
  private String clientSecret;
  private String adminUsername;
  private String adminPassword;
  private String adminClientId = "admin-cli";
  // Service account for admin API calls; must be a separate client from clientId (user logins)
  private String serviceClientId;
  private String serviceClientSecret;
  private long tokenTtl;
  private long refreshTokenTtl;

  /** Runs after binding: identity refuses to start without its own admin service client. */
  @Override
  public void afterPropertiesSet() {
    validateServiceClient();
  }

  void validateServiceClient() {
    if (isBlank(serviceClientId) || isBlank(serviceClientSecret)) {
      throw new IllegalStateException(
          "keycloak.service-client-id and keycloak.service-client-secret must be set"
              + " (env KEYCLOAK_SERVICE_CLIENT_ID / KEYCLOAK_SERVICE_CLIENT_SECRET): identity's"
              + " admin API calls need their own service client, e.g. dwell-admin."
              + " See docs/services/identity.md#keycloak-realm-security");
    }
    if (serviceClientId.equals(clientId)) {
      throw new IllegalStateException(
          "keycloak.service-client-id must not be the login client "
              + clientId
              + ": admin API calls need a separate client, e.g. dwell-admin."
              + " See docs/services/identity.md#keycloak-realm-security");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

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

  public Long getTokenTtl() {
    return tokenTtl;
  }

  public Long getRefreshTokenTtl() {
    return refreshTokenTtl;
  }
}
