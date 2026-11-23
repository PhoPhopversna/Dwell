package com.neo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

  private static Jwt.Builder jwt() {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .claim("sub", "u1")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60));
  }

  @Test
  void mapsRealmRolesToRoleAuthorities() {
    Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("ADMIN", "CUSTOMER"))).build();
    assertThat(new KeycloakRealmRoleConverter().convert(token))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN", "ROLE_CUSTOMER");
  }

  @Test
  void noRealmAccessClaimMeansNoAuthorities() {
    assertThat(new KeycloakRealmRoleConverter().convert(jwt().build())).isEmpty();
  }
}
