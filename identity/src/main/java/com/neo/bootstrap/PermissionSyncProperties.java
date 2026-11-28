package com.neo.bootstrap;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.keycloak.permission-sync")
public record PermissionSyncProperties(Boolean enabled, Map<String, List<String>> initialGrants) {

  public PermissionSyncProperties {
    enabled = enabled == null || enabled;
    initialGrants = initialGrants == null ? Map.of() : Map.copyOf(initialGrants);
  }

  public boolean grants(String role, String permission) {
    List<String> list = initialGrants.getOrDefault(role, List.of());
    return list.contains("*") || list.contains(permission);
  }
}
