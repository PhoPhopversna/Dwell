package com.neo.exception;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AttributeValidationException extends RuntimeException {
  private final HttpStatus status;
  private final List<String> errors;

  public AttributeValidationException(List<String> errors, HttpStatus status) {
    super("Attribute validation failed: " + String.join("; ", errors));
    this.errors = errors;
    this.status = status;
  }

  public List<String> getErrors() {
    return errors;
  }
}
