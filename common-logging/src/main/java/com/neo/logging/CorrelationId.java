package com.neo.logging;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationId {

  public static final String HEADER = "X-Correlation-ID";

  public static final String MDC_KEY = "correlationId";

  /** 1-64 chars of [A-Za-z0-9._:-]; anything else is replaced, which also blocks log injection. */
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  private CorrelationId() {}

  public static boolean isValid(String value) {
    return value != null && VALID.matcher(value).matches();
  }

  public static String generate() {
    return UUID.randomUUID().toString();
  }

  public static String current() {
    return org.slf4j.MDC.get(MDC_KEY);
  }

  /** {@code message} must be a fixed text: it is not escaped. */
  public static String errorJson(String message, String correlationId) {
    return "{\"success\":false,\"message\":\""
        + message
        + "\",\"data\":null"
        + (correlationId != null ? ",\"correlationId\":\"" + correlationId + "\"" : "")
        + "}";
  }

  public static String resolve(String incoming) {
    return isValid(incoming) ? incoming : generate();
  }
}
