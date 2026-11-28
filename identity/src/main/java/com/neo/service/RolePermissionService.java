package com.neo.service;

import com.neo.dto.PermissionMatrixResponse;
import com.neo.dto.PermissionResponse;
import com.neo.dto.RolePermissionsResponse;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Roles as bundles of permissions, managed like Jenkins' role strategy: the system defines the
 * permissions (client roles of the permission client), admins create roles and tick permissions.
 */
public interface RolePermissionService {

  Flux<PermissionResponse> getPermissions();

  Mono<RolePermissionsResponse> getRolePermissions(String roleName);

  /** Makes the role bundle exactly {@code permissions}: adds what's missing, removes the rest. */
  Mono<RolePermissionsResponse> setRolePermissions(String roleName, List<String> permissions);

  Mono<PermissionMatrixResponse> getMatrix();
}
