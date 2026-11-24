package com.neo.controller;

import com.neo.dto.ApiResponse;
import com.neo.service.UserAdminService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/api/users")
public class UserController {

  private final UserAdminService userAdminService;

  public UserController(UserAdminService userAdminService) {
    this.userAdminService = userAdminService;
  }

  @PostMapping("/{userId}/deactivate")
  public Mono<ApiResponse<Void>> deactivate(@PathVariable String userId) {
    return userAdminService
        .setActive(userId, false)
        .thenReturn(ApiResponse.<Void>ok("User deactivated", null));
  }

  @PostMapping("/{userId}/activate")
  public Mono<ApiResponse<Void>> activate(@PathVariable String userId) {
    return userAdminService
        .setActive(userId, true)
        .thenReturn(ApiResponse.<Void>ok("User activated", null));
  }
}
