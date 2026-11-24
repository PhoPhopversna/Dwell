package com.neo.service.impl;

import com.neo.dto.AppUserRequest;
import com.neo.dto.TokenClaims;
import com.neo.dto.UserAuditRequest;
import com.neo.entity.AppUser;
import com.neo.entity.UserLogAudit;
import com.neo.exception.AuthException;
import com.neo.exception.ConflictException;
import com.neo.repository.AppUserRepository;
import com.neo.repository.UserLogAuditRepository;
import com.neo.service.UserService;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

  private final AppUserRepository appUserRepository;
  private final UserLogAuditRepository userLogAuditRepository;

  public UserServiceImpl(
      AppUserRepository appUserRepository, UserLogAuditRepository userLogAuditRepository) {
    this.appUserRepository = appUserRepository;
    this.userLogAuditRepository = userLogAuditRepository;
  }

  @Override
  public Mono<Void> saveUser(AppUserRequest appUserRequest, String action, String correlationId) {
    return appUserRepository
        .save(mapToAppUserEntity(appUserRequest))
        .onErrorResume(
            ex ->
                saveUserAuditLog(
                        new UserAuditRequest(null, action, false, ex.getMessage(), correlationId))
                    .then(Mono.error(ex)))
        .flatMap(
            saved ->
                saveUserAuditLog(
                    new UserAuditRequest(
                        saved.getId(), action, true, "Save User Successfully", correlationId)))
        .then();
  }

  @Override
  public Mono<Void> saveUserAuditLog(UserAuditRequest userAuditRequest) {
    return userLogAuditRepository
        .save(mapToUserLogAuditEntity(userAuditRequest))
        .doOnError(
            ex ->
                log.error(
                    "Failed to save audit log, correlationId={}",
                    userAuditRequest.getCorrelationId(),
                    ex))
        .onErrorComplete()
        .then();
  }

  @Override
  public Mono<Void> ensureAvailable(String username, String email) {
    Mono<Boolean> taken =
        appUserRepository
            .existsByUsernameIgnoreCase(username)
            .flatMap(
                byName ->
                    byName || email == null
                        ? Mono.just(byName)
                        : appUserRepository.existsByEmailIgnoreCase(email));
    return storeCall(taken)
        .flatMap(
            isTaken ->
                isTaken
                    ? Mono.error(
                        new ConflictException(
                            "User with this username or email already exists", HttpStatus.CONFLICT))
                    : Mono.<Void>empty());
  }

  @Override
  public Mono<AppUser> syncAuthenticatedUser(
      TokenClaims claims, String action, String correlationId) {
    return storeCall(findOrCreate(claims))
        .flatMap(
            user -> {
              if (Boolean.FALSE.equals(user.getActive())) {
                return saveUserAuditLog(
                        new UserAuditRequest(
                            user.getId(), action, false, "Account is disabled", correlationId))
                    .then(
                        Mono.error(
                            new AuthException(
                                "Account is disabled: " + user.getUsername(),
                                HttpStatus.FORBIDDEN)));
              }
              if (!"LOGIN".equals(action)) {
                return Mono.just(user);
              }
              LocalDateTime now = LocalDateTime.now();
              user.setLastLoginAt(now);
              return storeCall(appUserRepository.updateLastLogin(user.getId(), now))
                  .then(
                      saveUserAuditLog(
                          new UserAuditRequest(
                              user.getId(), action, true, "Login successful", correlationId)))
                  .thenReturn(user);
            });
  }

  @Override
  public Mono<Void> auditLogout(TokenClaims claims, String correlationId) {
    return appUserRepository
        .findByKeycloakId(claims.subject())
        .map(user -> Optional.ofNullable(user.getId()))
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            id ->
                saveUserAuditLog(
                    new UserAuditRequest(
                        id.orElse(null), "LOGOUT", true, "Logout successful", correlationId)))
        .onErrorResume(
            e -> {
              log.warn("Could not audit logout of {}: {}", claims.username(), e.getMessage());
              return Mono.empty();
            });
  }

  /** The row for this Keycloak user, created from the token if it is missing. */
  private Mono<AppUser> findOrCreate(TokenClaims claims) {
    return appUserRepository
        .findByKeycloakId(claims.subject())
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.warn(
                      "No app_user for Keycloak user {} ({}); creating it",
                      claims.subject(),
                      claims.username());
                  return appUserRepository
                      .save(
                          AppUser.builder()
                              .keycloakId(claims.subject())
                              .username(claims.username())
                              .email(claims.email())
                              .firstName(claims.firstName())
                              .lastName(claims.lastName())
                              .active(true)
                              .createdAt(LocalDateTime.now())
                              .createdBy("KEYCLOAK_SYNC")
                              .build())
                      // A concurrent login may have inserted it first
                      .onErrorResume(
                          DataIntegrityViolationException.class,
                          e ->
                              appUserRepository
                                  .findByKeycloakId(claims.subject())
                                  .switchIfEmpty(Mono.error(e)));
                }));
  }

  /** Database failures become 503, so auth routes never answer without the local user. */
  private static <T> Mono<T> storeCall(Mono<T> call) {
    return call.onErrorMap(
        e ->
            !(e instanceof AuthException
                || e instanceof ConflictException
                || e instanceof DataIntegrityViolationException),
        e ->
            new AuthException(
                "User store unavailable: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE, e));
  }

  private AppUser mapToAppUserEntity(AppUserRequest request) {
    return AppUser.builder()
        .username(request.getUsername())
        .email(request.getEmail())
        .active(request.getActive())
        .keycloakId(request.getKeycloakId())
        .firstName(request.getFirstName())
        .lastName(request.getLastName())
        .createdAt(LocalDateTime.now())
        .createdBy(request.getCreatedBy())
        .build();
  }

  private UserLogAudit mapToUserLogAuditEntity(UserAuditRequest request) {
    return UserLogAudit.builder()
        .userId(request.getUserId())
        .action(request.getAction())
        .success(request.getSuccess())
        .response(truncate(request.getResponse()))
        .correlationId(request.getCorrelationId())
        .createdAt(LocalDateTime.now())
        .build();
  }

  /** The audit response column is VARCHAR(255). */
  private static String truncate(String text) {
    return text == null || text.length() <= 255 ? text : text.substring(0, 252) + "...";
  }
}
