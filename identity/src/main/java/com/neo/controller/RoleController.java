package com.neo.controller;

import com.neo.dto.*;
import com.neo.service.RoleService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/roles")
@Slf4j
public class RoleController {
  private final RoleService roleService;

  public RoleController(RoleService roleService) {
    this.roleService = roleService;
  }

  @GetMapping
  public Flux<ApiResponse<RoleRepresentation>> getAllRoles() {
    return roleService.getAllRoles().map(role -> ApiResponse.ok(role));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<ApiResponse<Void>> createRole(@RequestBody @Valid CreateRoleRequest request) {
    return roleService
        .createRole(request)
        .thenReturn(ApiResponse.<Void>ok("Role created successfully", null));
  }

  @DeleteMapping("/{roleName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<ApiResponse<Void>> deleteRole(@PathVariable String roleName) {
    return roleService
        .deleteRole(roleName)
        .thenReturn(ApiResponse.<Void>ok("Role deleted successfully", null))
        .doOnSuccess(v -> log.debug("Deleted role: {}", roleName));
  }

  @PostMapping("/users/{userId}/assign")
  public Mono<ApiResponse<Void>> assignRolesToUser(
      @PathVariable String userId, @RequestBody @Valid RoleNamesRequest request) {
    return roleService
        .assignRolesToUser(userId, request.getRoleNames())
        .thenReturn(ApiResponse.<Void>ok("Roles assigned successfully", null))
        .doOnSuccess(
            v -> log.debug("Assigned roles {} to user {}", request.getRoleNames(), userId));
  }

  @DeleteMapping("/users/{userId}/remove")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<ApiResponse<Void>> removeRolesFromUser(
      @PathVariable String userId, @RequestBody @Valid RoleNamesRequest request) {
    return roleService
        .removeRolesFromUser(userId, request.getRoleNames())
        .thenReturn(ApiResponse.<Void>ok("Roles removed successfully", null))
        .doOnSuccess(
            v -> log.debug("Removed roles {} from user {}", request.getRoleNames(), userId));
  }

  @GetMapping("/users/{userId}")
  public Mono<ApiResponse<UserRolesResponse>> getUserRoles(@PathVariable String userId) {
    return roleService
        .getUserRoles(userId)
        .map(userRoles -> ApiResponse.ok(userRoles))
        .doOnSuccess(r -> log.debug("Fetched roles for user {}", userId));
  }
}
