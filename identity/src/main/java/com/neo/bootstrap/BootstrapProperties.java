package com.neo.bootstrap;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * First-admin bootstrap. Identity only accepts calls from the gateway, and the gateway only lets
 * ADMIN manage roles, so the first ADMIN is created here at startup.
 *
 * @param enabled run the bootstrap at startup
 * @param realmRoles realm roles to make sure exist (e.g. ADMIN, CUSTOMER)
 * @param adminRole role given to the bootstrap admin
 * @param adminUsername bootstrap admin username
 * @param adminEmail bootstrap admin email
 * @param adminPassword password used only when the admin user has to be created
 */
@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(
    boolean enabled,
    List<String> realmRoles,
    String adminRole,
    String adminUsername,
    String adminEmail,
    String adminPassword) {

  public BootstrapProperties {
    realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
    if (adminRole == null || adminRole.isBlank()) {
      adminRole = "ADMIN";
    }
  }
}
