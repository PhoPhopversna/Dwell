package com.neo.bootstrap;

import com.neo.config.KeycloakProperties;
import com.neo.dto.CreateRoleRequest;
import com.neo.dto.RegisterRequest;
import com.neo.exception.ConflictException;
import com.neo.service.KeycloakAuthService;
import com.neo.service.RoleService;
import com.neo.service.impl.KeycloakAdminServiceImpl;
import com.neo.service.impl.KeycloakGenericConnector;
import com.neo.service.impl.RoleServiceImpl;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** Idempotent; failures are logged and never stop startup. */
@Slf4j
@Component
// Before KeycloakPermissionSync (order 100), which gives new permissions to the roles made here
@org.springframework.core.annotation.Order(10)
@RequiredArgsConstructor
@EnableConfigurationProperties(BootstrapProperties.class)
@ConditionalOnProperty(prefix = "app.bootstrap", name = "enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {

  private final BootstrapProperties properties;
  private final RoleService roleService;
  private final KeycloakAuthService keycloakAuthService;
  private final KeycloakAdminServiceImpl keycloakAdminService;
  private final KeycloakGenericConnector keycloakConnector;
  private final KeycloakProperties keycloakProperties;

  @Override
  public void run(ApplicationArguments args) {
    if (isBlank(properties.adminUsername()) || isBlank(properties.adminPassword())) {
      log.warn("Bootstrap enabled but app.bootstrap.admin-username/password not set; skipping");
      return;
    }
    try {
      ensureRoles()
          .then(ensureAdminUser())
          .then(findUserId(properties.adminUsername()))
          .flatMap(
              userId ->
                  roleService
                      .assignRolesToUser(userId, List.of(properties.adminRole()))
                      .thenReturn(userId))
          .doOnNext(
              userId ->
                  log.info(
                      "Bootstrap: user '{}' ({}) has realm role {}",
                      properties.adminUsername(),
                      userId,
                      properties.adminRole()))
          // Idempotent, so retry on transient failures (e.g. Keycloak still starting)
          .retryWhen(
              Retry.backoff(3, Duration.ofSeconds(2))
                  .doBeforeRetry(
                      r ->
                          log.warn(
                              "Bootstrap attempt {} failed: {}; retrying",
                              r.totalRetries() + 1,
                              r.failure().getMessage())))
          .block(Duration.ofSeconds(60));
    } catch (Exception e) {
      log.error("Bootstrap admin failed (service keeps running): {}", e.getMessage(), e);
    }
  }

  private Mono<Void> ensureRoles() {
    List<String> roles =
        properties.realmRoles().contains(properties.adminRole())
            ? properties.realmRoles()
            : concat(properties.realmRoles(), properties.adminRole());
    return Flux.fromIterable(roles)
        .concatMap(
            name -> {
              CreateRoleRequest request = new CreateRoleRequest();
              request.setName(name);
              request.setDescription("Created by identity bootstrap");
              return roleService
                  .createRole(request)
                  .doOnSuccess(v -> log.info("Bootstrap: created realm role {}", name))
                  .onErrorResume(ConflictException.class, e -> Mono.empty());
            })
        .then();
  }

  private Mono<Void> ensureAdminUser() {
    RegisterRequest request = new RegisterRequest();
    request.setUserName(properties.adminUsername());
    request.setEmail(properties.adminEmail());
    request.setFirstName("Admin");
    request.setLastName("Bootstrap");
    request.setEnabled(true);
    request.setCreatedBy("bootstrap");
    RegisterRequest.Credential credential = new RegisterRequest.Credential();
    credential.setType("password");
    credential.setValue(properties.adminPassword());
    credential.setTemporary(false);
    request.setCredentials(List.of(credential));
    // Only the admin role: not the self-registration defaults (e.g. CUSTOMER)
    return keycloakAuthService
        .register(request, List.of(properties.adminRole()))
        .doOnSuccess(v -> log.info("Bootstrap: created user {}", properties.adminUsername()))
        .onErrorResume(ConflictException.class, e -> Mono.empty());
  }

  @SuppressWarnings("rawtypes")
  private Mono<String> findUserId(String username) {
    String url =
        keycloakProperties.getUsersEndpoint()
            + "?exact=true&username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8);
    return keycloakAdminService
        .getAccessTokenCache()
        .flatMap(
            token ->
                keycloakConnector
                    .getFlux(url, token, Map.class, RoleServiceImpl.AUTH_ERROR_FACTORY)
                    .next())
        .map(user -> String.valueOf(((Map) user).get("id")))
        .switchIfEmpty(
            Mono.error(new IllegalStateException("User not found after register: " + username)));
  }

  private static List<String> concat(List<String> list, String extra) {
    return java.util.stream.Stream.concat(list.stream(), java.util.stream.Stream.of(extra))
        .toList();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
