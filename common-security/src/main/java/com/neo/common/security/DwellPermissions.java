package com.neo.common.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one list of permissions Dwell's services check: name and description. identity creates any
 * that are missing in Keycloak at startup (KeycloakPermissionSync), so adding a permission is: add
 * it here, use it in a service's SecurityConfig, deploy. No Keycloak step.
 *
 * <p>A permission is a client role of the Keycloak client {@link #CLIENT}. Roles (ADMIN, CUSTOMER
 * or any role an admin creates) are bundles of permissions, ticked in the role/permission matrix.
 * Services check permissions with {@code hasAuthority(...)}, never realm role names.
 */
public final class DwellPermissions {

  /** The Keycloak client whose roles are the permissions. */
  public static final String CLIENT = "dwell-api";

  /**
   * Not a permission: a role of {@link #CLIENT} that bundles every permission. It sits in the login
   * client's token scope, so a newly created permission reaches tokens without anyone changing the
   * login client. Never assign it to users; it's hidden from the permission list.
   */
  public static final String SCOPE_ROLE = "permission-scope";

  public static final String CATEGORY_READ = "category:read";
  public static final String CATEGORY_WRITE = "category:write";
  public static final String PRODUCT_READ = "product:read";
  public static final String PRODUCT_WRITE = "product:write";
  public static final String ROLE_READ = "role:read";
  public static final String ROLE_WRITE = "role:write";
  public static final String USER_ROLE_ASSIGN = "user-role:assign";
  public static final String USER_MANAGE = "user:manage";
  public static final String ROUTE_MANAGE = "route:manage";

  /** Every permission with the description shown in the matrix (used when creating it). */
  public static final Map<String, String> DESCRIPTIONS = descriptions();

  public static final List<String> ALL = List.copyOf(DESCRIPTIONS.keySet());

  private static Map<String, String> descriptions() {
    Map<String, String> d = new LinkedHashMap<>();
    d.put(CATEGORY_READ, "Read categories and their attributes");
    d.put(CATEGORY_WRITE, "Create, change and delete categories and their attributes");
    d.put(PRODUCT_READ, "Read products, variants and images");
    d.put(PRODUCT_WRITE, "Create, change and delete products, variants and images");
    d.put(ROLE_READ, "List roles, their permissions and a user's roles");
    d.put(ROLE_WRITE, "Create and delete roles and tick their permissions");
    d.put(USER_ROLE_ASSIGN, "Give users roles and take them away");
    d.put(USER_MANAGE, "Activate and deactivate users");
    d.put(ROUTE_MANAGE, "Manage the gateway's routes");
    return java.util.Collections.unmodifiableMap(d);
  }

  private DwellPermissions() {}
}
