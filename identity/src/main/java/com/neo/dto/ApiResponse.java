package com.neo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neo.logging.CorrelationId;
import lombok.Data;

@Data
public class ApiResponse<T> {
  private boolean success;
  private String message;
  private T data;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String correlationId = CorrelationId.current();

  public static <T> ApiResponse<T> ok(T data) {
    ApiResponse<T> r = new ApiResponse<>();
    r.success = true;
    r.data = data;
    return r;
  }

  public static <T> ApiResponse<T> ok(String message, T data) {
    ApiResponse<T> r = new ApiResponse<>();
    r.success = true;
    r.message = message;
    r.data = data;
    return r;
  }

  public static <T> ApiResponse<T> error(String message) {
    ApiResponse<T> r = new ApiResponse<>();
    r.success = false;
    r.message = message;
    return r;
  }
}
