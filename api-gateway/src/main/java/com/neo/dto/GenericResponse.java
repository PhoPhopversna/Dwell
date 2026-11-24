package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neo.logging.CorrelationId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class GenericResponse<T> {

  private boolean success;
  private String errorCode;
  private String message;
  private T data;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String correlationId = CorrelationId.current();

  public GenericResponse(boolean success, String errorCode, String message, T data) {
    this.success = success;
    this.errorCode = errorCode;
    this.message = message;
    this.data = data;
  }

  public static <T> GenericResponse<T> success(T data) {
    return new GenericResponse<>(true, "200", "Request was successful", data);
  }

  public static <T> GenericResponse<T> error(String errorCode, String message) {
    return new GenericResponse<>(false, errorCode, message, null);
  }
}
