package com.neo.dto;

import java.util.List;

/**
 * Everything a role/permission screen needs in one call: every permission (the columns) and every
 * role with its ticked permissions (the rows).
 */
public record PermissionMatrixResponse(
    List<PermissionResponse> permissions, List<RolePermissionsResponse> roles) {}
