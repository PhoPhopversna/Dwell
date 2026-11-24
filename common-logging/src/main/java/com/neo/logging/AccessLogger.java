package com.neo.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

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

  public record RequestData(String query, String contentType, String body) {
    public static final RequestData NONE = new RequestData(null, null, null);
  }

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
