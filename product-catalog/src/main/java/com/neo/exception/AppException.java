package com.neo.exception;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {

  private HttpStatus status;
  private String errorCode;

  public AppException(HttpStatus status, String errorCode, String message) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  /** Same, keeping the underlying failure (e.g. a MinIO error) as the cause for the log. */
  public AppException(HttpStatus status, String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
