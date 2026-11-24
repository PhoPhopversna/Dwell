package com.neo.bootstrap;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
