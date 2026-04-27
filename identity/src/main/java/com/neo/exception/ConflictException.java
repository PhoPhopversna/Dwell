package com.neo.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ConflictException extends RuntimeException {
  private final HttpStatus status;

  public ConflictException(String message, HttpStatus status) {
    super(message);
    this.status = status;
  }
}
