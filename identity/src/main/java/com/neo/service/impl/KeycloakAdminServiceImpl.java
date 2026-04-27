package com.neo.service.impl;

import com.neo.config.KeycloakProperties;
import com.neo.exception.AuthException;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class KeycloakAdminServiceImpl {

  private static final String ACCESS_TOKEN_KEY = "keycloak:admin:access_token";
  private static final String REFRESH_TOKEN_KEY = "keycloak:admin:refresh_token";

  private final KeycloakProperties keycloakProperties;
  private final WebClient webClient;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public KeycloakAdminServiceImpl(
      KeycloakProperties keycloakProperties,
      WebClient webClient,
      ReactiveRedisTemplate<String, String> redisTemplate) {
    this.keycloakProperties = keycloakProperties;
    this.webClient = webClient;
    this.redisTemplate = redisTemplate;
  }

  // ─── Get Access Token From Redis ──────────────────────────
  public Mono<String> getAccessTokenCache() {
    return redisTemplate
        .opsForValue()
        .get(ACCESS_TOKEN_KEY)
        .doOnNext(t -> log.debug("Access token served from Redis"))
        .switchIfEmpty(
            tryRefreshTokenCache() // access token expired → try refresh token
            );
  }

  // ─── Get Refresh Token From Redis ──────────────────────────
  private Mono<String> tryRefreshTokenCache() {
    return redisTemplate
        .opsForValue()
        .get(REFRESH_TOKEN_KEY)
        .doOnNext(t -> log.debug("Refresh token found, requesting new access token"))
        .flatMap(this::fetchByRefreshToken) // use refresh token
        .switchIfEmpty(getRealmToken()) // no refresh token → full login
        .onErrorResume(
            e -> {
              log.warn("Refresh token failed: {}, fetching fresh tokens", e.getMessage());
              return getRealmToken(); // refresh token rejected → full login
            });
  }

  private Mono<String> fetchByRefreshToken(String refreshToken) {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "refresh_token");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());
    formData.add("refresh_token", refreshToken);

    return webClient
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
                                    "Refresh token rejected: " + body, HttpStatus.UNAUTHORIZED))))
        .bodyToMono(Map.class)
        .flatMap(this::cacheAndExtractAccessToken);
  }

  private Mono<String> fetchFreshTokens() {
    log.debug("Fetching fresh tokens from Keycloak");

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "client_credentials");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());

    return webClient
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
                                    "Failed to fetch tokens: " + body,
                                    HttpStatus.INTERNAL_SERVER_ERROR))))
        .bodyToMono(Map.class)
        .flatMap(this::cacheAndExtractAccessToken);
  }

  public Mono<String> getRealmToken() {

    log.debug("Getting fresh tokens from Keycloak");

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "client_credentials");
    formData.add("client_id", keycloakProperties.getClientId());
    formData.add("client_secret", keycloakProperties.getClientSecret());

    return webClient
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
                                    "Failed to fetch tokens: " + body,
                                    HttpStatus.INTERNAL_SERVER_ERROR))))
        .bodyToMono(Map.class)
        .flatMap(this::cacheAndExtractAccessToken);
  }

  private Mono<String> cacheAndExtractAccessToken(Map<?, ?> response) {
    if (response == null || !response.containsKey("access_token")) {
      return Mono.error(
          new AuthException("Token response is empty", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    String accessToken = (String) response.get("access_token");
    String refreshToken = (String) response.get("refresh_token");

    Duration accessTtl = Duration.ofSeconds(keycloakProperties.getTokenTtl());
    Duration refreshTtl = Duration.ofSeconds(keycloakProperties.getRefreshTokenTtl());

    // Cache access token
    Mono<Boolean> cacheAccess =
        redisTemplate.opsForValue().set(ACCESS_TOKEN_KEY, accessToken, accessTtl);

    // Cache refresh token only if present (client_credentials may not return one)
    Mono<Boolean> cacheRefresh =
        (refreshToken != null)
            ? redisTemplate.opsForValue().set(REFRESH_TOKEN_KEY, refreshToken, refreshTtl)
            : Mono.just(true);

    return Mono.when(cacheAccess, cacheRefresh)
        .doOnSuccess(v -> log.debug("Tokens cached successfully"))
        .thenReturn(accessToken);
  }

  // ─── Force refresh (call on 401 from downstream) ──────────────────────────
  public Mono<String> forceRefresh() {
    log.debug("Force refreshing tokens");
    return redisTemplate
        .delete(ACCESS_TOKEN_KEY) // clear access token only
        .then(getAccessTokenCache()); // will try refresh token first
  }

  // ─── Clear everything and re-login ────────────────────────
  public Mono<String> forceFullRefresh() {
    log.warn("Force full re-login, clearing all cached tokens");
    return redisTemplate.delete(ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY).then(fetchFreshTokens());
  }
}
