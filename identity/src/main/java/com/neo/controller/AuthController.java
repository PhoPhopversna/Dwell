package com.neo.controller;

import com.neo.dto.ApiResponse;
import com.neo.dto.RegisterRequest;
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
@RequestMapping("identity/api/auth")
public class AuthController {

  private final KeycloakAuthService keycloakAuthService;

  public AuthController(KeycloakAuthService keycloakAuthService) {
    this.keycloakAuthService = keycloakAuthService;
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RegisterRequest request) {
    keycloakAuthService.register(request);
    return ResponseEntity.ok(ApiResponse.ok("Register successfully", null));
  }
}
