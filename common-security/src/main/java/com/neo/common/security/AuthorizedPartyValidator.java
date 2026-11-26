package com.neo.common.security;

import java.util.Collection;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Accepts only tokens Keycloak issued to one of our clients (the {@code azp} claim). */
public class AuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

  private final Set<String> allowedClients;

  public AuthorizedPartyValidator(Collection<String> allowedClients) {
    this.allowedClients = Set.copyOf(allowedClients);
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    String azp = jwt.getClaimAsString("azp");
    if (azp != null && allowedClients.contains(azp)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "Token was not issued to an allowed client", null));
  }
}
