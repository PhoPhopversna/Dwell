package com.neo.service;

import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenResponse;
import reactor.core.publisher.Mono;

public interface KeycloakAuthService {
  public Mono<TokenResponse> login(String username, String password);

  public Mono<TokenResponse> refresh(String refreshToken);

  public Mono<Void> logout(String refreshToken);

  /** Self-registration: the user gets the configured {@code app.registration.default-roles}. */
  public Mono<Void> register(RegisterRequest request);

  /**
   * Registers a user with exactly these realm roles instead of the registration defaults (e.g. the
   * bootstrap admin). Never fed from a request body: callers decide the roles in code.
   */
  public Mono<Void> register(RegisterRequest request, java.util.List<String> roles);
}
