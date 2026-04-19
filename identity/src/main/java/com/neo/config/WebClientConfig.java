package com.neo.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

  public final WebClientProperties webClientProperties;

  public WebClientConfig(WebClientProperties webClientProperties) {
    this.webClientProperties = webClientProperties;
  }

  @Bean
  public WebClient webClient() {

    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, webClientProperties.getConnect())
            .responseTimeout(Duration.ofMillis(webClientProperties.getResponse()))
            .doOnConnected(
                conn ->
                    conn.addHandlerLast(new ReadTimeoutHandler(webClientProperties.getRead()))
                        .addHandlerLast(new WriteTimeoutHandler(webClientProperties.getWrite())));

    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
        .build();
  }
}
