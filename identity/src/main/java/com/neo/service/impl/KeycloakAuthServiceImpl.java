package com.neo.service.impl;

import com.neo.config.KeycloakProperties;
import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenResponse;
import com.neo.exception.AuthException;
import com.neo.service.KeycloakAuthService;
import java.util.Map;
import java.util.function.BiFunction;
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

  public static final BiFunction<String, HttpStatus, RuntimeException> AUTH_ERROR_FACTORY =
      (msg, status) -> new AuthException(msg, status);

  public KeycloakAuthServiceImpl(
      KeycloakProperties keycloakProperties,
      WebClient webClient,
      KeycloakGenericConnector keycloakGenericConnector,
      KeycloakAdminServiceImpl keycloakAdminService) {
    this.keycloakProperties = keycloakProperties;
    this.webClient = webClient;
    this.keycloakGenericConnector = keycloakGenericConnector;
    this.keycloakAdminService = keycloakAdminService;
  }

  @Override
  public Mono<TokenResponse> login(String username, String password) {
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

  @Override
  public Mono<TokenResponse> refresh(String refreshToken) {
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

  @Override
  public Mono<Void> logout(String refreshToken) {
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
    log.debug("Create User Request : {}", request);

    Map<String, Object> body =
        Map.of(
            "username", request.getUsername(),
            "email", request.getEmail(),
            "firstName", request.getFirstName(),
            "lastName", request.getLastName(),
            "enabled", ENABLE,
            "credentials",
                request.getCredentials().stream()
                    .map(
                        c ->
                            Map.of(
                                "type", c.getType(),
                                "value", c.getValue(),
                                "temporary", c.getTemporary()))
                    .toList());
    return keycloakAdminService
        .getAccessTokenCache()
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
            });
  }

  private Mono<Void> keycloakUserRegister(String token, Map<String, Object> body) {
    return webClient
        .post()
        .uri(keycloakProperties.getRegisterEndpoint())
        .headers(h -> h.setBearerAuth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .onStatus(
            status -> status.is4xxClientError(),
            response ->
                response
                    .bodyToMono(String.class)
                    .flatMap(
                        errorBody -> {
                          log.error("Keycloak 4xx error: {}", errorBody);
                          return Mono.error(
                              new AuthException("Authentication failed", HttpStatus.BAD_REQUEST));
                        }))
        .bodyToMono(Void.class);
  }
}
