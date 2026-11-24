package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neo.exception.AuthException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * An error status from Keycloak must always become an error, even with an empty body. (Before the
 * fix, an empty 4xx/5xx body made the error handler return nothing, and the call "succeeded".)
 */
class KeycloakGenericConnectorTest {

  private static KeycloakGenericConnector connectorAnswering(HttpStatus status, String body) {
    WebClient client =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  ClientResponse.Builder response =
                      ClientResponse.create(status)
                          .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                  return Mono.just(body == null ? response.build() : response.body(body).build());
                })
            .build();
    return new KeycloakGenericConnector(client);
  }

  private static Mono<String> login(KeycloakGenericConnector connector) {
    return connector.postMono(
        "http://keycloak/token",
        MediaType.APPLICATION_FORM_URLENCODED,
        BodyInserters.fromFormData("grant_type", "password"),
        null,
        String.class,
        KeycloakAuthServiceImpl.AUTH_ERROR_FACTORY);
  }

  @Test
  void emptyBody4xxIsAnError() {
    assertThatThrownBy(() -> login(connectorAnswering(HttpStatus.BAD_REQUEST, null)).block())
        .isInstanceOf(AuthException.class)
        .satisfies(
            e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void emptyBody5xxIsAnError() {
    assertThatThrownBy(
            () -> login(connectorAnswering(HttpStatus.INTERNAL_SERVER_ERROR, null)).block())
        .isInstanceOf(AuthException.class)
        .satisfies(
            e ->
                assertThat(((AuthException) e).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void successStillWorks() {
    assertThat(login(connectorAnswering(HttpStatus.OK, "{\"access_token\":\"x\"}")).block())
        .isEqualTo("{\"access_token\":\"x\"}");
  }
}
