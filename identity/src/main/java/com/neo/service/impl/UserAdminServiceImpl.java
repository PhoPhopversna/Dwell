package com.neo.service.impl;

import com.neo.config.KeycloakProperties;
import com.neo.dto.UserAuditRequest;
import com.neo.exception.AuthException;
import com.neo.repository.AppUserRepository;
import com.neo.service.UserAdminService;
import com.neo.service.UserService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class UserAdminServiceImpl implements UserAdminService {

  private static final BiFunction<String, HttpStatus, RuntimeException> ERROR_FACTORY =
      AuthException::new;

  private final KeycloakProperties keycloakProperties;
  private final KeycloakGenericConnector keycloak;
  private final KeycloakAdminServiceImpl keycloakAdmin;
  private final AppUserRepository appUserRepository;
  private final UserService userService;

  public UserAdminServiceImpl(
      KeycloakProperties keycloakProperties,
      KeycloakGenericConnector keycloak,
      KeycloakAdminServiceImpl keycloakAdmin,
      AppUserRepository appUserRepository,
      UserService userService) {
    this.keycloakProperties = keycloakProperties;
    this.keycloak = keycloak;
    this.keycloakAdmin = keycloakAdmin;
    this.appUserRepository = appUserRepository;
    this.userService = userService;
  }

  @Override
  public Mono<Void> setActive(String keycloakId, boolean active) {
    if (!isUuid(keycloakId)) {
      return Mono.error(
          new AuthException("User id is not a Keycloak id: " + keycloakId, HttpStatus.BAD_REQUEST));
    }
    String action = active ? "ACTIVATE" : "DEACTIVATE";
    String userUrl = keycloakProperties.getUserByIdEndpoint(keycloakId);
    // Keycloak first: if a later step fails the user is already blocked, and a retry is safe
    Mono<Void> keycloakSteps =
        withAdminToken(
                token ->
                    keycloak.putMono(
                        userUrl,
                        MediaType.APPLICATION_JSON,
                        BodyInserters.fromValue(Map.of("enabled", active)),
                        token,
                        Void.class,
                        ERROR_FACTORY))
            .then(
                active
                    ? Mono.empty()
                    : withAdminToken(
                            token ->
                                keycloak.postMono(
                                    userUrl + "/logout",
                                    MediaType.APPLICATION_JSON,
                                    BodyInserters.empty(),
                                    token,
                                    Void.class,
                                    ERROR_FACTORY))
                        .then());

    return Mono.deferContextual(
        ctx -> {
          String correlationId = ctx.getOrDefault("correlationId", UUID.randomUUID().toString());
          return refuseSelfDeactivation(keycloakId, active)
              .then(keycloakSteps)
              .then(
                  Mono.defer(
                      () ->
                          appUserRepository
                              .updateActive(keycloakId, active)
                              .onErrorMap(
                                  e ->
                                      new AuthException(
                                          "User store unavailable: " + e.getMessage(),
                                          HttpStatus.SERVICE_UNAVAILABLE,
                                          e))))
              .flatMap(
                  rows -> {
                    if (rows == 0) {
                      log.warn("{}: Keycloak user {} has no app_user row yet", action, keycloakId);
                    }
                    log.info("{} user {}", action, keycloakId);
                    return appUserRepository
                        .findByKeycloakId(keycloakId)
                        .map(user -> Optional.ofNullable(user.getId()))
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(
                            id ->
                                userService.saveUserAuditLog(
                                    new UserAuditRequest(
                                        id.orElse(null),
                                        action,
                                        true,
                                        "Keycloak user " + keycloakId,
                                        correlationId)))
                        .onErrorResume(e -> Mono.empty());
                  });
        });
  }

  /** An admin locking themselves out would leave nobody able to undo it. */
  private Mono<Void> refuseSelfDeactivation(String keycloakId, boolean active) {
    if (active) {
      return Mono.empty();
    }
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(ctx -> ctx.getAuthentication())
        .filter(JwtAuthenticationToken.class::isInstance)
        .map(auth -> ((JwtAuthenticationToken) auth).getToken().getSubject())
        .filter(keycloakId::equals)
        .flatMap(
            self ->
                Mono.error(
                    new AuthException(
                        "Admin tried to deactivate their own account", HttpStatus.CONFLICT)));
  }

  /** Runs a Keycloak admin call, retrying once with a fresh admin token on 401. */
  private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
    return keycloakAdmin
        .getAccessTokenCache()
        .flatMap(call)
        .onErrorResume(
            AuthException.class,
            e ->
                e.getStatus() == HttpStatus.UNAUTHORIZED
                    ? keycloakAdmin.forceRefresh().flatMap(call)
                    : Mono.error(e));
  }

  private static boolean isUuid(String value) {
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
