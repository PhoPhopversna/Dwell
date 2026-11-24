package com.neo.config;

import com.neo.logging.http.CorrelationIdExchangeFilterFunction;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

  public final WebClientProperties webClientProperties;

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
        .filter(new CorrelationIdExchangeFilterFunction())
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
}
