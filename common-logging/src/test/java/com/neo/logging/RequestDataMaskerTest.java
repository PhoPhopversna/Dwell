package com.neo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestDataMaskerTest {

  private final RequestDataMasker masker = new RequestDataMasker(RequestLoggingProperties.DEFAULTS);

  private static final String JWT =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU";

  @Test
  void masksSensitiveJsonFieldsAtAnyDepthAndKeepsTheRest() {
    String body =
        "{\"user_name\":\"bob\",\"email\":\"bob@example.com\",\"enabled\":true,"
            + "\"credentials\":[{\"type\":\"password\",\"value\":\"Hunter2!\"}],"
            + "\"profile\":{\"phone_number\":\"+855 12 345 678\",\"city\":\"PP\"},"
            + "\"refresh_token\":\"rt-1\",\"note\":\"token was "
            + JWT
            + "\"}";
    String masked = masker.maskBody(body, "application/json", false);
    assertThat(masked)
        .contains("\"user_name\":\"bob\"", "\"enabled\":true", "\"city\":\"PP\"")
        .contains("\"email\":\"****\"", "\"credentials\":\"****\"", "\"phone_number\":\"****\"")
        .contains("\"refresh_token\":\"****\"")
        .doesNotContain("Hunter2", "bob@example.com", "345 678", "rt-1", JWT);
  }

  @Test
  void loginBodyNeverShowsThePassword() {
    assertThat(
            masker.maskBody(
                "{\"username\":\"dwell_admin\",\"password\":\"Admin@12345\"}",
                "application/json;charset=UTF-8",
                false))
        .isEqualTo("{\"username\":\"dwell_admin\",\"password\":\"****\"}");
  }

  @Test
  void cutOffJsonStillGetsMasked() {
    String partial = "{\"username\":\"bob\",\"access_token\":\"abc123\",\"password\":\"Hunt";
    String masked = masker.maskBody(partial, "application/json", true);
    assertThat(masked).doesNotContain("abc123", "Hunt").endsWith("...(truncated)");
  }

  @Test
  void formBodiesAndQueryStringsAreMaskedByParameterName() {
    assertThat(
            masker.maskBody(
                "username=bob&password=Hunter2&client_secret=s3",
                "application/x-www-form-urlencoded",
                false))
        .isEqualTo("username=bob&password=****&client_secret=****");
    assertThat(masker.maskQuery("page=2&size=20&token=abc&Email=a%40b.com"))
        .isEqualTo("page=2&size=20&token=****&Email=****");
    assertThat(masker.maskQuery(null)).isNull();
  }

  @Test
  void extraFieldsCanBeConfigured() {
    RequestDataMasker custom =
        new RequestDataMasker(
            new RequestLoggingProperties(null, null, null, null, List.of("national_id", "dob")));
    assertThat(
            custom.maskBody(
                "{\"nationalId\":\"123\",\"dob\":\"1990-01-01\",\"name\":\"x\"}",
                "application/json",
                false))
        .isEqualTo("{\"nationalId\":\"****\",\"dob\":\"****\",\"name\":\"x\"}");
  }

  @Test
  void longBodiesAreCapped() {
    RequestDataMasker small =
        new RequestDataMasker(new RequestLoggingProperties(null, null, null, 20, null));
    assertThat(
            small.maskBody(
                "{\"description\":\"" + "x".repeat(100) + "\"}", "application/json", false))
        .hasSize(20 + "...(truncated)".length())
        .endsWith("...(truncated)");
  }

  @Test
  void onlyTextLikeBodiesAreLogged() {
    assertThat(RequestDataMasker.isLoggableContentType("application/json")).isTrue();
    assertThat(RequestDataMasker.isLoggableContentType("application/problem+json")).isTrue();
    assertThat(RequestDataMasker.isLoggableContentType("application/x-www-form-urlencoded"))
        .isTrue();
    assertThat(RequestDataMasker.isLoggableContentType("multipart/form-data; boundary=x"))
        .isFalse();
    assertThat(RequestDataMasker.isLoggableContentType("image/png")).isFalse();
    assertThat(RequestDataMasker.isLoggableContentType("application/octet-stream")).isFalse();
    assertThat(RequestDataMasker.isLoggableContentType(null)).isFalse();
  }

  @Test
  void errorJsonCarriesTheCorrelationId() {
    assertThat(CorrelationId.errorJson("Access denied", "abc-1"))
        .isEqualTo(
            "{\"success\":false,\"message\":\"Access denied\",\"data\":null,\"correlationId\":\"abc-1\"}");
    assertThat(CorrelationId.errorJson("Access denied", null))
        .isEqualTo("{\"success\":false,\"message\":\"Access denied\",\"data\":null}");
  }
}
