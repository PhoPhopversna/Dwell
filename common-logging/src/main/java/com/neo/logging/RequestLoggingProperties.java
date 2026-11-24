package com.neo.logging;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

  /** UTF-8 can take up to 4 bytes per char. */
  public int maxCaptureBytes() {
    return maxBodyChars * 4;
  }
}
