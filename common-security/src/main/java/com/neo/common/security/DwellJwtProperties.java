package com.neo.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Extra JWT rules on top of Spring's signature, issuer and expiry checks.
 *
 * @param allowedClients clients ({@code azp} claim) whose tokens are accepted. Default {@code
 *     dwell-client}: a token Keycloak issued to any other client of the realm (e.g. the {@code
 *     dwell-admin} service account) is rejected.
 * @param permissionClient client whose client roles become plain authorities (permissions such as
 *     {@code product:write}). Default {@code dwell-api} ({@link DwellPermissions#CLIENT})
 * @param principalClaim claim used as the user's name in logs and {@code Authentication#getName}
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record DwellJwtProperties(
    List<String> allowedClients, String permissionClient, String principalClaim) {

  public DwellJwtProperties {
    allowedClients =
        allowedClients == null || allowedClients.isEmpty()
            ? List.of("dwell-client")
            : List.copyOf(allowedClients);
    if (permissionClient == null || permissionClient.isBlank()) {
      permissionClient = DwellPermissions.CLIENT;
    }
    if (principalClaim == null || principalClaim.isBlank()) {
      principalClaim = "preferred_username";
    }
  }
}
