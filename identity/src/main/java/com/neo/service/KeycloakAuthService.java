package com.neo.service;

import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenResponse;

public interface KeycloakAuthService {
  public TokenResponse login(String username, String password);

  public TokenResponse refresh(String refreshToken);

  public void logout(String refreshToken);

  public void register(RegisterRequest request);
}
