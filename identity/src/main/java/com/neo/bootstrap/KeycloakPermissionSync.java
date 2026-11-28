package com.neo.bootstrap;

import com.neo.common.security.DwellJwtProperties;
import com.neo.common.security.DwellPermissions;
import com.neo.config.KeycloakProperties;
import com.neo.exception.AuthException;
import com.neo.service.impl.KeycloakAdminServiceImpl;
import com.neo.service.impl.KeycloakGenericConnector;
import com.neo.service.impl.RoleServiceImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Makes Keycloak match the code's permission list ({@link DwellPermissions}) at startup, so a new
 * permission needs no Keycloak step in any environment:
 *
 * <ol>
 *   <li>creates the scope holder role ({@link DwellPermissions#SCOPE_ROLE}) if missing;
 *   <li>creates every permission that's missing, with its description;
 *   <li>bundles every permission client role into the holder (also ones created by hand), so they
 *       all reach tokens: the holder is in the login client's scope;
 *   <li>gives a newly created permission to the roles in {@code initial-grants}.
 * </ol>
 *
 * It never deletes or renames anything, and never touches the login client: putting the holder in
 * its scope is a one-time setup step (WARN if missing). Needs "manage" on the permission client for
 * identity's admin client (docs/keycloak-setup.md, step 5.6). Failures are logged; startup goes on.
 * Runs after the admin bootstrap, which creates the default roles.
 */
@Slf4j
@Component
@Order(100)
@EnableConfigurationProperties(PermissionSyncProperties.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public class KeycloakPermissionSync implements ApplicationRunner {

  private final KeycloakProperties keycloakProperties;
  private final KeycloakAdminServiceImpl keycloakAdmin;
  private final KeycloakGenericConnector keycloak;
  private final PermissionSyncProperties properties;
  private final String permissionClient;

  public KeycloakPermissionSync(
      KeycloakProperties keycloakProperties,
      KeycloakAdminServiceImpl keycloakAdmin,
      KeycloakGenericConnector keycloak,
      PermissionSyncProperties properties,
      DwellJwtProperties jwtProperties) {
    this.keycloakProperties = keycloakProperties;
    this.keycloakAdmin = keycloakAdmin;
    this.keycloak = keycloak;
    this.properties = properties;
    this.permissionClient = jwtProperties.permissionClient();
  }

  /** What the sync did and found; logged at startup. */
  record Report(List<String> created, List<String> addedToScope, List<String> problems) {}

  @Override
  public void run(ApplicationArguments args) {
    try {
      Report r = sync().block(Duration.ofSeconds(60));
      if (r == null) {
        return;
      }
      if (!r.created().isEmpty() || !r.addedToScope().isEmpty()) {
        log.info(
            "Keycloak permissions synced: created {}, added to token scope {}",
            r.created(),
            r.addedToScope());
      }
      if (r.problems().isEmpty()) {
        log.info("Keycloak permissions check passed ({} permissions)", DwellPermissions.ALL.size());
      } else {
        log.warn(
            "Keycloak permissions incomplete, users will get 403: {}",
            String.join("; ", r.problems()));
      }
    } catch (Exception e) {
      log.warn("Could not sync Keycloak permissions: {}", e.getMessage());
    }
  }

  Mono<Report> sync() {
    return withAdminToken(this::sync);
  }

  private Mono<Report> sync(String token) {
    return Mono.zip(
            clientUuid(token, permissionClient),
            clientUuid(token, keycloakProperties.getClientId()))
        .flatMap(ids -> sync(token, ids.getT1(), ids.getT2()))
        .switchIfEmpty(
            Mono.just(
                new Report(
                    List.of(),
                    List.of(),
                    List.of(
                        "client "
                            + permissionClient
                            + " or "
                            + keycloakProperties.getClientId()
                            + " not found (docs/keycloak-setup.md)"))));
  }

  private Mono<Report> sync(String token, String api, String login) {
    List<String> created = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    return roles(token, api)
        .flatMap(
            existing -> {
              List<String> toCreate = new ArrayList<>();
              if (!existing.containsKey(DwellPermissions.SCOPE_ROLE)) {
                toCreate.add(DwellPermissions.SCOPE_ROLE);
              }
              DwellPermissions.ALL.stream()
                  .filter(p -> !existing.containsKey(p))
                  .forEach(toCreate::add);
              if (!properties.enabled()) {
                toCreate.stream()
                    .filter(p -> !p.equals(DwellPermissions.SCOPE_ROLE))
                    .forEach(
                        p ->
                            problems.add(
                                "not defined as a role of " + permissionClient + ": " + p));
                return Mono.just(existing);
              }
              return Flux.fromIterable(toCreate)
                  .concatMap(
                      name -> createRole(token, api, name).doOnSuccess(v -> created.add(name)))
                  .then(Mono.defer(() -> roles(token, api)));
            })
        .flatMap(
            all ->
                properties.enabled()
                    ? fillHolder(token, api, all)
                        .flatMap(
                            added ->
                                grantNew(token, all, created, problems)
                                    .then(checkScope(token, login, api, all, problems))
                                    .thenReturn(added))
                    : checkScope(token, login, api, all, problems).thenReturn(List.<String>of()))
        .map(
            added ->
                new Report(
                    created.stream().filter(n -> !n.equals(DwellPermissions.SCOPE_ROLE)).toList(),
                    added,
                    problems));
  }

  /** Bundles every permission client role (except the holder itself) into the holder. */
  private Mono<List<String>> fillHolder(String token, String api, Map<String, Map> all) {
    return keycloak
        .getFlux(
            keycloakProperties.getClientRoleCompositesEndpoint(api, DwellPermissions.SCOPE_ROLE),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .map(r -> String.valueOf(r.get("name")))
        .collect(Collectors.toSet())
        .flatMap(
            inHolder -> {
              List<Map> missing =
                  all.entrySet().stream()
                      .filter(e -> !e.getKey().equals(DwellPermissions.SCOPE_ROLE))
                      .filter(e -> !inHolder.contains(e.getKey()))
                      .map(e -> ref(e.getValue()))
                      .toList();
              if (missing.isEmpty()) {
                return Mono.just(List.<String>of());
              }
              return keycloak
                  .postMono(
                      keycloakProperties.getClientRoleCompositesEndpoint(
                          api, DwellPermissions.SCOPE_ROLE),
                      MediaType.APPLICATION_JSON,
                      BodyInserters.fromValue(missing),
                      token,
                      Void.class,
                      RoleServiceImpl.AUTH_ERROR_FACTORY)
                  .then(
                      Mono.just(
                          missing.stream()
                              .map(m -> String.valueOf(m.get("name")))
                              .sorted()
                              .toList()));
            });
  }

  /** Gives permissions identity just created to the roles configured for them. */
  private Mono<Void> grantNew(
      String token, Map<String, Map> all, List<String> created, List<String> problems) {
    return Flux.fromIterable(properties.initialGrants().keySet())
        .concatMap(
            role -> {
              List<Map> grant =
                  created.stream()
                      .filter(p -> !p.equals(DwellPermissions.SCOPE_ROLE))
                      .filter(p -> properties.grants(role, p))
                      .map(p -> ref(all.get(p)))
                      .toList();
              if (grant.isEmpty()) {
                return Mono.empty();
              }
              return keycloak
                  .postMono(
                      keycloakProperties.getRoleCompositesEndpoint(role),
                      MediaType.APPLICATION_JSON,
                      BodyInserters.fromValue(grant),
                      token,
                      Void.class,
                      RoleServiceImpl.AUTH_ERROR_FACTORY)
                  .doOnSuccess(
                      v ->
                          log.info(
                              "Gave role {} the new permissions {}",
                              role,
                              grant.stream().map(g -> g.get("name")).toList()))
                  .then()
                  .onErrorResume(
                      e -> {
                        problems.add(
                            "could not give role "
                                + role
                                + " its new permissions: "
                                + e.getMessage());
                        return Mono.empty();
                      });
            })
        .then();
  }

  /** The holder (or, for older setups, every permission) must be in the login client's scope. */
  private Mono<Void> checkScope(
      String token, String login, String api, Map<String, Map> all, List<String> problems) {
    return keycloak
        .getFlux(
            keycloakProperties.getClientScopeMappingsEndpoint(login, api),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .map(r -> String.valueOf(r.get("name")))
        .collect(Collectors.toSet())
        .doOnNext(
            scope -> {
              List<String> notDefined =
                  DwellPermissions.ALL.stream().filter(p -> !all.containsKey(p)).toList();
              if (!notDefined.isEmpty()) {
                problems.add("not defined as roles of " + permissionClient + ": " + notDefined);
              }
              if (!scope.contains(DwellPermissions.SCOPE_ROLE)) {
                List<String> notInScope =
                    DwellPermissions.ALL.stream()
                        .filter(all::containsKey)
                        .filter(p -> !scope.contains(p))
                        .toList();
                if (!notInScope.isEmpty()) {
                  problems.add(
                      DwellPermissions.SCOPE_ROLE
                          + " is not in the scope of "
                          + keycloakProperties.getClientId()
                          + ", so these never reach tokens: "
                          + notInScope
                          + " (one-time setup: docs/keycloak-setup.md, step 4.4)");
                }
              }
            })
        .then();
  }

  private Mono<Void> createRole(String token, String api, String name) {
    String description =
        name.equals(DwellPermissions.SCOPE_ROLE)
            ? "Not a permission: bundles every permission so they reach tokens. Never assign it."
            : DwellPermissions.DESCRIPTIONS.get(name);
    return keycloak
        .postMono(
            keycloakProperties.getClientRolesEndpoint(api),
            MediaType.APPLICATION_JSON,
            BodyInserters.fromValue(Map.of("name", name, "description", description)),
            token,
            Void.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .then()
        .doOnSuccess(v -> log.info("Created permission client role {}", name));
  }

  private Mono<Map<String, Map>> roles(String token, String api) {
    return keycloak
        .getFlux(
            keycloakProperties.getClientRolesEndpoint(api),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .collectMap(r -> String.valueOf(r.get("name")), r -> (Map) r, LinkedHashMap::new);
  }

  private Mono<String> clientUuid(String token, String clientId) {
    return keycloak
        .getFlux(
            keycloakProperties.getClientByClientIdEndpoint(clientId),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .next()
        .map(c -> String.valueOf(c.get("id")));
  }

  private static Map ref(Map role) {
    return Map.of("id", role.get("id"), "name", role.get("name"));
  }

  /** 403 → a clear hint: identity's admin client lacks the "manage" right on the client. */
  private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
    return keycloakAdmin
        .getAccessTokenCache()
        .flatMap(call)
        .onErrorResume(
            e -> e instanceof AuthException ae && ae.getStatus() == HttpStatus.UNAUTHORIZED,
            e -> keycloakAdmin.forceRefresh().flatMap(call))
        .onErrorMap(
            e -> e instanceof AuthException ae && ae.getStatus() == HttpStatus.FORBIDDEN,
            e ->
                new IllegalStateException(
                    "Keycloak refused (403): identity's admin client needs 'manage' on client "
                        + permissionClient
                        + " (docs/keycloak-setup.md, step 5.6)"));
  }

  static Set<String> names(List<Map> roles) {
    return roles.stream().map(r -> String.valueOf(r.get("name"))).collect(Collectors.toSet());
  }
}
