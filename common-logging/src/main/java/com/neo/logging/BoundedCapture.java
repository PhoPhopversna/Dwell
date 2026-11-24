package com.neo.logging;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copy of the first {@code maxBytes} of a body that is streamed to its real consumer. Used to log
 * request and response bodies without buffering them: the consumer always gets every byte, the log
 * gets at most {@code maxBytes}, and {@link #truncated()} says whether more was sent.
 */
public final class BoundedCapture {

  private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset=\"?([^\";\\s]+)");

  private final int maxBytes;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private volatile boolean truncated;

  public BoundedCapture(int maxBytes) {
    this.maxBytes = Math.max(maxBytes, 0);
  }

  public synchronized void write(int b) {
    if (bytes.size() < maxBytes) {
      bytes.write(b);
    } else {
      truncated = true;
    }
  }

  public synchronized void write(byte[] b, int off, int len) {
    int length = Math.min(Math.max(maxBytes - bytes.size(), 0), len);
    bytes.write(b, off, length);
    if (length < len) {
      truncated = true;
    }
  }

  public synchronized int size() {
    return bytes.size();
  }

  /** Bytes that can still be captured before the cap. */
  public synchronized int remaining() {
    return maxBytes - bytes.size();
  }

  /** Records that the body continued beyond what was captured. */
  public void markTruncated() {
    truncated = true;
  }

  public boolean truncated() {
    return truncated;
  }

  /** The captured bytes as text in the content type's charset (UTF-8 if none); null if empty. */
  public synchronized String text(String contentType) {
    return bytes.size() == 0 ? null : bytes.toString(charsetOf(contentType));
  }

  static Charset charsetOf(String contentType) {
    if (contentType != null) {
      Matcher m = CHARSET.matcher(contentType);
      if (m.find()) {
        try {
          return Charset.forName(m.group(1).toLowerCase(Locale.ROOT));
        } catch (RuntimeException unknownCharset) {
          // fall through
        }
      }
    }
    return StandardCharsets.UTF_8;
  }
}
