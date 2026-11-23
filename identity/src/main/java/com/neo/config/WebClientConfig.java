package com.neo.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

  public final WebClientProperties webClientProperties;
  private static final String CORRELATION_ID_KEY = "correlationId";
  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  public WebClientConfig(WebClientProperties webClientProperties) {
    this.webClientProperties = webClientProperties;
  }

  @Bean
  public WebClient webClient() {

    HttpClient httpClient =
        HttpClient.create(connectionProvider())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, webClientProperties.getConnect())
            .responseTimeout(Duration.ofMillis(webClientProperties.getResponse()))
            .doOnConnected(
                conn ->
                    // Values are milliseconds; the int constructors would read them as seconds.
                    conn.addHandlerLast(
                            new ReadTimeoutHandler(
                                webClientProperties.getRead(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(
                            new WriteTimeoutHandler(
                                webClientProperties.getWrite(), TimeUnit.MILLISECONDS)));

    return WebClient.builder()
        .filter(correlationIdExchangeFilter())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
        .build();
  }

  /**
   * Drops idle pooled connections before Keycloak/proxies close them, so a request never goes out
   * on a dead connection ("Connection prematurely closed BEFORE response").
   */
  private ConnectionProvider connectionProvider() {
    return ConnectionProvider.builder("keycloak")
        .maxIdleTime(Duration.ofSeconds(20))
        .maxLifeTime(Duration.ofMinutes(5))
        .evictInBackground(Duration.ofSeconds(30))
        .build();
  }

  private ExchangeFilterFunction correlationIdExchangeFilter() {
    return ExchangeFilterFunction.ofRequestProcessor(
        clientRequest ->
            Mono.deferContextual(
                ctx -> {
                  String correlationId = ctx.getOrDefault(CORRELATION_ID_KEY, "");
                  MDC.put(CORRELATION_ID_KEY, correlationId);

                  ClientRequest newRequest =
                      ClientRequest.from(clientRequest)
                          .header(CORRELATION_HEADER, correlationId)
                          .build();

                  return Mono.just(newRequest);
                }));
  }
}
