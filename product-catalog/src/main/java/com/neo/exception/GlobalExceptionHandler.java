package com.neo.exception;

import com.neo.dto.CustomApiResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AttributeValidationException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleAttributeValidation(
      AttributeValidationException ex) {
    return error(ex.getStatus(), ex.getMessage());
  }

  @ExceptionHandler(AppException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleAppException(AppException ex) {
    return error(ex.getStatus(), ex.getMessage());
  }

  @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleNotFound(
      jakarta.persistence.EntityNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
    return error(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  /** Unique-constraint (duplicate SKU) or FK violations (category still in use). */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<CustomApiResponse<Void>> handleConflict(DataIntegrityViolationException ex) {
    return error(
        HttpStatus.CONFLICT, "Request conflicts with existing data (duplicate or still in use)");
  }

  private static ResponseEntity<CustomApiResponse<Void>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(CustomApiResponse.error(message));
  }
}
