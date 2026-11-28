package com.neo.service.impl;

import com.neo.common.security.DwellJwtProperties;
import com.neo.common.security.DwellPermissions;
import com.neo.config.KeycloakProperties;
import com.neo.dto.PermissionMatrixResponse;
import com.neo.dto.PermissionResponse;
import com.neo.dto.RolePermissionsResponse;
import com.neo.dto.UserAuditRequest;
import com.neo.exception.AuthException;
import com.neo.exception.ConflictException;
import com.neo.service.RolePermissionService;
import com.neo.service.UserService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keycloak model: a permission is a client role of the permission client ({@code dwell-api}); a
 * role is a realm role, and "ticking" a permission adds it to the role's composites. Keycloak then
 * puts a user's permissions in their token, which every service checks. Nothing here is a fixed
 * list: roles are whatever admins create, permissions whatever the permission client holds.
 */
@Slf4j
@Service
@SuppressWarnings({"rawtypes", "unchecked"})
public class RolePermissionServiceImpl implements RolePermissionService {

  /** Keycloak's own realm roles; not business roles, so not shown on the matrix. */
  private static final Set<String> KEYCLOAK_BUILT_IN =
      Set.of("offline_access", "uma_authorization");

  private final KeycloakProperties keycloakProperties;
  private final KeycloakAdminServiceImpl keycloakAdmin;
  private final KeycloakGenericConnector keycloak;
  private final UserService userService;
  private final String permissionClient;

  public RolePermissionServiceImpl(
      KeycloakProperties keycloakProperties,
      KeycloakAdminServiceImpl keycloakAdmin,
      KeycloakGenericConnector keycloak,
      UserService userService,
      DwellJwtProperties jwtProperties) {
    this.keycloakProperties = keycloakProperties;
    this.keycloakAdmin = keycloakAdmin;
    this.keycloak = keycloak;
    this.userService = userService;
    this.permissionClient = jwtProperties.permissionClient();
  }

  @Override
  public Flux<PermissionResponse> getPermissions() {
    return withAdminToken(token -> permissionCatalog(token).map(Catalog::responses))
        .flatMapMany(Flux::fromIterable);
  }

  @Override
  public Mono<RolePermissionsResponse> getRolePermissions(String roleName) {
    return withAdminToken(
        token ->
            Mono.zip(realmRole(token, roleName), permissionCatalog(token))
                .flatMap(
                    t ->
                        directPermissions(token, roleName, t.getT2().clientUuid())
                            .map(
                                names ->
                                    new RolePermissionsResponse(
                                        roleName, description(t.getT1()), names))));
  }

  @Override
  public Mono<RolePermissionsResponse> setRolePermissions(
      String roleName, List<String> permissions) {
    Set<String> wanted = new LinkedHashSet<>(permissions);
    return Mono.deferContextual(
        ctx -> {
          String correlationId = ctx.getOrDefault("correlationId", UUID.randomUUID().toString());
          return withAdminToken(
              token ->
                  Mono.zip(realmRole(token, roleName), permissionCatalog(token))
                      .flatMap(
                          t -> {
                            Catalog catalog = t.getT2();
                            List<String> unknown =
                                wanted.stream()
                                    .filter(p -> !catalog.byName().containsKey(p))
                                    .toList();
                            if (!unknown.isEmpty()) {
                              return Mono.error(
                                  new AuthException(
                                      "Unknown permissions: " + unknown, HttpStatus.BAD_REQUEST));
                            }
                            return directPermissions(token, roleName, catalog.clientUuid())
                                .flatMap(
                                    current ->
                                        apply(
                                            token,
                                            roleName,
                                            description(t.getT1()),
                                            catalog,
                                            current,
                                            wanted,
                                            correlationId));
                          }));
        });
  }

