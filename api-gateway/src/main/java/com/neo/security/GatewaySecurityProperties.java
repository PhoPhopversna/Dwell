package com.neo.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record GatewaySecurityProperties(List<String> publicPaths) {

  public GatewaySecurityProperties {
    publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
  }
}
