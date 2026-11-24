package com.neo.service.impl;

import com.neo.config.KeycloakProperties;
import com.neo.dto.AppUserRequest;
import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenClaims;
import com.neo.dto.TokenResponse;
import com.neo.dto.UserAuditRequest;
import com.neo.exception.AuthException;
import com.neo.exception.ConflictException;
import com.neo.service.KeycloakAuthService;
import com.neo.service.UserService;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class KeycloakAuthServiceImpl implements KeycloakAuthService {

  private final KeycloakProperties keycloakProperties;
  private final WebClient webClient;

  private final KeycloakGenericConnector keycloakGenericConnector;
  private final KeycloakAdminServiceImpl keycloakAdminService;

  private final UserService userService;

  private static final String CORRELATION_ID_MDC_KEY = "correlationId";

  public static final BiFunction<String, HttpStatus, RuntimeException> AUTH_ERROR_FACTORY =
      (msg, status) -> new AuthException(msg, status);

  public KeycloakAuthServiceImpl(
      KeycloakProperties keycloakProperties,
      WebClient webClient,
      KeycloakGenericConnector keycloakGenericConnector,
      KeycloakAdminServiceImpl keycloakAdminService,
      UserService userService) {
    this.keycloakProperties = keycloakProperties;
    this.webClient = webClient;
    this.keycloakGenericConnector = keycloakGenericConnector;
    this.keycloakAdminService = keycloakAdminService;
    this.userService = userService;
  }

  @Override
  public Mono<TokenResponse> login(String username, String password) {
    return withCorrelationId(
        correlationId ->
            requestLoginTokens(username, password)
                .onErrorResume(
                    AuthException.class,
                    e ->
                        userService
                            .saveUserAuditLog(
                                new UserAuditRequest(
                                    null,
                                    "LOGIN",
                                    false,
                                    "Login failed for " + username + ": " + e.getMessage(),
                                    correlationId))
                            .then(Mono.error(e)))
                .flatMap(tokens -> syncUser(tokens, "LOGIN", correlationId)));
  }

  @Override
  public Mono<TokenResponse> refresh(String refreshToken) {
    return withCorrelationId(
        correlationId ->
            requestRefreshedTokens(refreshToken)
                .flatMap(tokens -> syncUser(tokens, "REFRESH", correlationId)));
  }

  @Override
  public Mono<Void> logout(String refreshToken) {
    return withCorrelationId(
        correlationId ->
            revokeSession(refreshToken)
                .then(Mono.defer(() -> auditLogout(refreshToken, correlationId))));
  }

  /** Tokens are only returned once the local user is in sync; otherwise the session is revoked. */
  private Mono<TokenResponse> syncUser(TokenResponse tokens, String action, String correlationId) {
    return Mono.fromCallable(() -> TokenClaims.fromJwt(tokens.getAccessToken()))
        .flatMap(claims -> userService.syncAuthenticatedUser(claims, action, correlationId))
        .thenReturn(tokens)
        .onErrorResume(e -> revokeQuietly(tokens.getRefreshToken()).then(Mono.error(e)));
  }

  private Mono<Void> revokeQuietly(String refreshToken) {
    if (refreshToken == null) {
      return Mono.empty();
    }
    return revokeSession(refreshToken)
        .onErrorResume(
            e -> {
              log.warn("Could not revoke Keycloak session: {}", e.getMessage());
              return Mono.empty();
            });
  }

  private Mono<Void> auditLogout(String refreshToken, String correlationId) {
    try {
      return userService.auditLogout(TokenClaims.fromJwt(refreshToken), correlationId);
    } catch (IllegalArgumentException e) {
      log.warn("Logout succeeded but the refresh token is unreadable: {}", e.getMessage());
      return Mono.empty();
    }
  }

  private static <T> Mono<T> withCorrelationId(Function<String, Mono<T>> body) {
    return Mono.deferContextual(
        ctx -> body.apply(ctx.getOrDefault(CORRELATION_ID_MDC_KEY, UUID.randomUUID().toString())));
  }

  private Mono<TokenResponse> requestLoginTokens(String username, String password) {
    log.debug("Attempting login for user: {}", username);

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "password");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("username", username);
    formData.add("password", password);
    formData.add("scope", "openid profile email");

    return keycloakGenericConnector.postMono(
        keycloakProperties.getTokenEndpoint(),
        MediaType.APPLICATION_FORM_URLENCODED,
        BodyInserters.fromFormData(formData),
        null,
        TokenResponse.class,
        AUTH_ERROR_FACTORY);
  }

  private Mono<TokenResponse> requestRefreshedTokens(String refreshToken) {
    log.debug("Refreshing token");

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "refresh_token");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("refresh_token", refreshToken);

    return keycloakGenericConnector.postMono(
        keycloakProperties.getTokenEndpoint(),
        MediaType.APPLICATION_FORM_URLENCODED,
        BodyInserters.fromFormData(formData),
        null,
        TokenResponse.class,
        AUTH_ERROR_FACTORY);
  }

  private Mono<Void> revokeSession(String refreshToken) {
    log.debug("Logging out user");

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("refresh_token", refreshToken);

    return keycloakGenericConnector.postMono(
        keycloakProperties.getLogoutEndpoint(),
        MediaType.APPLICATION_FORM_URLENCODED,
        BodyInserters.fromFormData(formData),
        null,
        Void.class,
        AUTH_ERROR_FACTORY);
  }

  @Override
  public Mono<Void> register(RegisterRequest request) {
    final boolean ENABLE = true;
    log.debug("Registering user {}", request.getUserName());

    // Optional fields (first/last name, temporary) may be absent: Map.of() rejects nulls.
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("username", request.getUserName());
    body.put("email", request.getEmail());
    if (request.getFirstName() != null) {
      body.put("firstName", request.getFirstName());
    }
    if (request.getLastName() != null) {
      body.put("lastName", request.getLastName());
    }
    body.put("enabled", ENABLE);
    body.put(
        "credentials",
        request.getCredentials().stream()
            .map(
                c ->
                    Map.of(
                        "type", c.getType(),
                        "value", c.getValue(),
                        "temporary", Boolean.TRUE.equals(c.getTemporary())))
            .toList());
    return Mono.deferContextual(
        ctx -> {
          String correlationId =
              ctx.getOrDefault(CORRELATION_ID_MDC_KEY, UUID.randomUUID().toString());
          return userService
              .ensureAvailable(request.getUserName(), request.getEmail())
              .then(Mono.defer(keycloakAdminService::getAccessTokenCache))
              .flatMap(token -> keycloakUserRegister(token, body))
              .onErrorResume(
                  AuthException.class,
                  e -> {
                    if (e.getStatus() == HttpStatus.UNAUTHORIZED) {
                      // 401 → force refresh and retry once
                      return keycloakAdminService
                          .forceRefresh()
                          .flatMap(newToken -> keycloakUserRegister(newToken, body));
                    }
                    return Mono.error(e);
                  })
              .onErrorResume(
                  ex ->
                      userService
                          .saveUserAuditLog(
                              new UserAuditRequest(
                                  null,
                                  "REGISTER",
                                  false,
                                  "Register failed: " + ex.getMessage(),
                                  correlationId))
                          .then(Mono.error(ex)))
              .flatMap(
                  keycloakId -> {
                    AppUserRequest appUserRequest =
                        AppUserRequest.builder()
                            .keycloakId(keycloakId)
                            .username(request.getUserName())
                            .email(request.getEmail())
                            .active(ENABLE)
                            .firstName(request.getFirstName())
                            .lastName(request.getLastName())
                            .createdBy(
                                request.getCreatedBy() != null
                                    ? request.getCreatedBy()
                                    : "SELF_REGISTER")
                            .build();
                    return userService
                        .saveUser(appUserRequest, "REGISTER", correlationId)
                        .onErrorResume(
                            e ->
                                rollbackKeycloakUser(
                                        keycloakId, request.getUserName(), correlationId)
                                    .then(Mono.error(e)));
                  });
        });
  }

  /** Deletes the Keycloak user whose local row could not be saved; 404 means already gone. */
  private Mono<Void> rollbackKeycloakUser(
      String keycloakId, String username, String correlationId) {
    String url = keycloakProperties.getUserByIdEndpoint(keycloakId);
    return keycloakAdminService
        .getAccessTokenCache()
        .flatMap(token -> keycloakGenericConnector.delete(url, token, AUTH_ERROR_FACTORY))
        .onErrorResume(
            AuthException.class,
            e ->
                e.getStatus() == HttpStatus.UNAUTHORIZED
                    ? keycloakAdminService
                        .forceRefresh()
                        .flatMap(
                            token ->
                                keycloakGenericConnector.delete(url, token, AUTH_ERROR_FACTORY))
                    : Mono.error(e))
        .onErrorResume(
            e -> e instanceof AuthException ae && ae.getStatus() == HttpStatus.NOT_FOUND,
            e -> Mono.empty())
        .then(
            Mono.defer(
                () -> {
                  log.warn(
                      "Rolled back Keycloak user {} ({}) after the local save failed",
                      keycloakId,
                      username);
                  return userService.saveUserAuditLog(
                      new UserAuditRequest(
                          null,
                          "REGISTER_ROLLBACK",
                          true,
                          "Deleted Keycloak user " + keycloakId,
                          correlationId));
                }))
        .onErrorResume(
            e -> {
              log.error(
                  "Could not roll back Keycloak user {} ({}); delete it manually",
                  keycloakId,
                  username,
                  e);
              return userService.saveUserAuditLog(
                  new UserAuditRequest(
                      null,
                      "REGISTER_ROLLBACK_FAILED",
                      false,
                      "Keycloak user " + keycloakId + " left behind",
                      correlationId));
            });
  }

  private Mono<String> keycloakUserRegister(String token, Map<String, Object> body) {
    return webClient
        .post()
        .uri(keycloakProperties.getRegisterEndpoint())
        .headers(h -> h.setBearerAuth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .onStatus(
            status -> status.value() == 409,
            response ->
                response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(
                        errorBody -> {
                          log.warn("Keycloak 409 on register: {}", errorBody);
                          return Mono.error(
                              new ConflictException(
                                  "User with this username or email already exists",
                                  HttpStatus.CONFLICT));
                        }))
        .onStatus(
            status -> status.is4xxClientError(),
            response ->
                response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(
                        errorBody -> {
                          log.warn(
                              "Keycloak {} on register: {}",
                              response.statusCode().value(),
                              errorBody);
                          return Mono.error(
                              new AuthException("Authentication failed", HttpStatus.BAD_REQUEST));
                        }))
        .toBodilessEntity()
        .map(
            response -> {
              URI location = response.getHeaders().getLocation();
              log.info("Keycloak Response : {}", location);
              if (location == null) {
                log.error("Keycloak did not return Location header {}", location);
                throw new AuthException(
                    "Keycloak did not return Location header", HttpStatus.INTERNAL_SERVER_ERROR);
              }
              String path = location.getPath();
              return path.substring(path.lastIndexOf('/') + 1);
            });
  }
}
