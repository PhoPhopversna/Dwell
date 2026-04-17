package com.neo.exception;

import org.springframework.http.HttpStatus;

public class RouteNotFoundException extends AppException {

  public RouteNotFoundException(HttpStatus status, String errorCode, String message) {
    super(status, errorCode, message);
  }
}
