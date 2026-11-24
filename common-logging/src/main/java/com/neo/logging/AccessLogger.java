package com.neo.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Emits the single request/response log line per request per service. Used only by the web filters,
 * so a request is logged once per service no matter how many layers it passes through.
 *
 * <p>Level by outcome: 2xx/3xx INFO, 4xx WARN, 5xx ERROR. Logs method, path, status, duration and,
 * when enabled, the query string, the text/JSON request body and the text/JSON response body, all
 * with sensitive values masked by {@link RequestDataMasker}. Headers are never logged.
 */
public final class AccessLogger {

  public static final String LOGGER_NAME = "com.neo.logging.access";

  private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

  private AccessLogger() {}

  public static void requestReceived(String method, String path) {
    if (LOG.isDebugEnabled()) {
      LOG.atDebug()
          .addKeyValue("event", "http_request_received")
          .addKeyValue("method", method)
          .addKeyValue("path", path)
          .log("Request received");
    }
  }

  /** Masked request data for the access line; any field may be null (then it is omitted). */
  public record RequestData(String query, String contentType, String body) {
    public static final RequestData NONE = new RequestData(null, null, null);
  }

  /** Masked response data for the access line; null body = not logged. */
  public record ResponseData(String contentType, String body) {
    public static final ResponseData NONE = new ResponseData(null, null);
  }

  public static void requestCompleted(
      String method, String path, int status, long durationMs, Throwable error) {
    requestCompleted(method, path, status, durationMs, error, RequestData.NONE);
  }

  public static void requestCompleted(
      String method,
      String path,
      int status,
      long durationMs,
      Throwable error,
      RequestData request) {
    requestCompleted(method, path, status, durationMs, error, request, ResponseData.NONE);
  }

  public static void requestCompleted(
      String method,
      String path,
      int status,
      long durationMs,
      Throwable error,
      RequestData request,
      ResponseData response) {
    Level level =
        status >= 500 || error != null ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO;
    LoggingEventBuilder event =
        LOG.atLevel(level)
            .addKeyValue("event", "http_request_completed")
            .addKeyValue("method", method)
            .addKeyValue("path", path)
            .addKeyValue("status", status)
            .addKeyValue("durationMs", durationMs);
    if (request.query() != null) {
      event.addKeyValue("query", request.query());
    }
    if (request.body() != null) {
      event.addKeyValue("contentType", request.contentType());
      event.addKeyValue("requestBody", request.body());
    }
    if (response.body() != null) {
      event.addKeyValue("responseContentType", response.contentType());
      event.addKeyValue("responseBody", response.body());
    }
    if (error != null) {
      event.setCause(error).log("Request failed with unhandled exception");
    } else if (status >= 500) {
      event.log("Request completed with server error");
    } else if (status >= 400) {
      event.log("Request completed with client error");
    } else {
      event.log("Request completed successfully");
    }
  }
}