  @Override
  public Mono<PermissionMatrixResponse> getMatrix() {
    return withAdminToken(
        token ->
            permissionCatalog(token)
                .flatMap(
                    catalog ->
                        keycloak
                            .getFlux(
                                keycloakProperties.getRealmRolesEndpoint(),
                                token,
                                Map.class,
                                RoleServiceImpl.AUTH_ERROR_FACTORY)
                            .filter(r -> isBusinessRole(String.valueOf(r.get("name"))))
                            .flatMapSequential(
                                r -> {
                                  String name = String.valueOf(r.get("name"));
                                  return directPermissions(token, name, catalog.clientUuid())
                                      .map(
                                          names ->
                                              new RolePermissionsResponse(
                                                  name, description(r), names));
                                })
                            .collectList()
                            .map(
                                roles ->
                                    new PermissionMatrixResponse(catalog.responses(), roles))));
  }

  private Mono<RolePermissionsResponse> apply(
      String token,
      String roleName,
      String description,
      Catalog catalog,
      List<String> current,
      Set<String> wanted,
      String correlationId) {
    List<String> toAdd = wanted.stream().filter(p -> !current.contains(p)).toList();
    List<String> toRemove = current.stream().filter(p -> !wanted.contains(p)).toList();
    String endpoint = keycloakProperties.getRoleCompositesEndpoint(roleName);

    // Deferred: nothing is even prepared until the lockout guard has passed
    Mono<Void> add =
        toAdd.isEmpty()
            ? Mono.empty()
            : Mono.defer(
                () ->
                    keycloak
                        .postMono(
                            endpoint,
                            MediaType.APPLICATION_JSON,
                            BodyInserters.fromValue(catalog.representations(toAdd)),
                            token,
                            Void.class,
                            RoleServiceImpl.AUTH_ERROR_FACTORY)
                        .then());
    Mono<Void> remove =
        toRemove.isEmpty()
            ? Mono.empty()
            : Mono.defer(
                () ->
                    keycloak.deleteWithBody(
                        endpoint,
                        catalog.representations(toRemove),
                        token,
                        RoleServiceImpl.AUTH_ERROR_FACTORY));

    return refuseSelfLockout(roleName, toRemove)
        .then(add)
        .then(remove)
        .then(
            Mono.defer(
                () ->
                    caller()
                        .defaultIfEmpty("?")
                        .flatMap(
                            who -> {
                              log.info(
                                  "Role {} permissions changed by {}: added {}, removed {}",
                                  roleName,
                                  who,
                                  toAdd,
                                  toRemove);
                              return userService
                                  .saveUserAuditLog(
                                      new UserAuditRequest(
                                          null,
                                          "ROLE_PERMISSIONS",
                                          true,
                                          "Role "
                                              + roleName
                                              + " by "
                                              + who
                                              + ": added "
                                              + toAdd
                                              + ", removed "
                                              + toRemove,
                                          correlationId))
                                  .onErrorResume(e -> Mono.empty());
                            })))
        .thenReturn(
            new RolePermissionsResponse(
                roleName, description, List.copyOf(new ArrayList<>(wanted))));
  }

  /**
   * Removing the permission that manages roles from a role you have yourself could leave nobody
   * able to undo it, so it must be done by someone else (or in the Keycloak console).
   */
  private Mono<Void> refuseSelfLockout(String roleName, List<String> toRemove) {
    if (!toRemove.contains(DwellPermissions.ROLE_WRITE)) {
      return Mono.empty();
    }
    return callerRealmRoles()
        .filter(roles -> roles.contains(roleName))
        .flatMap(
            roles ->
                Mono.error(
                    new ConflictException(
                        "You can't remove "
                            + DwellPermissions.ROLE_WRITE
                            + " from a role you have yourself",
                        HttpStatus.CONFLICT)));
  }

