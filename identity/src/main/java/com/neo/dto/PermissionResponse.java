package com.neo.dto;

/** One permission (a client role of the permission client, e.g. {@code product:write}). */
public record PermissionResponse(String name, String description) {}
