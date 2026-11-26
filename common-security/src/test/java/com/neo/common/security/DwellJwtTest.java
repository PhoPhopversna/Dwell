package com.neo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Real signed tokens through a decoder set up exactly like the services' one. */
class DwellJwtTest {

  private static final String ISSUER = "http://kc/realms/Dwell";
  private static final KeyPair KEYS = keyPair();
  private static final KeyPair OTHER_KEYS = keyPair();

  private final DwellJwtProperties props = new DwellJwtProperties(null, null, null);
  private final NimbusJwtDecoder decoder = decoder();

  private static KeyPair keyPair() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private NimbusJwtDecoder decoder() {
    NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    d.setJwtValidator(DwellJwt.validator(ISSUER, props));
    return d;
  }

  private static String token(KeyPair keys, String issuer, String azp, Instant expires) {
    try {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .subject("u1")
              .issuer(issuer)
              .issueTime(Date.from(expires.minusSeconds(300)))
              .expirationTime(Date.from(expires))
              .claim("preferred_username", "alice")
              .claim("realm_access", Map.of("roles", List.of("ADMIN")))
              .claim(
                  "resource_access",
                  Map.of(
                      "dwell-api", Map.of("roles", List.of("product:read")),
                      "realm-management", Map.of("roles", List.of("manage-users"))));
      if (azp != null) {
        claims.claim("azp", azp);
      }
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
      jwt.sign(new RSASSASigner(keys.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String valid() {
    return token(KEYS, ISSUER, "dwell-client", Instant.now().plusSeconds(60));
  }

  @Test
  void validLoginTokenIsAcceptedAndMapped() {
    Jwt jwt = decoder.decode(valid());
    JwtAuthenticationToken auth =
        (JwtAuthenticationToken) DwellJwt.authenticationConverter(props).convert(jwt);
    assertThat(auth.getName()).isEqualTo("alice");
    // Default permission client dwell-api; other clients' roles (realm-management) never count
    assertThat(auth.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "product:read");
  }

  @Test
  void tokenForAnotherClientIsRejected() {
    // e.g. the dwell-admin service account: valid signature and issuer, wrong client
    String t = token(KEYS, ISSUER, "dwell-admin", Instant.now().plusSeconds(60));
    assertThatThrownBy(() -> decoder.decode(t))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("allowed client");
  }

  @Test
  void tokenWithoutAzpIsRejected() {
    String t = token(KEYS, ISSUER, null, Instant.now().plusSeconds(60));
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void tokenFromAnotherIssuerIsRejected() {
    String t =
        token(KEYS, "http://kc/realms/master", "dwell-client", Instant.now().plusSeconds(60));
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void expiredTokenIsRejected() {
    String t = token(KEYS, ISSUER, "dwell-client", Instant.now().minusSeconds(600));
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void tokenSignedWithAnotherKeyIsRejected() {
    String t = token(OTHER_KEYS, ISSUER, "dwell-client", Instant.now().plusSeconds(60));
    assertThatThrownBy(() -> decoder.decode(t))
        .isInstanceOf(org.springframework.security.oauth2.jwt.BadJwtException.class);
  }

  @Test
  void missingIssuerConfigFailsStartup() {
    assertThatThrownBy(() -> DwellJwt.validator(" ", props))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer-uri");
    assertThatCode(() -> DwellJwt.validator(ISSUER, props)).doesNotThrowAnyException();
  }
}
