package com.neo.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param adminRole realm role required for the gateway's own route-admin API ({@code /api/routes})
 * @param publicPaths paths anyone may call without a token, whatever the route's roles say (login,
 *     register, ...). Without these nobody could get a token through the gateway.
 */
@ConfigurationProperties(prefix = "app.security")
public record GatewaySecurityProperties(String adminRole, List<String> publicPaths) {

  public GatewaySecurityProperties {
    if (adminRole == null || adminRole.isBlank()) {
      adminRole = "ADMIN";
    }
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
  }
}
