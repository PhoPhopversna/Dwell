package com.neo.logging;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What request and response data goes into the access log line ({@code app.logging.request.*}).
 *
 * @param logQuery log the query string (sensitive parameters masked)
 * @param logBody log the request body for text/JSON/form content types (sensitive fields masked)
 * @param logResponseBody log the response body for text/JSON/XML content types (sensitive fields
 *     masked); binary, compressed and streaming (SSE, NDJSON) responses never are
 * @param maxBodyChars longest body (request or response) written to the log; longer bodies are cut
 *     and marked
 * @param maskedFields extra field / parameter names to mask, on top of the built-in list
 *     (passwords, tokens, secrets, credentials, authorization, email, phone, card data)
 */
@ConfigurationProperties(prefix = "app.logging.request")
public record RequestLoggingProperties(
    Boolean logQuery,
    Boolean logBody,
    Boolean logResponseBody,
    Integer maxBodyChars,
    List<String> maskedFields) {

  public static final RequestLoggingProperties DEFAULTS =
      new RequestLoggingProperties(null, null, null, null, null);

  public RequestLoggingProperties {
    logQuery = logQuery == null || logQuery;
    logBody = logBody == null || logBody;
    logResponseBody = logResponseBody == null || logResponseBody;
    maxBodyChars = maxBodyChars == null || maxBodyChars < 0 ? 2000 : maxBodyChars;
    maskedFields = maskedFields == null ? List.of() : List.copyOf(maskedFields);
  }

  /** Bytes captured from the wire (UTF-8 can take up to 4 bytes per char). */
  public int maxCaptureBytes() {
    return maxBodyChars * 4;
  }
}
