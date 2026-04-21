package com.neo.controller;

import com.neo.dto.*;
import com.neo.service.KeycloakAuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final KeycloakAuthService keycloakAuthService;

  public AuthController(KeycloakAuthService keycloakAuthService) {
    this.keycloakAuthService = keycloakAuthService;
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
    log.info("Register");
    keycloakAuthService.register(request);
    return ResponseEntity.ok(ApiResponse.ok("Register successfully", null));
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<TokenResponse>> login(
      @Valid @RequestBody LoginRequest request) {
    TokenResponse tokens = keycloakAuthService.login(request.getUsername(), request.getPassword());
    return ResponseEntity.ok(ApiResponse.ok("Login successful", tokens));
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<TokenResponse>> refresh(
      @Valid @RequestBody RefreshRequest request) {
    TokenResponse tokens = keycloakAuthService.refresh(request.getRefreshToken());
    return ResponseEntity.ok(ApiResponse.ok("Token refreshed", tokens));
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
    keycloakAuthService.logout(request.getRefreshToken());
    return ResponseEntity.ok(ApiResponse.ok("Logged out successfully", null));
  }
}
