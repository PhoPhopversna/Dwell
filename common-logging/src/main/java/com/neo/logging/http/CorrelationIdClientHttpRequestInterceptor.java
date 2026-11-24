package com.neo.logging.http;

import com.neo.logging.CorrelationId;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class CorrelationIdClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String id = MDC.get(CorrelationId.MDC_KEY);
    if (id != null && !request.getHeaders().containsKey(CorrelationId.HEADER)) {
      request.getHeaders().set(CorrelationId.HEADER, id);
    }
    return execution.execute(request, body);
  }
}
