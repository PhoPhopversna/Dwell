package com.neo.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation-id conventions shared by every service.
 *
 * <p>The API gateway accepts a valid incoming id or generates one, and forwards it on {@link
 * #HEADER}. Downstream services reuse the id they receive and only generate one as a last resort
 * (e.g. a request that did not come through the gateway).
 */
public final class CorrelationId {

  /** HTTP and Kafka header carrying the id. Header names are case-insensitive in HTTP. */
  public static final String HEADER = "X-Correlation-ID";

  /** MDC / Reactor-context key, rendered as the {@code correlationId} JSON field. */
  public static final String MDC_KEY = "correlationId";

  /**
   * Accepted incoming ids: 1-64 chars of letters, digits, '-', '_', '.', ':'. Anything else is
   * replaced, which also prevents log injection (newlines, JSON breaking characters).
   */
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  private CorrelationId() {}

  public static boolean isValid(String value) {
    return value != null && VALID.matcher(value).matches();
  }

  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /** The current thread's correlation id from the MDC, or null (e.g. for response bodies). */
  public static String current() {
    return org.slf4j.MDC.get(MDC_KEY);
  }

  /**
   * The standard error body for responses written by hand (filters, security handlers): {@code
   * {"success":false,"message":"...","data":null,"correlationId":"..."}}. {@code message} must be a
   * fixed text (it is not escaped); the id is safe because only validated ids exist.
   */
  public static String errorJson(String message, String correlationId) {
    return "{\"success\":false,\"message\":\""
        + message
        + "\",\"data\":null"
        + (correlationId != null ? ",\"correlationId\":\"" + correlationId + "\"" : "")
        + "}";
  }

  /** The incoming id when valid, otherwise a newly generated one. */
  public static String resolve(String incoming) {
    return isValid(incoming) ? incoming : generate();
  }
}
