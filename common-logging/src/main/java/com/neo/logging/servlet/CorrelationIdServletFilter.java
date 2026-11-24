package com.neo.logging.servlet;

import com.neo.logging.AccessLogger;
import com.neo.logging.BoundedCapture;
import com.neo.logging.CorrelationId;
import com.neo.logging.RequestDataMasker;
import com.neo.logging.RequestLoggingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * First filter of every servlet service (product-catalog): takes the {@code X-Correlation-ID}
 * forwarded by the gateway (or generates one as a last resort), puts it in the MDC for the whole
 * request, returns it on the response, writes one access-log line (with the masked request and
 * response data), and always clears the MDC so pooled threads never leak an id into the next
 * request.
 *
 * <p>Error dispatches ({@code /error}) are not filtered again, so a failing request is logged once.
 */
public class CorrelationIdServletFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdServletFilter.class);

  private final RequestLoggingProperties properties;
  private final RequestDataMasker masker;

  public CorrelationIdServletFilter() {
    this(RequestLoggingProperties.DEFAULTS);
  }

  public CorrelationIdServletFilter(RequestLoggingProperties properties) {
    this.properties = properties;
    this.masker = new RequestDataMasker(properties);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    String incoming = request.getHeader(CorrelationId.HEADER);
    String id = CorrelationId.resolve(incoming);
    String method = request.getMethod();
    String path = request.getRequestURI();

    String contentType = request.getContentType();
    String rawQuery = properties.logQuery() ? request.getQueryString() : null;
    // Caches (up to the cap) only what the handler actually reads; multipart/binary not wrapped.
    ContentCachingRequestWrapper caching =
        properties.logBody() && RequestDataMasker.isLoggableContentType(contentType)
            ? new ContentCachingRequestWrapper(request, properties.maxCaptureBytes())
            : null;

    // Copies (up to the cap) what the controller writes, while it goes to the client unchanged.
    BodyCapturingResponse capturingResponse =
        properties.logResponseBody()
            ? new BodyCapturingResponse(response, properties.maxCaptureBytes())
            : null;

    String previous = MDC.get(CorrelationId.MDC_KEY);
    MDC.put(CorrelationId.MDC_KEY, id);
    request.setAttribute(CorrelationId.MDC_KEY, id);
    response.setHeader(CorrelationId.HEADER, id);
    Throwable failure = null;
    try {
      if (incoming != null && !incoming.equals(id)) {
        LOG.warn("Invalid {} header replaced with a generated id", CorrelationId.HEADER);
      }
      AccessLogger.requestReceived(method, path);
      chain.doFilter(
          caching != null ? caching : request,
          capturingResponse != null ? capturingResponse : response);
    } catch (IOException | ServletException | RuntimeException | Error e) {
      failure = e;
      throw e;
    } finally {
      int status = failure != null && response.getStatus() < 500 ? 500 : response.getStatus();
      AccessLogger.requestCompleted(
          method,
          path,
          status,
          (System.nanoTime() - start) / 1_000_000,
          failure,
          requestData(rawQuery, contentType, caching),
          responseData(capturingResponse));
      if (previous == null) {
        MDC.remove(CorrelationId.MDC_KEY);
      } else {
        MDC.put(CorrelationId.MDC_KEY, previous);
      }
    }
  }

  private AccessLogger.RequestData requestData(
      String rawQuery, String contentType, ContentCachingRequestWrapper caching) {
    String body = null;
    if (caching != null) {
      byte[] bytes = caching.getContentAsByteArray();
      if (bytes.length > 0) {
        boolean cut =
            caching.getContentLength() > bytes.length
                || bytes.length >= properties.maxCaptureBytes();
        body = masker.maskBody(new String(bytes, StandardCharsets.UTF_8), contentType, cut);
      }
    }
    return new AccessLogger.RequestData(masker.maskQuery(rawQuery), contentType, body);
  }

  private AccessLogger.ResponseData responseData(BodyCapturingResponse capturing) {
    if (capturing == null) {
      return AccessLogger.ResponseData.NONE;
    }
    String contentType = capturing.getContentType();
    if (!RequestDataMasker.isLoggableResponseContentType(contentType)
        || RequestDataMasker.isEncoded(capturing.getHeader("Content-Encoding"))) {
      return AccessLogger.ResponseData.NONE;
    }
    String body =
        masker.maskBody(
            capturing.capture.text(contentType), contentType, capturing.capture.truncated());
    return new AccessLogger.ResponseData(contentType, body);
  }

  /**
   * Tees the response body into a {@link BoundedCapture}. Nothing is buffered: every byte or char
   * goes straight to the real response, so streaming, flushing and Content-Length behave as before.
   */
  static final class BodyCapturingResponse extends HttpServletResponseWrapper {

    final BoundedCapture capture;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    BodyCapturingResponse(HttpServletResponse response, int maxBytes) {
      super(response);
      this.capture = new BoundedCapture(maxBytes);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
      if (outputStream == null) {
        outputStream = new TeeOutputStream(super.getOutputStream(), capture);
      }
      return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
      if (writer == null) {
        writer = new TeeWriter(super.getWriter(), capture, getCharacterEncoding());
      }
      return writer;
    }
  }

  private static final class TeeOutputStream extends ServletOutputStream {

    private final ServletOutputStream delegate;
    private final BoundedCapture capture;

    TeeOutputStream(ServletOutputStream delegate, BoundedCapture capture) {
      this.delegate = delegate;
      this.capture = capture;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      capture.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      capture.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setWriteListener(WriteListener listener) {
      delegate.setWriteListener(listener);
    }
  }

  /** Writes to the container's own writer; captures the chars as bytes of the response charset. */
  private static final class TeeWriter extends PrintWriter {

    private final BoundedCapture capture;
    private final java.nio.charset.Charset charset;

    TeeWriter(PrintWriter delegate, BoundedCapture capture, String characterEncoding) {
      super(delegate);
      this.capture = capture;
      java.nio.charset.Charset cs;
      try {
        cs = java.nio.charset.Charset.forName(characterEncoding);
      } catch (RuntimeException e) {
        cs = StandardCharsets.UTF_8;
      }
      this.charset = cs;
    }

    @Override
    public void write(int c) {
      super.write(c);
      record(String.valueOf((char) c));
    }

    @Override
    public void write(char[] buf, int off, int len) {
      super.write(buf, off, len);
      record(new String(buf, off, len));
    }

    @Override
    public void write(String s, int off, int len) {
      super.write(s, off, len);
      record(s.substring(off, off + len));
    }

    private void record(String text) {
      if (capture.remaining() <= 0) {
        if (!text.isEmpty()) {
          capture.markTruncated();
        }
        return;
      }
      byte[] bytes = text.getBytes(charset);
      capture.write(bytes, 0, bytes.length);
    }
  }
}
