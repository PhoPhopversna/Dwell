package com.neo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.dto.ApiResponse;
import com.neo.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/** Clients get a general message per status; details (user, role, Keycloak body) never leak. */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static void assertGeneral(
      ResponseEntity<ApiResponse<Void>> response, int status, String message, String... secrets) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getBody().isSuccess()).isFalse();
    assertThat(response.getBody().getMessage()).isEqualTo(message);
    for (String secret : secrets) {
      assertThat(response.getBody().getMessage()).doesNotContain(secret);
    }
  }

  @Test
  void existingUserIsNotRevealed() {
    assertGeneral(
        handler.handleConflict(
            new ConflictException(
                "User with this username or email already exists", HttpStatus.CONFLICT)),
        409,
        "Request could not be completed",
        "exists",
        "email");
  }

  @Test
  void keycloakDetailsAndNamesAreNotRevealed() {
    assertGeneral(
        handler.handleAuth(
            new AuthException(
                "Failed to delete role: {\"error\":\"Could not find role\"}",
                HttpStatus.NOT_FOUND)),
        404,
        "Resource not found",
        "Could not find role",
        "error");
    assertGeneral(
        handler.handleAuth(new AuthException("Role not found: SECRET_ROLE", HttpStatus.NOT_FOUND)),
        404,
        "Resource not found",
        "SECRET_ROLE");
    assertGeneral(
        handler.handleAuth(new AuthException("Invalid or expired token", HttpStatus.UNAUTHORIZED)),
        401,
        "Authentication failed");
    assertGeneral(
        handler.handleAuth(
            new AuthException(
                "Keycloak is unreachable: connection failed", HttpStatus.SERVICE_UNAVAILABLE)),
        503,
        "Service temporarily unavailable",
        "Keycloak");
  }

  @Test
  void validationAndMalformedInputAre400NotServerErrors() throws Exception {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new LoginRequest(), "login");
    result.rejectValue("password", "NotBlank", "Password is required");
    MethodParameter param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
    assertGeneral(
        handler.handleBind(new WebExchangeBindException(param, result)),
        400,
        "Invalid request",
        "password",
        "Password");
    assertGeneral(
        handler.handleResponseStatus(new ServerWebInputException("Failed to read HTTP message")),
        400,
        "Invalid request",
        "HTTP message");
  }

  @Test
  void unexpectedErrorsAndAccessDeniedAreGeneral() {
    assertGeneral(
        handler.handleUnexpected(new NullPointerException("request.getFirstName() is null")),
        500,
        "An unexpected error occurred",
        "getFirstName");
    assertGeneral(
        handler.handleAccessDenied(new AccessDeniedException("needs ADMIN")),
        403,
        "Access denied",
        "ADMIN");
  }

  @Test
  void unmappedStatusesFallBackToGeneralMessages() {
    assertThat(GlobalExceptionHandler.publicMessage(418))
        .isEqualTo("Request could not be completed");
    assertThat(GlobalExceptionHandler.publicMessage(502)).isEqualTo("An unexpected error occurred");
  }

  @Test
  void keycloakUnreachableIs503() {
    var ex =
        new org.springframework.web.reactive.function.client.WebClientRequestException(
            new java.net.ConnectException("Connection refused: localhost/127.0.0.1:8080"),
            org.springframework.http.HttpMethod.POST,
            java.net.URI.create("http://localhost:8080/realms/Dwell/protocol/openid-connect/token"),
            new org.springframework.http.HttpHeaders());
    assertGeneral(
        handler.handleUnreachable(ex), 503, "Service temporarily unavailable", "8080", "Dwell");
  }

  @Test
  void unmappedKeycloakErrors_5xxIs503_otherwise500() {
    var failing =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            502, "Bad Gateway", null, "upstream proxy error".getBytes(), null);
    assertGeneral(handler.handleDependencyError(failing), 503, "Service temporarily unavailable");

    var odd =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            400, "Bad Request", null, "{\"error\":\"invalid_client\"}".getBytes(), null);
    assertGeneral(
        handler.handleDependencyError(odd), 500, "An unexpected error occurred", "invalid_client");
  }

  @Test
  void localDuplicateIs409() {
    assertGeneral(
        handler.handleDataConflict(
            new org.springframework.dao.DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"app_user_username_key\"")),
        409,
        "Request could not be completed",
        "app_user");
  }
}
