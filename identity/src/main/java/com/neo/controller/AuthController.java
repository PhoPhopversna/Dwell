package com.neo.controller;

import com.neo.dto.*;
import com.neo.service.KeycloakAuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final KeycloakAuthService keycloakAuthService;

  public AuthController(KeycloakAuthService keycloakAuthService) {
    this.keycloakAuthService = keycloakAuthService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<ApiResponse<String>> register(@Valid @RequestBody RegisterRequest request) {
    return keycloakAuthService
        .register(request)
        .then(Mono.just(ApiResponse.ok("Register successfully")));
  }

  @PostMapping("/login")
  public Mono<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
    return keycloakAuthService
        .login(request.getUsername(), request.getPassword())
        .map(tokens -> ApiResponse.ok("Login successful", tokens));
  }

  @PostMapping("/refresh")
  public Mono<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
    return keycloakAuthService
        .refresh(request.getRefreshToken())
        .map(tokens -> ApiResponse.ok("Token refreshed", tokens));
  }

  @PostMapping("/logout")
  public Mono<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
    return keycloakAuthService
        .logout(request.getRefreshToken())
        .then(Mono.just(ApiResponse.ok("Logged out successfully", null)));
  }
}
