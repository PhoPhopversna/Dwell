package com.neo.common.security;

import java.util.List;

/**
 * Every permission Dwell checks. Each is a client role of the Keycloak client {@code dwell-api}
 * (see docs/keycloak-setup.md); the realm roles {@code ADMIN} and {@code CUSTOMER} are composite
 * roles that bundle them, so users still get permissions by being given a role. Services check
 * these with {@code hasAuthority(...)}, never realm role names.
 */
public final class DwellPermissions {

  /** The Keycloak client whose roles are the permissions. */
  public static final String CLIENT = "dwell-api";

  public static final String CATEGORY_READ = "category:read";
  public static final String CATEGORY_WRITE = "category:write";
  public static final String PRODUCT_READ = "product:read";
  public static final String PRODUCT_WRITE = "product:write";
  public static final String ROLE_READ = "role:read";
  public static final String ROLE_WRITE = "role:write";
  public static final String USER_ROLE_ASSIGN = "user-role:assign";
  public static final String USER_MANAGE = "user:manage";
  public static final String ROUTE_MANAGE = "route:manage";

  public static final List<String> ALL =
      List.of(
          CATEGORY_READ,
          CATEGORY_WRITE,
          PRODUCT_READ,
          PRODUCT_WRITE,
          ROLE_READ,
          ROLE_WRITE,
          USER_ROLE_ASSIGN,
          USER_MANAGE,
          ROUTE_MANAGE);

  /** What the realm role CUSTOMER bundles; ADMIN bundles {@link #ALL}. */
  public static final List<String> CUSTOMER = List.of(CATEGORY_READ, PRODUCT_READ);

  private DwellPermissions() {}
}
