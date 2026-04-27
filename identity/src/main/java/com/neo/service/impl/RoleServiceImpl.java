package com.neo.service.impl;

import com.neo.config.KeycloakProperties;
import com.neo.dto.CreateRoleRequest;
import com.neo.dto.RoleRepresentation;
import com.neo.dto.UserRolesResponse;
import com.neo.exception.AuthException;
import com.neo.service.RoleService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class RoleServiceImpl implements RoleService {

  private final KeycloakProperties keycloakProperties;
  private final WebClient webClient;

  private final KeycloakAdminServiceImpl keycloakAdminService;
  private final KeycloakGenericConnector keycloakGenericConnector;
  public static final BiFunction<String, HttpStatus, RuntimeException> AUTH_ERROR_FACTORY =
      (msg, status) -> new AuthException(msg, status);

  public RoleServiceImpl(
      KeycloakProperties keycloakProperties,
      WebClient webClient,
      KeycloakAdminServiceImpl keycloakAdminService,
      KeycloakGenericConnector keycloakGenericConnector) {
    this.keycloakProperties = keycloakProperties;
    this.webClient = webClient;
    this.keycloakAdminService = keycloakAdminService;
    this.keycloakGenericConnector = keycloakGenericConnector;
  }

  @Override
  public Flux<RoleRepresentation> getAllRoles() {

    return keycloakAdminService
        .getAccessTokenCache()
        .doOnNext(token -> log.debug("Fetching all realm roles"))
        .flatMapMany(
            token ->
                keycloakGenericConnector.getFlux(
                    keycloakProperties.getRealmRolesEndpoint(),
                    token,
                    RoleRepresentation.class,
                    AUTH_ERROR_FACTORY));
  }

  @Override
  public Mono<Void> createRole(CreateRoleRequest request) {
    return keycloakAdminService
        .getAccessTokenCache()
        .doOnNext(token -> log.debug("Creating realm role: {}", request.getName()))
        .flatMap(
            token -> {
              Map<String, String> body =
                  Map.of(
                      "name",
                      request.getName(),
                      "description",
                      request.getDescription() != null ? request.getDescription() : "");
              return keycloakGenericConnector.postMono(
                  keycloakProperties.getRealmRolesEndpoint(),
                  MediaType.APPLICATION_JSON,
                  BodyInserters.fromValue(body),
                  token,
                  Void.class,
                  AUTH_ERROR_FACTORY);
            });
  }

  @Override
  public Mono<Void> deleteRole(String roleName) {
    return keycloakAdminService
        .getAccessTokenCache()
        .doOnNext(token -> log.debug("Deleting realm role: {}", roleName))
        .flatMap(
            adminToken ->
                webClient
                    .delete()
                    .uri(keycloakProperties.getRealmRoleByNameEndpoint(roleName))
                    .headers(h -> h.setBearerAuth(adminToken))
                    .retrieve()
                    .onStatus(
                        status -> status.is4xxClientError(),
                        resp ->
                            resp.bodyToMono(String.class)
                                .flatMap(
                                    b ->
                                        Mono.error(
                                            new AuthException(
                                                "Failed to delete role: " + b,
                                                HttpStatus.BAD_REQUEST))))
                    .bodyToMono(Void.class));
  }

  @Override
  public Mono<Void> assignRolesToUser(String userId, List<String> roleNames) {
    return keycloakAdminService
        .getAccessTokenCache()
        .doOnNext(token -> log.debug("Assigning roles {} to user {}", roleNames, userId))
        .flatMap(
            token ->
                resolveRoles(token, roleNames)
                    .collectList()
                    .flatMap(
                        role ->
                            keycloakGenericConnector.postMono(
                                keycloakProperties.getUserRoleMappingsEndpoint(userId),
                                MediaType.APPLICATION_JSON,
                                BodyInserters.fromValue(role),
                                token,
                                Void.class,
                                AUTH_ERROR_FACTORY)));
  }

  @Override
  public Mono<Void> removeRolesFromUser(String userId, List<String> roleNames) {
    return keycloakAdminService
        .getAccessTokenCache()
        .doOnNext(token -> log.debug("Removing roles {} from user {}", roleNames, userId))
        .flatMap(
            adminToken ->
                resolveRoles(adminToken, roleNames)
                    .collectList()
                    .flatMap(
                        roleObjects ->
                            webClient
                                .method(HttpMethod.DELETE)
                                .uri(keycloakProperties.getUserRoleMappingsEndpoint(userId))
                                .headers(h -> h.setBearerAuth(adminToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(roleObjects)
                                .retrieve()
                                .onStatus(
                                    status -> status.is4xxClientError(),
                                    resp ->
                                        resp.bodyToMono(String.class)
                                            .flatMap(
                                                b ->
                                                    Mono.error(
                                                        new AuthException(
                                                            "Failed to remove roles: " + b,
                                                            HttpStatus.BAD_REQUEST))))
                                .bodyToMono(Void.class)));
  }

  @Override
  public Mono<UserRolesResponse> getUserRoles(String userId) {
    return keycloakAdminService
        .getAccessTokenCache()
        .doOnNext(token -> log.debug("Fetching roles for user {}", userId))
        .flatMap(
            adminToken ->
                Mono.zip(fetchUserInfo(adminToken, userId), fetchUserRoles(adminToken, userId)))
        .map(tuple -> buildResponse(userId, tuple.getT1(), tuple.getT2()))
        .doOnSuccess(
            r -> log.debug("Fetched {} roles for user {}", r.getRealmRoles().size(), userId))
        .doOnError(e -> log.error("Failed to fetch roles for user {}: {}", userId, e.getMessage()));
  }

  @Override
  public Flux<RoleRepresentation> resolveRoles(String adminToken, List<String> roleNames) {
    return Flux.fromIterable(roleNames)
        .flatMap(
            roleName ->
                webClient
                    .get()
                    .uri(keycloakProperties.getRealmRoleByNameEndpoint(roleName))
                    .headers(h -> h.setBearerAuth(adminToken))
                    .retrieve()
                    .onStatus(
                        status -> status.value() == 404,
                        resp ->
                            Mono.error(
                                new AuthException(
                                    "Role not found: " + roleName, HttpStatus.NOT_FOUND)))
                    .bodyToMono(RoleRepresentation.class)
                    .switchIfEmpty(
                        Mono.error(
                            new AuthException(
                                "Role not found: " + roleName, HttpStatus.NOT_FOUND))));
  }

  private Mono<Map<String, Object>> fetchUserInfo(String adminToken, String userId) {
    return webClient
        .get()
        .uri(keycloakProperties.getUserByIdEndpoint(userId))
        .headers(h -> h.setBearerAuth(adminToken))
        .retrieve()
        .onStatus(
            status -> status.value() == 404,
            resp ->
                Mono.error(new AuthException("User not found: " + userId, HttpStatus.NOT_FOUND)))
        .onStatus(
            HttpStatusCode::isError,
            resp ->
                resp.bodyToMono(String.class)
                    .flatMap(
                        body ->
                            Mono.error(
                                new AuthException(
                                    "Failed to fetch user info: " + body,
                                    HttpStatus.INTERNAL_SERVER_ERROR))))
        .bodyToMono(new ParameterizedTypeReference<>() {});
  }

  private Mono<List<RoleRepresentation>> fetchUserRoles(String adminToken, String userId) {
    return webClient
        .get()
        .uri(keycloakProperties.getUserRoleMappingsEndpoint(userId))
        .headers(h -> h.setBearerAuth(adminToken))
        .retrieve()
        .onStatus(
            HttpStatusCode::isError,
            resp ->
                resp.bodyToMono(String.class)
                    .flatMap(
                        body ->
                            Mono.error(
                                new AuthException(
                                    "Failed to fetch roles: " + body,
                                    HttpStatus.INTERNAL_SERVER_ERROR))))
        .bodyToFlux(RoleRepresentation.class)
        .collectList()
        .defaultIfEmpty(Collections.emptyList());
  }

  private UserRolesResponse buildResponse(
      String userId, Map<String, Object> userInfo, List<RoleRepresentation> roles) {
    UserRolesResponse response = new UserRolesResponse();
    response.setUserId(userId);
    response.setUsername(
        Optional.ofNullable(userInfo).map(info -> (String) info.get("username")).orElse("unknown"));
    response.setRealmRoles(roles);
    return response;
  }
}
