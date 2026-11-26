package com.neo.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRoleConverterTest {

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
    assertThat(new KeycloakRoleConverter(null).convert(token))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN", "ROLE_CUSTOMER");
  }

  @Test
  void noRealmAccessClaimMeansNoAuthorities() {
    assertThat(new KeycloakRoleConverter(null).convert(jwt().build())).isEmpty();
  }

  @Test
  void permissionClientRolesBecomePlainAuthorities() {
    Jwt token =
        jwt()
            .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
            .claim(
                "resource_access",
                Map.of(
                    "dwell-api", Map.of("roles", List.of("product:read")),
                    "realm-management", Map.of("roles", List.of("manage-users"))))
            .build();
    assertThat(new KeycloakRoleConverter("dwell-api").convert(token))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_CUSTOMER", "product:read");
  }

  @Test
  void otherClientsRolesAreIgnoredWithoutAPermissionClient() {
    Jwt token =
        jwt()
            .claim("resource_access", Map.of("dwell-api", Map.of("roles", List.of("product:read"))))
            .build();
    assertThat(new KeycloakRoleConverter(" ").convert(token)).isEmpty();
  }
}
