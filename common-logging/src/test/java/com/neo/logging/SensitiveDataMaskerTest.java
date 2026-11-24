package com.neo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataMaskerTest {

  private static final String JWT =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU";

  @Test
  void masksJwtsAnywhereInText() {
    assertThat(SensitiveDataMasker.mask("token was " + JWT + " ok"))
        .doesNotContain(JWT)
        .contains("****");
  }

  @Test
  void masksAuthorizationSchemes() {
    assertThat(SensitiveDataMasker.mask("Authorization: Bearer abc.def-ghi"))
        .doesNotContain("abc.def-ghi");
    assertThat(SensitiveDataMasker.mask("Basic dXNlcjpwYXNz")).doesNotContain("dXNlcjpwYXNz");
  }

  @Test
  void masksLombokToStringAndJsonSecrets() {
    String lombok = "LoginRequest(username=bob, password=Hunter2!)";
    assertThat(SensitiveDataMasker.mask(lombok)).contains("username=bob").doesNotContain("Hunter2");

    String json = "{\"client_secret\":\"s3cr3t\",\"refresh_token\":\"rt-123\",\"name\":\"x\"}";
    assertThat(SensitiveDataMasker.mask(json))
        .doesNotContain("s3cr3t")
        .doesNotContain("rt-123")
        .contains("\"name\":\"x\"");
  }

  @Test
  void masksStructuredFieldsBySensitiveKey() {
    assertThat(SensitiveDataMasker.maskValue("Authorization", "Bearer x")).isEqualTo("****");
    assertThat(SensitiveDataMasker.maskValue("password", 1234)).isEqualTo("****");
    assertThat(SensitiveDataMasker.maskValue("path", "/api/products")).isEqualTo("/api/products");
    assertThat(SensitiveDataMasker.maskValue("status", 200)).isEqualTo(200);
  }

  @Test
  void leavesOrdinaryTextAlone() {
    String text = "Request completed successfully for /api/v1/products/42";
    assertThat(SensitiveDataMasker.mask(text)).isEqualTo(text);
  }
}
