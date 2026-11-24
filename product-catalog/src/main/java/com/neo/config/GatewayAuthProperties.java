package com.neo.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
