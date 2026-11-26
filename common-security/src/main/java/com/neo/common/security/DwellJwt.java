package com.neo.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** The JWT rules every Dwell service applies. */
public final class DwellJwt {

  private DwellJwt() {}

  /** Expiry + issuer (Spring's defaults) + allowed client. */
  public static OAuth2TokenValidator<Jwt> validator(String issuer, DwellJwtProperties props) {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalStateException(
          "spring.security.oauth2.resourceserver.jwt.issuer-uri must be set: tokens from any"
              + " other issuer must be rejected");
    }
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new AuthorizedPartyValidator(props.allowedClients()));
  }

  public static JwtAuthenticationConverter authenticationConverter(DwellJwtProperties props) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        new KeycloakRoleConverter(props.permissionClient()));
    converter.setPrincipalClaimName(props.principalClaim());
    return converter;
  }
}
