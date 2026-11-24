package com.neo.exception;

import com.neo.dto.CustomApiResponse;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * What a client may see:
 *
 * <ul>
 *   <li>Messages written by this service about the client's own input are kept: attribute
 *       validation errors, "Product not found: {id}", "Variant SKU already exists: {sku}", category
 *       hierarchy rules. They describe the request, not the system.
 *   <li>Anything that could come from a library, the JPA provider, the database or a 5xx is
 *       replaced by a general message: class/package names, SQL and driver details, MinIO/Kafka
 *       errors.
 * </ul>
 *
 * The full detail is always logged (WARN for 4xx, ERROR + stack trace for 5xx) with the request's
 * correlation id, which the client receives in the X-Correlation-ID response header.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

  static final String INVALID_REQUEST = "Invalid request";
  static final String NOT_FOUND = "Resource not found";
  static final String SERVER_ERROR = "An unexpected error occurred";
  static final String MALFORMED_BODY = "Malformed request body";
  static final String FILE_TOO_LARGE = "Uploaded file is too large";

  /**
   * Shape of this service's own not-found messages ("Product not found: 3fa8..."). The JPA provider
   * throws the same exception type with messages like "Unable to find com.neo.entity.Product with
   * id ...", which reveal internals and must not be returned.
   */
  private static final Pattern OWN_NOT_FOUND =
      Pattern.compile("^[A-Z][A-Za-z ]{0,40} not found(: [A-Za-z0-9_-]{1,64})?$");

  @ExceptionHandler(AttributeValidationException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleAttributeValidation(
      AttributeValidationException ex) {
    return error(ex.getStatus(), ex.getMessage(), ex);
  }

  /** Our own business errors: the message is safe for 4xx; 5xx are always general. */
  @ExceptionHandler(AppException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleAppException(AppException ex) {
    return error(ex.getStatus(), ex.getMessage(), ex);
  }

  @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleNotFound(
      jakarta.persistence.EntityNotFoundException ex) {
    String message = ex.getMessage();
    boolean own = message != null && OWN_NOT_FOUND.matcher(message).matches();
    return error(HttpStatus.NOT_FOUND, message, own ? message : NOT_FOUND, ex);
  }

  /** Raised by libraries as well as our code; its message is never trusted for the client. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleIllegalArgument(
      IllegalArgumentException ex) {
    return error(HttpStatus.BAD_REQUEST, ex.getMessage(), INVALID_REQUEST, ex);
  }

  /** Unique-constraint (duplicate SKU) or FK violations (category still in use). */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleConflict(
      DataIntegrityViolationException ex) {
    return error(
        HttpStatus.CONFLICT,
        ex.getMostSpecificCause().getMessage(),
        "Request conflicts with existing data (duplicate or still in use)",
        ex);
  }

  /**
   * {@code @Valid} failures on a {@code @RequestBody} / {@code @RequestPart}. The field names and
   * constraint messages ("name: must not be blank") describe the client's own input, so they are
   * returned; rejected values are not, as they may be sensitive.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleValidation(
      MethodArgumentNotValidException ex) {
    return error(
        HttpStatus.BAD_REQUEST, "Validation failed: " + fieldErrors(ex.getBindingResult()), ex);
  }

  /** Invalid JSON, wrong JSON types (text for a number, unknown enum value), empty body. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleUnreadable(
      HttpMessageNotReadableException ex) {
    return error(HttpStatus.BAD_REQUEST, ex.getMessage(), MALFORMED_BODY, ex);
  }

  /** e.g. {@code /api/categories/abc} where a UUID is expected. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    return error(
        HttpStatus.BAD_REQUEST,
        ex.getMessage(),
        "Invalid value for '%s'".formatted(ex.getName()),
        ex);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleMissingParameter(
      MissingServletRequestParameterException ex) {
    return error(
        HttpStatus.BAD_REQUEST,
        ex.getMessage(),
        "Missing parameter '%s'".formatted(ex.getParameterName()),
        ex);
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleMissingPart(
      MissingServletRequestPartException ex) {
    return error(
        HttpStatus.BAD_REQUEST,
        ex.getMessage(),
        "Missing part '%s'".formatted(ex.getRequestPartName()),
        ex);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
    return error(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), FILE_TOO_LARGE, ex);
  }

  /**
   * Everything else. Spring MVC's own exceptions (404 no handler, 405 method, 415 media type, ...)
   * carry their status; anything unknown is a 500. Only a general message reaches the client.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<CustomApiResponse<Void>> handleUnexpected(Exception ex) {
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    if (ex instanceof ErrorResponse response) {
      status = toHttpStatus(response.getStatusCode());
    }
    return error(status, ex.getMessage(), generalMessage(status), ex);
  }

  static String generalMessage(HttpStatus status) {
    return switch (status) {
      case NOT_FOUND -> NOT_FOUND;
      case METHOD_NOT_ALLOWED -> "Method not allowed";
      case UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type";
      case NOT_ACCEPTABLE -> "Not acceptable";
      case PAYLOAD_TOO_LARGE -> FILE_TOO_LARGE;
      default -> status.is5xxServerError() ? SERVER_ERROR : INVALID_REQUEST;
    };
  }

  private static HttpStatus toHttpStatus(HttpStatusCode code) {
    HttpStatus resolved = HttpStatus.resolve(code.value());
    return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static String fieldErrors(BindingResult result) {
    String fields =
        result.getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .distinct()
            .collect(Collectors.joining("; "));
    String global =
        result.getGlobalErrors().stream()
            .map(e -> e.getDefaultMessage())
            .distinct()
            .collect(Collectors.joining("; "));
    return fields.isEmpty() ? global : global.isEmpty() ? fields : fields + "; " + global;
  }

  private static ResponseEntity<CustomApiResponse<Void>> error(
      HttpStatus status, String message, Exception ex) {
    return error(status, message, message, ex);
  }

  /**
   * @param detail logged only
   * @param clientMessage returned to the client (replaced by a general message for 5xx)
   */
  private static ResponseEntity<CustomApiResponse<Void>> error(
      HttpStatus status, String detail, String clientMessage, Exception ex) {
    if (status.is5xxServerError()) {
      log.error("Request failed ({}): {}", status.value(), detail, ex);
      clientMessage = SERVER_ERROR;
    } else {
      log.warn(
          "Request rejected ({}) {}: {}", status.value(), ex.getClass().getSimpleName(), detail);
    }
    return ResponseEntity.status(status).body(CustomApiResponse.error(clientMessage));
  }
}
