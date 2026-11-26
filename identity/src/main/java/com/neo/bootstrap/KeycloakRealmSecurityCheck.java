package com.neo.bootstrap;

import com.neo.config.KeycloakProperties;
import com.neo.exception.AuthException;
import com.neo.service.impl.KeycloakAdminServiceImpl;
import com.neo.service.impl.KeycloakGenericConnector;
import com.neo.service.impl.RoleServiceImpl;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Logs an ERROR at startup if the realm lets ordinary users administer it. Read-only; never stops
 * startup. Fix steps: docs/services/identity.md#keycloak-realm-security.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.keycloak.security-check",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class KeycloakRealmSecurityCheck implements ApplicationRunner {

  private final KeycloakProperties keycloakProperties;
  private final KeycloakAdminServiceImpl keycloakAdminService;
  private final KeycloakGenericConnector keycloak;

  @Override
  public void run(ApplicationArguments args) {
    try {
      List<String> problems = findProblems().block(Duration.ofSeconds(30));
      if (problems == null || problems.isEmpty()) {
        log.info("Keycloak realm {} security check passed", keycloakProperties.getRealm());
      } else {
        log.error(
            "Keycloak realm {} is insecure: {}",
            keycloakProperties.getRealm(),
            String.join("; ", problems));
      }
    } catch (Exception e) {
      log.warn("Could not verify Keycloak realm security: {}", e.getMessage());
    }
  }

  Mono<List<String>> findProblems() {
    return keycloakAdminService
        .getAccessTokenCache()
        .flatMap(this::findProblems)
        // The cached admin token may have been revoked (e.g. all sessions ended): retry once
        .onErrorResume(
            e -> e instanceof AuthException ae && ae.getStatus() == HttpStatus.UNAUTHORIZED,
            e -> keycloakAdminService.forceRefresh().flatMap(this::findProblems));
  }

  private Mono<List<String>> findProblems(String token) {
    return Mono.zip(
            adminRolesGivenToEveryone(token),
            passwordLoginOnAdminCli(token),
            loginClientProblems(token))
        .map(
            found -> {
              List<String> problems = new ArrayList<>(found.getT1());
              problems.addAll(found.getT2());
              problems.addAll(found.getT3());
              return problems;
            });
  }

  /** realm-management roles inside the realm's default role: every user gets them. */
  @SuppressWarnings("rawtypes")
  private Mono<List<String>> adminRolesGivenToEveryone(String token) {
    String realmUrl = realmUrl();
    Mono<String> defaultRole =
        keycloak
            .getMono(realmUrl, token, Map.class, RoleServiceImpl.AUTH_ERROR_FACTORY)
            .map(realm -> String.valueOf(((Map) realm.get("defaultRole")).get("name")));
    Mono<String> realmManagementId =
        keycloak
            .getMono(
                realmUrl + "/clients?clientId=realm-management",
                token,
                Map.class,
                RoleServiceImpl.AUTH_ERROR_FACTORY)
            .map(client -> String.valueOf(client.get("id")));
    return Mono.zip(defaultRole, realmManagementId)
        .flatMap(
            ids ->
                keycloak
                    .getFlux(
                        realmUrl
                            + "/roles/"
                            + URLEncoder.encode(ids.getT1(), StandardCharsets.UTF_8)
                            + "/composites/clients/"
                            + ids.getT2(),
                        token,
                        Map.class,
                        RoleServiceImpl.AUTH_ERROR_FACTORY)
                    .map(role -> String.valueOf(role.get("name")))
                    .collectList())
        .map(
            roles ->
                roles.isEmpty()
                    ? List.of()
                    : List.of(
                        "the default roles give every user realm-management "
                            + roles
                            + " (they can administer the realm through Keycloak's admin API)"));
  }

  /** The realm's public admin-cli client must not accept a username and password. */
  private Mono<List<String>> passwordLoginOnAdminCli(String token) {
    return keycloak
        .getFlux(
            realmUrl() + "/clients?clientId=admin-cli",
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .filter(
            client ->
                Boolean.TRUE.equals(client.get("enabled"))
                    && Boolean.TRUE.equals(client.get("publicClient"))
                    && Boolean.TRUE.equals(client.get("directAccessGrantsEnabled")))
        .map(
            client ->
                "the public client admin-cli accepts passwords directly (any user can get a"
                    + " token from it)")
        .collectList();
  }

  /**
   * The login client must have full scope off (a user's tokens would carry every role they have,
   * admin roles included) and no service account (that was the old admin client).
   */
  private Mono<List<String>> loginClientProblems(String token) {
    String clientId = keycloakProperties.getClientId();
    return keycloak
        .getFlux(
            realmUrl() + "/clients?clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8),
            token,
            Map.class,
            RoleServiceImpl.AUTH_ERROR_FACTORY)
        .flatMapIterable(
            client -> {
              List<String> problems = new ArrayList<>();
              if (Boolean.TRUE.equals(client.get("fullScopeAllowed"))) {
                problems.add(
                    "the login client "
                        + clientId
                        + " has full scope (an admin role given to a user by mistake ends up in"
                        + " their tokens)");
              }
              if (Boolean.TRUE.equals(client.get("serviceAccountsEnabled"))) {
                problems.add(
                    "the login client "
                        + clientId
                        + " still has a service account (turn it off; admin calls use "
                        + keycloakProperties.getServiceClientId()
                        + ")");
              }
              return problems;
            })
        .collectList();
  }

  private String realmUrl() {
    return keycloakProperties.getUrl() + "/admin/realms/" + keycloakProperties.getRealm();
  }
}
