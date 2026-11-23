package com.neo.dto;

import lombok.Data;

@Data
public class CustomApiResponse<T> {
  private boolean success;
  private String message;
  private T data;

  public static <T> CustomApiResponse<T> ok(T data) {
    CustomApiResponse<T> r = new CustomApiResponse<>();
    r.success = true;
    r.data = data;
    return r;
  }

  public static <T> CustomApiResponse<T> ok(String message, T data) {
    CustomApiResponse<T> r = new CustomApiResponse<>();
    r.success = true;
    r.message = message;
    r.data = data;
    return r;
  }

  public static <T> CustomApiResponse<T> error(String message) {
    CustomApiResponse<T> r = new CustomApiResponse<>();
    r.success = false;
    r.message = message;
    return r;
  }
}
