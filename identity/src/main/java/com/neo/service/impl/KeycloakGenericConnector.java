package com.neo.service.impl;

import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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

    return applyErrorHandling(
        webClient
            .get()
            .uri(url)
            .headers(h -> setBearerAuth(h, token))
            .retrieve()
            .onStatus(
                status -> status.is4xxClientError(),
                resp ->
                    resp.bodyToMono(String.class)
                        .flatMap(
                            body ->
                                Mono.error(
                                    errorFactory.apply(
                                        "Client error: " + body, HttpStatus.BAD_REQUEST))))
            .onStatus(
                status -> status.is5xxServerError(),
                resp ->
                    Mono.error(errorFactory.apply("Server error", HttpStatus.SERVICE_UNAVAILABLE)))
            .bodyToFlux(responseType),
        errorFactory);
  }

  public <T> Mono<T> getMono(
      String url,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return getFlux(url, token, responseType, errorFactory).next();
  }

  // =========================================================================
  // POST
  // =========================================================================

  public <T> Flux<T> postFlux(
      String url,
      MediaType contentType,
      BodyInserter<?, ? super ClientHttpRequest> body,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return applyErrorHandling(
        webClient
            .post()
            .uri(url)
            .contentType(contentType)
            .headers(h -> setBearerAuth(h, token))
            .body(body)
            .retrieve()
            .onStatus(
                status -> status.value() == 401,
                resp -> Mono.error(errorFactory.apply("Unauthorized", HttpStatus.UNAUTHORIZED)))
            .onStatus(
                status -> status.is4xxClientError(),
                resp ->
                    resp.bodyToMono(String.class)
                        .flatMap(
                            errorBody -> {
                              log.error("4xx error: {}", errorBody);
                              return Mono.error(
                                  errorFactory.apply("Client error", HttpStatus.BAD_REQUEST));
                            }))
            .onStatus(
                status -> status.is5xxServerError(),
                resp ->
                    Mono.error(errorFactory.apply("Server error", HttpStatus.SERVICE_UNAVAILABLE)))
            .bodyToFlux(responseType),
        errorFactory);
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

  // =========================================================================
  // PUT
  // =========================================================================

  public <T> Mono<T> putMono(
      String url,
      MediaType contentType,
      BodyInserter<?, ? super ClientHttpRequest> body,
      String token,
      Class<T> responseType,
      BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return applyErrorHandling(
            webClient
                .put()
                .uri(url)
                .contentType(contentType)
                .headers(h -> setBearerAuth(h, token))
                .body(body)
                .retrieve()
                .onStatus(
                    status -> status.is4xxClientError(),
                    resp ->
                        resp.bodyToMono(String.class)
                            .flatMap(
                                errorBody ->
                                    Mono.error(
                                        errorFactory.apply(
                                            "Client error: " + errorBody, HttpStatus.BAD_REQUEST))))
                .onStatus(
                    status -> status.is5xxServerError(),
                    resp ->
                        Mono.error(
                            errorFactory.apply("Server error", HttpStatus.SERVICE_UNAVAILABLE)))
                .bodyToFlux(responseType),
            errorFactory)
        .next();
  }

  // =========================================================================
  // DELETE
  // =========================================================================

  public Mono<Void> delete(
      String url, String token, BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return applyErrorHandling(
            webClient
                .delete()
                .uri(url)
                .headers(h -> setBearerAuth(h, token))
                .retrieve()
                .onStatus(
                    status -> status.is4xxClientError(),
                    resp ->
                        resp.bodyToMono(String.class)
                            .flatMap(
                                errorBody ->
                                    Mono.error(
                                        errorFactory.apply(
                                            "Client error: " + errorBody, HttpStatus.BAD_REQUEST))))
                .onStatus(
                    status -> status.is5xxServerError(),
                    resp ->
                        Mono.error(
                            errorFactory.apply("Server error", HttpStatus.SERVICE_UNAVAILABLE)))
                .bodyToFlux(Void.class),
            errorFactory)
        .then();
  }

  private <T> Flux<T> applyErrorHandling(
      Flux<T> flux, BiFunction<String, HttpStatus, RuntimeException> errorFactory) {

    return flux.onErrorMap(
            WebClientRequestException.class,
            e -> {
              log.error("Connection error: {}", e.getMessage());
              return errorFactory.apply("Service unreachable", HttpStatus.SERVICE_UNAVAILABLE);
            })
        .onErrorMap(
            WebClientResponseException.class,
            e -> {
              log.error("Request failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
              return errorFactory.apply("Service error", HttpStatus.SERVICE_UNAVAILABLE);
            })
        .onErrorMap(
            e -> !(e instanceof RuntimeException) || isUnmappedRuntimeException(e, errorFactory),
            e -> {
              log.error("Unexpected error: {}", e.getMessage());
              return errorFactory.apply("Unexpected error", HttpStatus.INTERNAL_SERVER_ERROR);
            });
  }

  /** True when the exception was NOT already produced by our errorFactory chain. */
  private boolean isUnmappedRuntimeException(
      Throwable e, BiFunction<String, HttpStatus, RuntimeException> errorFactory) {
    return false;
  }

  private void setBearerAuth(org.springframework.http.HttpHeaders headers, String token) {
    if (token != null && !token.isBlank()) {
      headers.setBearerAuth(token);
    }
  }
}
