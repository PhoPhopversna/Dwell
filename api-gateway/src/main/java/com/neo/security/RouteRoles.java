package com.neo.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class RouteRoles {

  public static final String METADATA_KEY = "roles";

  public static final String PUBLIC = "PUBLIC";

  private RouteRoles() {}

  public static Set<String> parse(String column) {
    if (column == null || column.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(column.split(","))
        .map(String::trim)
        .filter(r -> !r.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public static String join(Collection<String> roles) {
    if (roles == null) {
      return null;
    }
    String joined =
        roles.stream()
            .filter(r -> r != null && !r.isBlank())
            .map(String::trim)
            .distinct()
            .collect(Collectors.joining(","));
    return joined.isEmpty() ? null : joined;
  }
}
