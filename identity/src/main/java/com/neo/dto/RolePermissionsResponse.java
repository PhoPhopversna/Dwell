package com.neo.dto;

import java.util.List;

/** A role and the permissions it bundles directly. */
public record RolePermissionsResponse(String role, String description, List<String> permissions) {}
