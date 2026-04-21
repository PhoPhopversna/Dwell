package com.neo.service.impl;

import com.neo.config.KeycloakProperties;
import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenResponse;
import com.neo.exception.AuthException;
import com.neo.service.KeycloakAuthService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class KeycloakAuthServiceImpl implements KeycloakAuthService {

  private final KeycloakProperties keycloakProperties;
  private final WebClient webClient;

  public KeycloakAuthServiceImpl(KeycloakProperties keycloakProperties, WebClient webClient) {
    this.keycloakProperties = keycloakProperties;
    this.webClient = webClient;
  }

  @Override
  public TokenResponse login(String username, String password) {
    log.debug("Attempting login for user: {}", username);

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "password");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("username", username);
    formData.add("password", password);
    formData.add("scope", "openid profile email");

    return postToKeycloak(keycloakProperties.getTokenEndpoint(), formData);
  }

  @Override
  public TokenResponse refresh(String refreshToken) {
    log.debug("Refreshing token");

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "refresh_token");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("refresh_token", refreshToken);

    return postToKeycloak(keycloakProperties.getTokenEndpoint(), formData);
  }

  @Override
  public void logout(String refreshToken) {
    log.debug("Logging out user");

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("refresh_token", refreshToken);

    webClient
        .post()
        .uri(keycloakProperties.getLogoutEndpoint())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData(formData))
        .retrieve()
        .onStatus(
            status -> status.is4xxClientError() || status.is5xxServerError(),
            response ->
                response
                    .bodyToMono(String.class)
                    .flatMap(
                        body ->
                            Mono.error(
                                new AuthException(
                                    "Logout failed: " + body, HttpStatus.BAD_REQUEST))))
        .bodyToMono(Void.class)
        .block();
  }

  @Override
  public void register(RegisterRequest request) {
    String realmToken = getRealmToken();
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

    webClient
        .post()
        .uri(keycloakProperties.getRegisterEndpoint())
        .headers(h -> h.setBearerAuth(realmToken))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .onStatus(
            status -> status.is4xxClientError(),
            resp ->
                resp.bodyToMono(String.class)
                    .flatMap(
                        b -> {
                          log.info("Response status: {}, body: {}", resp.statusCode(), b);
                          return Mono.error(
                              new AuthException(
                                  "Failed to create role: " + b, HttpStatus.BAD_REQUEST));
                        }))
        .bodyToMono(Void.class)
        .block();
  }

  private TokenResponse postToKeycloak(String url, MultiValueMap<String, String> formData) {
    try {
      return webClient
          .post()
          .uri(url)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(BodyInserters.fromFormData(formData))
          .retrieve()
          .onStatus(
              status -> status.value() == 401,
              response ->
                  Mono.error(
                      new AuthException("Invalid username or password", HttpStatus.UNAUTHORIZED)))
          .onStatus(
              status -> status.is4xxClientError(),
              response ->
                  response
                      .bodyToMono(String.class)
                      .flatMap(
                          body -> {
                            log.error("Keycloak 4xx error: {}", body);
                            return Mono.error(
                                new AuthException("Authentication failed", HttpStatus.BAD_REQUEST));
                          }))
          .onStatus(
              status -> status.is5xxServerError(),
              response ->
                  Mono.error(
                      new AuthException("Keycloak server error", HttpStatus.SERVICE_UNAVAILABLE)))
          .bodyToMono(TokenResponse.class)
          .block();
    } catch (WebClientResponseException e) {
      log.error("Keycloak request failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
      throw new AuthException("Authentication service error", HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  // ── Realm Token ───────────────────────────────────────────────────────────

  /** Returns Realm Access Token */
  private String getRealmToken() {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "client_credentials");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());

    Map<?, ?> response =
        webClient
            .post()
            .uri(keycloakProperties.getRealmToken())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .onStatus(
                status -> status.is4xxClientError() || status.is5xxServerError(),
                resp ->
                    resp.bodyToMono(String.class)
                        .flatMap(
                            body ->
                                Mono.error(
                                    new AuthException(
                                        "Failed to obtain admin token: " + body,
                                        HttpStatus.INTERNAL_SERVER_ERROR))))
            .bodyToMono(Map.class)
            .block();

    if (response == null || !response.containsKey("access_token")) {
      throw new AuthException("Realm token response is empty", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return (String) response.get("access_token");
  }
}
