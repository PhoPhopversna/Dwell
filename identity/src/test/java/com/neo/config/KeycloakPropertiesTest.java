package com.neo.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeycloakPropertiesTest {

  private KeycloakProperties props(String serviceClientId, String serviceClientSecret) {
    KeycloakProperties p = new KeycloakProperties();
    p.setClientId("dwell-client");
    p.setClientSecret("login-secret");
    p.setServiceClientId(serviceClientId);
    p.setServiceClientSecret(serviceClientSecret);
    return p;
  }

  @Test
  void aSeparateServiceClientIsAccepted() {
    assertThatCode(() -> props("dwell-admin", "admin-secret").afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  void startupFailsWithoutAServiceClient() {
    assertThatThrownBy(() -> props(null, null).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("KEYCLOAK_SERVICE_CLIENT_ID");
    assertThatThrownBy(() -> props(" ", "admin-secret").afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> props("dwell-admin", "").afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theLoginClientCannotBeTheServiceClient() {
    assertThatThrownBy(() -> props("dwell-client", "login-secret").afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not be the login client dwell-client");
  }
}
