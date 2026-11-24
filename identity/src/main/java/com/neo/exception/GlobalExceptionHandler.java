package com.neo.exception;

import com.neo.dto.ApiResponse;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

  static final Map<Integer, String> PUBLIC_MESSAGES =
      Map.of(
          400, "Invalid request",
          401, "Authentication failed",
          403, "Access denied",
          404, "Resource not found",
          409, "Request could not be completed",
          429, "Too many requests",
          503, "Service temporarily unavailable");

  static final String DEFAULT_CLIENT_ERROR = "Request could not be completed";
  static final String DEFAULT_SERVER_ERROR = "An unexpected error occurred";

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<ApiResponse<Void>> handleAuth(AuthException ex) {
    return respond(ex.getStatus(), ex.getMessage(), ex);
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
    return respond(HttpStatus.CONFLICT, ex.getMessage(), ex);
  }

  @ExceptionHandler(WebExchangeBindException.class)
  public ResponseEntity<ApiResponse<Void>> handleBind(WebExchangeBindException ex) {
    return respond(HttpStatus.BAD_REQUEST, "Validation failed: " + fieldErrors(ex), ex);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    return respond(
        HttpStatus.BAD_REQUEST, "Validation failed: " + fieldErrors(ex.getBindingResult()), ex);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
    return respond(ex.getStatusCode(), ex.getReason(), ex);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
    return respond(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
  }

  @ExceptionHandler(WebClientRequestException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnreachable(WebClientRequestException ex) {
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Dependency unreachable: " + ex.getMethod() + " " + ex.getUri() + ": " + ex.getMessage(),
        ex);
  }

  /** Unmapped Keycloak error: 5xx -> 503, otherwise 500. */
  @ExceptionHandler(WebClientResponseException.class)
  public ResponseEntity<ApiResponse<Void>> handleDependencyError(WebClientResponseException ex) {
    HttpStatus status =
        ex.getStatusCode().is5xxServerError()
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.INTERNAL_SERVER_ERROR;
    return respond(
        status,
        "Dependency returned %d for %s: %s"
            .formatted(
                ex.getStatusCode().value(),
                ex.getRequest() != null ? ex.getRequest().getURI() : "?",
                ex.getResponseBodyAsString()),
        ex);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleDataConflict(DataIntegrityViolationException ex) {
    return respond(HttpStatus.CONFLICT, ex.getMostSpecificCause().getMessage(), ex);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
  }

  static String publicMessage(int status) {
    return PUBLIC_MESSAGES.getOrDefault(
        status, status >= 500 ? DEFAULT_SERVER_ERROR : DEFAULT_CLIENT_ERROR);
  }

  private static ResponseEntity<ApiResponse<Void>> respond(
      HttpStatusCode status, String detail, Exception ex) {
    int code = status != null ? status.value() : 500;
    if (code >= 500) {
      log.error("Request failed ({}): {}", code, detail, ex);
    } else {
      log.warn("Request rejected ({}) {}: {}", code, ex.getClass().getSimpleName(), detail);
    }
    return ResponseEntity.status(code).body(ApiResponse.error(publicMessage(code)));
  }

  private static String fieldErrors(BindingResult result) {
    return result.getFieldErrors().stream()
        .map(FieldError::getField)
        .distinct()
        .collect(Collectors.joining(", "));
  }
}
