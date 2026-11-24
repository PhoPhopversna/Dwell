package com.neo.logging.http;

import com.neo.logging.CorrelationId;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

public class CorrelationIdExchangeFilterFunction implements ExchangeFilterFunction {

  @Override
  public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
    return Mono.deferContextual(
        ctx -> {
          String id = ctx.getOrDefault(CorrelationId.MDC_KEY, MDC.get(CorrelationId.MDC_KEY));
          if (id == null || request.headers().containsKey(CorrelationId.HEADER)) {
            return next.exchange(request);
          }
          return next.exchange(
              ClientRequest.from(request).header(CorrelationId.HEADER, id).build());
        });
  }
}
