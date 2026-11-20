package com.neo.exception;

import com.neo.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ServerWebExchange;

@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AttributeValidationException.class)
  public ResponseEntity<ApiResponse<Void>> handleAuthException(
      AttributeValidationException ex, ServerWebExchange webExchange) {
    return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage()));
  }
}
