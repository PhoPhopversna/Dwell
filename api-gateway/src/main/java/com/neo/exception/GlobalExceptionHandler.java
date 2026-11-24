package com.neo.exception;

import com.neo.dto.GenericResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Errors from the gateway's own endpoints ({@code /api/routes}). Proxied requests never get here:
 * their errors come from the downstream service, or from {@code DownstreamErrorFilter} when it
 * can't be reached.
 *
 * <p>4xx are logged at WARN with the detail, 5xx at ERROR with the stack trace. The client gets the
 * {@link GenericResponse} shape with a general message for 5xx, and the correlation id.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

  static final String SERVER_ERROR = "An unexpected error occurred";
  static final String INVALID_REQUEST = "Invalid request";

  /** Already logged with its context where it was raised ({@code RouteServiceImpl}). */
  @ExceptionHandler(DataBaseException.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleDatabaseException(
      DataBaseException ex) {
    return Mono.just(
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(GenericResponse.error(ex.getErrorCode(), SERVER_ERROR)));
  }

  @ExceptionHandler(AppException.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleAppException(AppException ex) {
    HttpStatus status = ex.getStatus() != null ? ex.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
    return respond(status, ex.getErrorCode(), ex.getMessage(), ex.getMessage(), ex);
  }

  /**
   * Malformed JSON, a body field of the wrong type, {@code /api/routes/abc} for a numeric id, and
   * {@code @Valid} failures ({@code WebExchangeBindException} is a subtype).
   */
  @ExceptionHandler(ServerWebInputException.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleBadInput(ServerWebInputException ex) {
    return respond(HttpStatus.BAD_REQUEST, "400", detail(ex), INVALID_REQUEST, ex);
  }

  /** Other framework errors that carry their own status (405, 406, 415, ...). */
  @ExceptionHandler(ResponseStatusException.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleResponseStatus(
      ResponseStatusException ex) {
    HttpStatus status = toHttpStatus(ex.getStatusCode());
    String message = status.is5xxServerError() ? SERVER_ERROR : status.getReasonPhrase();
    return respond(status, String.valueOf(status.value()), detail(ex), message, ex);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleAccessDenied(
      AccessDeniedException ex) {
    return respond(HttpStatus.FORBIDDEN, "403", ex.getMessage(), "Access denied", ex);
  }

  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleUnexpected(Exception ex) {
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "500", ex.getMessage(), SERVER_ERROR, ex);
  }

  private static Mono<ResponseEntity<GenericResponse<Object>>> respond(
      HttpStatus status, String errorCode, String detail, String clientMessage, Exception ex) {
    if (status.is5xxServerError()) {
      log.error("Request failed ({}): {}", status.value(), detail, ex);
      clientMessage = SERVER_ERROR;
    } else {
      log.warn(
          "Request rejected ({}) {}: {}", status.value(), ex.getClass().getSimpleName(), detail);
    }
    return Mono.just(
        ResponseEntity.status(status).body(GenericResponse.error(errorCode, clientMessage)));
  }

  /** The reason plus the root cause, e.g. "Failed to read HTTP message: Unexpected character". */
  private static String detail(ResponseStatusException ex) {
    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root == ex ? ex.getReason() : ex.getReason() + ": " + root.getMessage();
  }

  private static HttpStatus toHttpStatus(HttpStatusCode code) {
    HttpStatus resolved = HttpStatus.resolve(code.value());
    return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
  }
}
