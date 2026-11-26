package com.neo.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak token to authorities: realm roles ({@code realm_access.roles}) become {@code
 * ROLE_<name>}, so {@code hasRole("ADMIN")} works; the permission client's roles ({@code
 * resource_access.<client>.roles}) become authorities as they are, so {@code
 * hasAuthority("product:create")} works.
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private final String permissionClient;

  public KeycloakRoleConverter(String permissionClient) {
    this.permissionClient =
        permissionClient == null || permissionClient.isBlank() ? null : permissionClient;
  }

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    roles(jwt.getClaims().get("realm_access"))
        .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
    if (permissionClient != null
        && jwt.getClaims().get("resource_access") instanceof Map<?, ?> clients) {
      roles(clients.get(permissionClient))
          .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
    }
    return authorities;
  }

  private static List<String> roles(Object access) {
    if (!(access instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) {
      return List.of();
    }
    return roles.stream().filter(String.class::isInstance).map(String.class::cast).toList();
  }
}
