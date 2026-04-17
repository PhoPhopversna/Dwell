package com.neo.exception;

import org.springframework.http.HttpStatus;

public class DataBaseException extends AppException {

  public DataBaseException(HttpStatus status, String errorCode, String message) {
    super(status, errorCode, message);
  }
}
