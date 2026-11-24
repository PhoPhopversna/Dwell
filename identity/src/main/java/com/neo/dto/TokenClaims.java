package com.neo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;

/** The user claims of a token Keycloak just issued to us (so the signature is not re-checked). */
public record TokenClaims(
    String subject, String username, String email, String firstName, String lastName) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static TokenClaims fromJwt(String jwt) {
    String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException("Not a JWT");
    }
    try {
      JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
      String subject = text(claims, "sub");
      if (subject == null) {
        throw new IllegalArgumentException("JWT has no sub claim");
      }
      String username = text(claims, "preferred_username");
      return new TokenClaims(
          subject,
          username != null ? username : subject,
          text(claims, "email"),
          text(claims, "given_name"),
          text(claims, "family_name"));
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Unreadable JWT payload: " + e.getMessage(), e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  @Override
  public String toString() {
    return "TokenClaims[subject=" + subject + ", username=" + username + "]";
  }
}
