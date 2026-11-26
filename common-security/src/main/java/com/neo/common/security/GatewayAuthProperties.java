package com.neo.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-only access: requests must carry the secret the gateway adds. {@code excludedPaths} are
 * relative to the service's base/context path.
 */
@ConfigurationProperties(prefix = "app.gateway-auth")
public record GatewayAuthProperties(
    boolean enabled, String headerName, String secret, List<String> excludedPaths) {

  public GatewayAuthProperties {
    if (headerName == null || headerName.isBlank()) {
      headerName = "X-Gateway-Secret";
    }
    excludedPaths = excludedPaths == null ? List.of() : List.copyOf(excludedPaths);
  }
}
