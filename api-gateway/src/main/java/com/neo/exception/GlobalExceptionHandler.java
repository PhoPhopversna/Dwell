package com.neo.exception;

import com.neo.dto.GenericResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import reactor.core.publisher.Mono;

@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DataBaseException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Mono<GenericResponse<Object>> handleDatabaseException(DataBaseException ex) {
    return Mono.just(GenericResponse.error(ex.getErrorCode(), ex.getMessage()));
  }

  /** E.g. RouteNotFoundException: use the status the exception carries (404) instead of 500. */
  @ExceptionHandler(AppException.class)
  public Mono<ResponseEntity<GenericResponse<Object>>> handleAppException(AppException ex) {
    HttpStatus status = ex.getStatus() != null ? ex.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
    return Mono.just(
        ResponseEntity.status(status)
            .body(GenericResponse.error(ex.getErrorCode(), ex.getMessage())));
  }
}
