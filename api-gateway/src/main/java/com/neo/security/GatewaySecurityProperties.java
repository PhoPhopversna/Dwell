package com.neo.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record GatewaySecurityProperties(String adminRole, List<String> publicPaths) {

  public GatewaySecurityProperties {
    if (adminRole == null || adminRole.isBlank()) {
      adminRole = "ADMIN";
    }
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
  }
}
