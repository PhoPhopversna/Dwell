package com.neo.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code permissions} column of a route: comma-separated permissions (any one is enough), or
 * {@code PUBLIC} (no token needed). Empty = nobody.
 */
public final class RoutePermissions {

  public static final String METADATA_KEY = "permissions";

  public static final String PUBLIC = "PUBLIC";

  private RoutePermissions() {}

  public static Set<String> parse(String column) {
    if (column == null || column.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(column.split(","))
        .map(String::trim)
        .filter(r -> !r.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public static String join(Collection<String> permissions) {
    if (permissions == null) {
      return null;
    }
    String joined =
        permissions.stream()
            .filter(r -> r != null && !r.isBlank())
            .map(String::trim)
            .distinct()
            .collect(Collectors.joining(","));
    return joined.isEmpty() ? null : joined;
  }
}
