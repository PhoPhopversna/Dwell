package com.neo.logging.reactive;

import com.neo.logging.AccessLogger;
import com.neo.logging.BoundedCapture;
import com.neo.logging.CorrelationId;
import com.neo.logging.RequestDataMasker;
import com.neo.logging.RequestLoggingProperties;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;

/**
 * First filter of every WebFlux service (API gateway, identity).
 *
 * <ol>
 *   <li>Reuses a valid incoming {@code X-Correlation-ID} or generates one.
 *   <li>Sets it on the request (so the gateway forwards exactly this id downstream and any code
 *       reading the header sees it), in the Reactor context (-> MDC on every thread, see {@link
 *       ReactorMdcPropagation}) and on the response.
 *   <li>Writes one access-log line when the request finishes, including failures, with the masked
 *       request and response data.
 * </ol>
 */
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

  private final RequestLoggingProperties properties;
  private final RequestDataMasker masker;

  public CorrelationIdWebFilter() {
    this(RequestLoggingProperties.DEFAULTS);
  }

  public CorrelationIdWebFilter(RequestLoggingProperties properties) {
    this.properties = properties;
    this.masker = new RequestDataMasker(properties);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    long start = System.nanoTime();
    ServerHttpRequest request = exchange.getRequest();
    String incoming = request.getHeaders().getFirst(CorrelationId.HEADER);
    String id = CorrelationId.resolve(incoming);
    String method = request.getMethod().name();
    String path = request.getPath().value();

    ServerHttpRequest withId =
        request.mutate().headers(h -> h.set(CorrelationId.HEADER, id)).build();
    String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    BodyCapturingRequest capturing =
        properties.logBody() && RequestDataMasker.isLoggableContentType(contentType)
            ? new BodyCapturingRequest(withId, properties.maxCaptureBytes())
            : null;
    BodyCapturingResponse capturingResponse =
        properties.logResponseBody()
            ? new BodyCapturingResponse(exchange.getResponse(), properties.maxCaptureBytes())
            : null;
    ServerWebExchange.Builder builder =
        exchange.mutate().request(capturing != null ? capturing : withId);
    if (capturingResponse != null) {
      builder.response(capturingResponse);
    }
    ServerWebExchange tracked = builder.build();
    String rawQuery = properties.logQuery() ? request.getURI().getRawQuery() : null;
    tracked.getAttributes().put(CorrelationId.MDC_KEY, id);
    tracked
        .getResponse()
        .beforeCommit(
            () -> {
              // set() so a downstream service's own header is not duplicated
              tracked.getResponse().getHeaders().set(CorrelationId.HEADER, id);
              return Mono.empty();
            });

    withMdc(
        id,
        () -> {
          if (incoming != null && !incoming.equals(id)) {
            LOG.warn("Invalid {} header replaced with a generated id", CorrelationId.HEADER);
          }
          AccessLogger.requestReceived(method, path);
        });

    // doOnSuccess/doOnError/doOnCancel run before the signal reaches the subscriber (unlike
    // doFinally), so the completion line is always written before the request is done.
    return chain
        .filter(tracked)
        .doOnSuccess(
            v ->
                withMdc(
                    id,
                    () ->
                        logCompletion(
                            tracked,
                            method,
                            path,
                            start,
                            SignalType.ON_COMPLETE,
                            null,
                            requestData(rawQuery, contentType, capturing),
                            responseData(capturingResponse))))
        .doOnError(
            e ->
                withMdc(
                    id,
                    () ->
                        logCompletion(
                            tracked,
                            method,
                            path,
                            start,
                            SignalType.ON_ERROR,
                            e,
                            requestData(rawQuery, contentType, capturing),
                            responseData(capturingResponse))))
        .doOnCancel(
            () ->
                withMdc(
                    id,
                    () ->
                        logCompletion(
                            tracked,
                            method,
                            path,
                            start,
                            SignalType.CANCEL,
                            null,
                            requestData(rawQuery, contentType, capturing),
                            responseData(capturingResponse))))
        .contextWrite(Context.of(CorrelationId.MDC_KEY, id));
  }

  private static void logCompletion(
      ServerWebExchange exchange,
      String method,
      String path,
      long start,
      SignalType signal,
      Throwable error,
      AccessLogger.RequestData request,
      AccessLogger.ResponseData response) {
    long durationMs = (System.nanoTime() - start) / 1_000_000;
    int status;
    Throwable unexpected = null;
    if (error instanceof ResponseStatusException rse) {
      // Expected HTTP outcomes (e.g. 404 no route): no stack trace unless it is a 5xx.
      status = rse.getStatusCode().value();
      unexpected = status >= 500 ? error : null;
    } else if (error != null) {
      status = 500;
      unexpected = error;
    } else if (signal == SignalType.CANCEL) {
      status = 499; // client closed the connection
    } else {
      HttpStatusCode code = exchange.getResponse().getStatusCode();
      status = code != null ? code.value() : 200;
    }
    AccessLogger.requestCompleted(method, path, status, durationMs, unexpected, request, response);
  }

  private AccessLogger.RequestData requestData(
      String rawQuery, String contentType, BodyCapturingRequest capturing) {
    String body =
        capturing == null
            ? null
            : masker.maskBody(
                capturing.capture.text(contentType), contentType, capturing.capture.truncated());
    return new AccessLogger.RequestData(masker.maskQuery(rawQuery), contentType, body);
  }

  private AccessLogger.ResponseData responseData(BodyCapturingResponse capturing) {
    if (capturing == null || !capturing.captureStarted) {
      return AccessLogger.ResponseData.NONE;
    }
    String contentType = capturing.contentType();
    String body =
        masker.maskBody(
            capturing.capture.text(contentType), contentType, capturing.capture.truncated());
    return new AccessLogger.ResponseData(contentType, body);
  }

  /** Copies the readable bytes of {@code buffer} (without consuming them) into {@code capture}. */
  private static void copy(DataBuffer buffer, BoundedCapture capture) {
    int readable = buffer.readableByteCount();
    int length = Math.min(readable, Math.max(capture.remaining(), 0));
    byte[] chunk = new byte[length];
    int position = buffer.readPosition();
    for (int i = 0; i < length; i++) {
      chunk[i] = buffer.getByte(position + i);
    }
    capture.write(chunk, 0, length);
    if (length < readable) {
      capture.markTruncated();
    }
  }

  /**
   * Copies the first {@code maxBytes} of the body as it streams to the handler or the proxied
   * service. Nothing is read ahead or buffered beyond that cap, and the body is consumed exactly
   * once by whoever normally reads it.
   */
  static final class BodyCapturingRequest extends ServerHttpRequestDecorator {

    final BoundedCapture capture;

    BodyCapturingRequest(ServerHttpRequest delegate, int maxBytes) {
      super(delegate);
      this.capture = new BoundedCapture(maxBytes);
    }

    @Override
    public Flux<DataBuffer> getBody() {
      return super.getBody().doOnNext(b -> copy(b, capture));
    }
  }

  /**
   * Copies the first {@code maxBytes} of the response body as it is written to the client (or, in
   * the gateway, as the proxied service's response is relayed). Decides when the body is written,
   * once the content type is known: binary, compressed and streaming responses pass through
   * untouched and are not logged.
   */
  static final class BodyCapturingResponse extends ServerHttpResponseDecorator {

    final BoundedCapture capture;
    volatile boolean captureStarted;

    BodyCapturingResponse(ServerHttpResponse delegate, int maxBytes) {
      super(delegate);
      this.capture = new BoundedCapture(maxBytes);
    }

    String contentType() {
      return getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
      if (!RequestDataMasker.isLoggableResponseContentType(contentType())
          || RequestDataMasker.isEncoded(getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))) {
        return super.writeWith(body);
      }
      captureStarted = true;
      return super.writeWith(Flux.from(body).doOnNext(b -> copy(b, capture)));
    }

    // writeAndFlushWith is only used for streaming responses: never captured.
  }

  /** Runs {@code action} with the id in the MDC, then restores the previous value. */
  static void withMdc(String id, Runnable action) {
    String previous = MDC.get(CorrelationId.MDC_KEY);
    MDC.put(CorrelationId.MDC_KEY, id);
    try {
      action.run();
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationId.MDC_KEY);
      } else {
        MDC.put(CorrelationId.MDC_KEY, previous);
      }
    }
  }
}
