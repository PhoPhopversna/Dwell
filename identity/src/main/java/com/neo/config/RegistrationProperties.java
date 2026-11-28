package com.neo.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Self-registration ({@code POST /api/auth/register}). */
@Data
@Component
@ConfigurationProperties(prefix = "app.registration")
public class RegistrationProperties {

  /**
   * Realm roles every self-registered user gets (each bundles permissions, e.g. CUSTOMER = {@code
   * category:read} + {@code product:read}). Empty = no role; an admin assigns one later.
   */
  private List<String> defaultRoles = List.of("CUSTOMER");
}
