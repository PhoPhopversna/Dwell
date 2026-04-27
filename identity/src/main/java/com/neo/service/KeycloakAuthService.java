package com.neo.service;

import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenResponse;
import reactor.core.publisher.Mono;

public interface KeycloakAuthService {
  public Mono<TokenResponse> login(String username, String password);

  public Mono<TokenResponse> refresh(String refreshToken);

  public Mono<Void> logout(String refreshToken);

  public Mono<Void> register(RegisterRequest request);
}
