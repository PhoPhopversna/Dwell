package com.neo.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.util.AntPathMatcher;

/** Stack-independent part of the gateway-only check. */
public class GatewaySecretCheck {

  private final GatewayAuthProperties properties;
  private final byte[] expectedSecret;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public GatewaySecretCheck(GatewayAuthProperties properties) {
    if (properties.secret() == null || properties.secret().isBlank()) {
      throw new IllegalStateException(
          "app.gateway-auth.secret must be set when app.gateway-auth.enabled=true");
    }
    this.properties = properties;
    this.expectedSecret = properties.secret().getBytes(StandardCharsets.UTF_8);
  }

  public String headerName() {
    return properties.headerName();
  }

  public boolean isExcluded(String path) {
    return properties.excludedPaths().stream().anyMatch(p -> pathMatcher.match(p, path));
  }

  /** Constant-time comparison. */
  public boolean matches(String provided) {
    return provided != null
        && MessageDigest.isEqual(expectedSecret, provided.getBytes(StandardCharsets.UTF_8));
  }
}
