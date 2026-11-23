package com.neo.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for rejecting requests that did not come through the API gateway.
 *
 * @param enabled whether the check is enforced
 * @param headerName header the gateway sets on every forwarded request
 * @param secret shared secret the gateway puts in that header
 * @param excludedPaths Ant-style paths that may be called directly (e.g. API docs)
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
