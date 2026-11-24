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

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

  static final String INVALID_REQUEST = "Invalid request";
  static final String NOT_FOUND = "Resource not found";
  static final String SERVER_ERROR = "An unexpected error occurred";
  static final String MALFORMED_BODY = "Malformed request body";
  static final String FILE_TOO_LARGE = "Uploaded file is too large";

  /** Our own not-found messages; JPA's ("Unable to find com.neo...") reveal internals. */
  private static final Pattern OWN_NOT_FOUND =
      Pattern.compile("^[A-Z][A-Za-z ]{0,40} not found(: [A-Za-z0-9_-]{1,64})?$");

  @ExceptionHandler(AttributeValidationException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleAttributeValidation(
      AttributeValidationException ex) {
    return error(ex.getStatus(), ex.getMessage(), ex);
  }

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

  /** Also raised by libraries, so its message is never returned. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleIllegalArgument(
      IllegalArgumentException ex) {
    return error(HttpStatus.BAD_REQUEST, ex.getMessage(), INVALID_REQUEST, ex);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleConflict(
      DataIntegrityViolationException ex) {
    return error(
        HttpStatus.CONFLICT,
        ex.getMostSpecificCause().getMessage(),
        "Request conflicts with existing data (duplicate or still in use)",
        ex);
  }

  /** Field names and messages are returned, rejected values are not. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleValidation(
      MethodArgumentNotValidException ex) {
    return error(
        HttpStatus.BAD_REQUEST, "Validation failed: " + fieldErrors(ex.getBindingResult()), ex);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleUnreadable(
      HttpMessageNotReadableException ex) {
    return error(HttpStatus.BAD_REQUEST, ex.getMessage(), MALFORMED_BODY, ex);
  }

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
