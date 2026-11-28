package com.neo.controller;

import com.neo.dto.ApiResponse;
import com.neo.dto.PermissionMatrixResponse;
import com.neo.dto.PermissionResponse;
import com.neo.dto.RolePermissionsRequest;
import com.neo.dto.RolePermissionsResponse;
import com.neo.service.RolePermissionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Role/permission management, Jenkins style: create a role ({@code POST /api/roles}), then tick its
 * permissions here. Reading needs {@code role:read}, changing {@code role:write} (SecurityConfig).
 */
@RestController
@RequestMapping("/api")
public class RolePermissionController {

  private final RolePermissionService service;

  public RolePermissionController(RolePermissionService service) {
    this.service = service;
  }

  /** Every permission that exists (the columns of the matrix). */
  @GetMapping("/permissions")
  public Mono<ApiResponse<List<PermissionResponse>>> permissions() {
    return service.getPermissions().collectList().map(ApiResponse::ok);
  }

  /** Every role with its ticked permissions. */
  @GetMapping("/permissions/matrix")
  public Mono<ApiResponse<PermissionMatrixResponse>> matrix() {
    return service.getMatrix().map(ApiResponse::ok);
  }

  @GetMapping("/roles/{roleName}/permissions")
  public Mono<ApiResponse<RolePermissionsResponse>> rolePermissions(@PathVariable String roleName) {
    return service.getRolePermissions(roleName).map(ApiResponse::ok);
  }

  /** Replaces the role's permissions with exactly this list (the ticked boxes). */
  @PutMapping("/roles/{roleName}/permissions")
  public Mono<ApiResponse<RolePermissionsResponse>> setRolePermissions(
      @PathVariable String roleName, @RequestBody @Valid RolePermissionsRequest request) {
    return service
        .setRolePermissions(roleName, request.getPermissions())
        .map(r -> ApiResponse.ok("Role permissions updated", r));
  }
}
