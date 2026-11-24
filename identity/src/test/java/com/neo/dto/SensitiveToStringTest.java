package com.neo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SensitiveToStringTest {

  @Test
  void loginRequestHidesPassword() {
    LoginRequest r = new LoginRequest();
    r.setUsername("bob");
    r.setPassword("Hunter2!");
    assertThat(r.toString()).contains("bob").doesNotContain("Hunter2");
  }

  @Test
  void registerRequestHidesPasswordAndEmail() {
    RegisterRequest.Credential c = new RegisterRequest.Credential();
    c.setType("password");
    c.setValue("Hunter2!");
    RegisterRequest r = new RegisterRequest();
    r.setUserName("bob");
    r.setEmail("bob@example.com");
    r.setCredentials(List.of(c));
    assertThat(r.toString())
        .contains("bob")
        .doesNotContain("Hunter2")
        .doesNotContain("bob@example.com");
    assertThat(c.toString()).doesNotContain("Hunter2");
  }

  @Test
  void tokensAreHidden() {
    RefreshRequest refresh = new RefreshRequest();
    refresh.setRefreshToken("rt-secret");
    LogoutRequest logout = new LogoutRequest();
    logout.setRefreshToken("rt-secret");
    TokenResponse tokens = new TokenResponse();
    tokens.setAccessToken("at-secret");
    tokens.setRefreshToken("rt-secret");
    assertThat(refresh + " " + logout + " " + tokens)
        .doesNotContain("rt-secret")
        .doesNotContain("at-secret");
  }
}