  private Mono<JwtAuthenticationToken> callerToken() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(ctx -> ctx.getAuthentication())
        .filter(JwtAuthenticationToken.class::isInstance)
        .cast(JwtAuthenticationToken.class);
  }

  private Mono<String> caller() {
    return callerToken().map(JwtAuthenticationToken::getName);
  }

  private Mono<Collection<String>> callerRealmRoles() {
    return callerToken()
        .map(
            auth -> {
              Object access = auth.getToken().getClaims().get("realm_access");
              if (access instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> r) {
                return (Collection<String>) r;
              }
              return List.<String>of();
            });
  }

  /** 404 → "Role not found" (instead of Keycloak's own message). */
  private Mono<Map> realmRole(String token, String roleName) {
    return keycloak
        .getMono(
            keycloakProperties.getRealmRoleByNameEndpoint(roleName),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .onErrorMap(
            e -> e instanceof AuthException ae && ae.getStatus() == HttpStatus.NOT_FOUND,
            e -> new AuthException("Role not found: " + roleName, HttpStatus.NOT_FOUND))
        .switchIfEmpty(
            Mono.error(new AuthException("Role not found: " + roleName, HttpStatus.NOT_FOUND)));
  }

  private Mono<List<String>> directPermissions(String token, String roleName, String clientUuid) {
    return keycloak
        .getFlux(
            keycloakProperties.getRoleClientCompositesEndpoint(roleName, clientUuid),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .map(r -> String.valueOf(r.get("name")))
        .sort()
        .collectList();
  }

  /** The permission client and its roles, i.e. every permission that exists. */
  private Mono<Catalog> permissionCatalog(String token) {
    return keycloak
        .getFlux(
            keycloakProperties.getClientByClientIdEndpoint(permissionClient),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .next()
        .switchIfEmpty(
            Mono.error(
                new AuthException(
                    "Permission client " + permissionClient + " not found in Keycloak",
                    HttpStatus.SERVICE_UNAVAILABLE)))
        .flatMap(
            client -> {
              String uuid = String.valueOf(client.get("id"));
              return keycloak
                  .getFlux(
                      keycloakProperties.getClientRolesEndpoint(uuid),
                      token,
                      Map.class,
                      RoleServiceImpl.AUTH_ERROR_FACTORY)
                  // The scope holder bundles every permission; it is not one itself
                  .filter(r -> !DwellPermissions.SCOPE_ROLE.equals(String.valueOf(r.get("name"))))
                  .collectSortedList(
                      (a, b) ->
                          String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))))
                  .map(roles -> new Catalog(uuid, roles));
            });
  }

  private boolean isBusinessRole(String name) {
    return !KEYCLOAK_BUILT_IN.contains(name)
        && !name.equals("default-roles-" + keycloakProperties.getRealm().toLowerCase());
  }

  private static String description(Map role) {
    Object d = role.get("description");
    return d == null ? null : String.valueOf(d);
  }

  /** Runs a Keycloak admin call, retrying once with a fresh admin token on 401. */
  private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
    return keycloakAdmin
        .getAccessTokenCache()
        .flatMap(call)
        .onErrorResume(
            e -> e instanceof AuthException ae && ae.getStatus() == HttpStatus.UNAUTHORIZED,
            e -> keycloakAdmin.forceRefresh().flatMap(call));
  }

  /** The permission client's roles, by name. */
  private record Catalog(String clientUuid, List<Map> roles) {

    Map<String, Map> byName() {
      Map<String, Map> m = new java.util.LinkedHashMap<>();
      roles.forEach(r -> m.put(String.valueOf(r.get("name")), r));
      return m;
    }

    List<PermissionResponse> responses() {
      return roles.stream()
          .map(r -> new PermissionResponse(String.valueOf(r.get("name")), description(r)))
          .toList();
    }

    /** What Keycloak needs to add or remove a composite: the role's id and name. */
    List<Map<String, Object>> representations(List<String> names) {
      Map<String, Map> all = byName();
      return names.stream()
          .map(n -> Map.<String, Object>of("id", all.get(n).get("id"), "name", n))
          .toList();
    }
  }
}
