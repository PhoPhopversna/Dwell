package com.neo.service.impl;

import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class KeycloakGenericConnector {

  private final WebClient webClient;

  public KeycloakGenericConnector(WebClient webClient) {
    this.webClient = webClient;
  }

  public <T> Flux<T> getFlux(
      String url,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    WebClient.ResponseSpec spec =
        webClient.get().uri(url).headers(h -> setBearerAuth(h, token)).retrieve();

    return applyErrorHandling(
        applyStatusHandlers(spec, url, errorFactory).bodyToFlux(responseType), url, errorFactory);
  }

  public <T> Mono<T> getMono(
      String url,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return getFlux(url, token, responseType, errorFactory).next();
  }

  public <T> Flux<T> postFlux(
      String url,
      MediaType contentType,
      BodyInserter<?, ? super ClientHttpRequest> body,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    WebClient.ResponseSpec spec =
        webClient
            .post()
            .uri(url)
            .contentType(contentType)
            .headers(h -> setBearerAuth(h, token))
            .body(body)
            .retrieve();

    return applyErrorHandling(
        applyStatusHandlers(spec, url, errorFactory).bodyToFlux(responseType), url, errorFactory);
  }

  public <T> Mono<T> postMono(
      String url,
      MediaType contentType,
      BodyInserter<?, ? super ClientHttpRequest> body,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return postFlux(url, contentType, body, token, responseType, errorFactory).next();
  }

  public <T> Mono<T> putMono(
      String url,
      MediaType contentType,
      BodyInserter<?, ? super ClientHttpRequest> body,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    WebClient.ResponseSpec spec =
        webClient
            .put()
            .uri(url)
            .contentType(contentType)
            .headers(h -> setBearerAuth(h, token))
            .body(body)
            .retrieve();

    return applyErrorHandling(
            applyStatusHandlers(spec, url, errorFactory).bodyToFlux(responseType),
            url,
            errorFactory)
        .next();
  }

  public Mono<Void> delete(
      String url, String token, BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    WebClient.ResponseSpec spec =
        webClient.delete().uri(url).headers(h -> setBearerAuth(h, token)).retrieve();

    return applyErrorHandling(
            applyStatusHandlers(spec, url, errorFactory).bodyToFlux(Void.class), url, errorFactory)
        .then();
  }

  private <T> Flux<T> applyErrorHandling(
      Flux<T> flux, String url, BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return flux
        // Connection-level failure (e.g. Keycloak is down, DNS failure, timeout)
        .onErrorMap(
            WebClientRequestException.class,
            e -> {
              log.error("Connection error reaching Keycloak [{}]: {}", url, e.getMessage());
              return errorFactory.apply(
                  "Keycloak is unreachable: connection failed", HttpStatus.SERVICE_UNAVAILABLE);
            })

        // Response-level failure not caught by onStatus (should rarely occur)
        .onErrorMap(
            WebClientResponseException.class,
            e -> {
              log.error(
                  "Unexpected WebClient response error [{}] - status: {}, body: {}",
                  url,
                  e.getStatusCode(),
                  e.getResponseBodyAsString());
              return errorFactory.apply(
                  "Unexpected response from Keycloak", HttpStatus.SERVICE_UNAVAILABLE);
            })

        // Any other unchecked exception not already wrapped by our factory
        .onErrorMap(
            e -> !(e instanceof RuntimeException) || isUnmappedRuntimeException(e),
            e -> {
              log.error(
                  "Unexpected error during Keycloak request [{}]: {}", url, e.getMessage(), e);
              return errorFactory.apply(
                  "Unexpected internal error", HttpStatus.INTERNAL_SERVER_ERROR);
            });
  }

  /** True when the exception was NOT already produced by our errorFactory chain. */
  private boolean isUnmappedRuntimeException(Throwable e) {
    return false;
  }

  private void setBearerAuth(org.springframework.http.HttpHeaders headers, String token) {
    if (token != null && !token.isBlank()) {
      headers.setBearerAuth(token);
    }
  }

  private WebClient.ResponseSpec applyStatusHandlers(
      WebClient.ResponseSpec spec,
      String url,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return spec.onStatus(
            status -> status.value() == 401,
            resp -> {
              log.warn("401 Unauthorized from Keycloak [{}]", url);
              return Mono.error(
                  errorFactory.apply("Invalid or expired token", HttpStatus.UNAUTHORIZED));
            })
        .onStatus(
            status -> status.value() == 403,
            resp -> {
              log.warn("403 Forbidden from Keycloak [{}]", url);
              return Mono.error(
                  errorFactory.apply(
                      "Insufficient permissions to access resource", HttpStatus.FORBIDDEN));
            })
        .onStatus(
            status -> status.value() == 404,
            resp -> {
              log.warn("404 Not Found from Keycloak [{}]", url);
              return Mono.error(errorFactory.apply("Resource not found", HttpStatus.NOT_FOUND));
            })
        .onStatus(
            HttpStatusCode::is4xxClientError,
            resp ->
                resp.bodyToMono(String.class)
                    .flatMap(
                        body -> {
                          log.warn("4xx error from Keycloak [{}]: {}", url, body);
                          return Mono.error(
                              errorFactory.apply("Invalid Request", HttpStatus.BAD_REQUEST));
                        }))
        .onStatus(
            HttpStatusCode::is5xxServerError,
            resp ->
                resp.bodyToMono(String.class)
                    .flatMap(
                        body -> {
                          log.error("5xx error from Keycloak [{}]: {}", url, body);
                          return Mono.<Throwable>error(
                              errorFactory.apply(
                                  "Internal Server Error", HttpStatus.SERVICE_UNAVAILABLE));
                        }));
  }
}
