package com.neo.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.neo.logging.CorrelationId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Boots Spring Boot's real task/client auto-configuration plus ours, exactly as a service does, and
 * checks that plain {@code @Async}, {@code @Scheduled}, and clients built from Boot's builders
 * carry the correlation id with no extra code.
 */
class CorrelationLoggingAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CorrelationLoggingAutoConfiguration.class,
                  TaskExecutionAutoConfiguration.class,
                  TaskSchedulingAutoConfiguration.class,
                  WebClientAutoConfiguration.class,
                  RestTemplateAutoConfiguration.class,
                  RestClientAutoConfiguration.class));

  @AfterEach
  void clear() {
    MDC.clear();
  }

  // ---------- @Async ----------

  @Configuration
  @EnableAsync
  static class AsyncConfig {
    @Bean
    AsyncWorker worker() {
      return new AsyncWorker();
    }
  }

  static class AsyncWorker {
    @Async
    public CompletableFuture<String> work() {
      return CompletableFuture.completedFuture(
          Thread.currentThread().getName() + "|" + MDC.get(CorrelationId.MDC_KEY));
    }
  }

  @Test
  void plainAsyncMethodRunsWithTheRequestsId() {
    runner
        .withUserConfiguration(AsyncConfig.class)
        .run(
            ctx -> {
              MDC.put(CorrelationId.MDC_KEY, "req-async-1");
              String[] result =
                  ctx.getBean(AsyncWorker.class).work().get(5, TimeUnit.SECONDS).split("\\|");
              assertThat(result[0])
                  .startsWith("task-"); // really ran on Boot's applicationTaskExecutor
              assertThat(result[1]).isEqualTo("req-async-1");
            });
  }

  @Test
  void injectedTaskExecutorCarriesIdAndLeavesNothingBehind() {
    runner.run(
        ctx -> {
          TaskExecutor executor = ctx.getBean("applicationTaskExecutor", TaskExecutor.class);
          AtomicReference<String> seen = new AtomicReference<>();
          MDC.put(CorrelationId.MDC_KEY, "req-exec");
          CompletableFuture<Void> done = new CompletableFuture<>();
          executor.execute(
              () -> {
                seen.set(MDC.get(CorrelationId.MDC_KEY));
                done.complete(null);
              });
          done.get(5, TimeUnit.SECONDS);
          assertThat(seen.get()).isEqualTo("req-exec");

          MDC.clear(); // next task submitted without a request: new id, never the stale one
          CompletableFuture<String> next = new CompletableFuture<>();
          executor.execute(() -> next.complete(MDC.get(CorrelationId.MDC_KEY)));
          assertThat(next.get(5, TimeUnit.SECONDS)).isNotEqualTo("req-exec");
        });
  }

  // ---------- @Scheduled ----------

  @Configuration
  @EnableScheduling
  static class SchedulingConfig {
    static final BlockingQueue<String> RUNS = new LinkedBlockingQueue<>();

    @Scheduled(fixedDelay = 20)
    void job() {
      RUNS.add(String.valueOf(MDC.get(CorrelationId.MDC_KEY)));
    }
  }

  @Test
  void eachScheduledRunGetsItsOwnId() {
    runner
        .withUserConfiguration(SchedulingConfig.class)
        .run(
            ctx -> {
              String first = SchedulingConfig.RUNS.poll(5, TimeUnit.SECONDS);
              String second = SchedulingConfig.RUNS.poll(5, TimeUnit.SECONDS);
              assertThat(CorrelationId.isValid(first)).isTrue();
              assertThat(CorrelationId.isValid(second)).isTrue();
              assertThat(second).isNotEqualTo(first);
            });
  }

  // ---------- HTTP clients ----------

  @Test
  void restTemplateFromBuilderSendsTheHeader() {
    runner.run(
        ctx -> {
          RestTemplate rest = ctx.getBean(RestTemplateBuilder.class).build();
          MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
          server
              .expect(requestTo("http://inventory/api/stock"))
              .andExpect(header(CorrelationId.HEADER, "req-rt"))
              .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
          server
              .expect(requestTo("http://inventory/api/stock"))
              .andExpect(headerDoesNotExist(CorrelationId.HEADER))
              .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

          MDC.put(CorrelationId.MDC_KEY, "req-rt");
          rest.getForObject("http://inventory/api/stock", String.class);
          MDC.clear(); // outside a request nothing is invented
          rest.getForObject("http://inventory/api/stock", String.class);
          server.verify();
        });
  }

  @Test
  void restClientFromBuilderSendsTheHeader_withoutOverridingAnExplicitOne() {
    runner.run(
        ctx -> {
          RestClient.Builder builder = ctx.getBean(RestClient.Builder.class);
          MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
          RestClient client = builder.build();
          server
              .expect(requestTo("http://orders/api/x"))
              .andExpect(header(CorrelationId.HEADER, "req-rc"))
              .andRespond(withSuccess());
          server
              .expect(requestTo("http://orders/api/x"))
              .andExpect(header(CorrelationId.HEADER, "explicit"))
              .andRespond(withSuccess());

          MDC.put(CorrelationId.MDC_KEY, "req-rc");
          client.get().uri("http://orders/api/x").retrieve().toBodilessEntity();
          client
              .get()
              .uri("http://orders/api/x")
              .header(CorrelationId.HEADER, "explicit")
              .retrieve()
              .toBodilessEntity();
          server.verify();
        });
  }

  @Test
  void webClientFromBuilderSendsTheHeader_fromReactorContextOrMdc() {
    runner.run(
        ctx -> {
          List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
          WebClient client =
              ctx.getBean(WebClient.Builder.class)
                  .exchangeFunction(
                      req -> {
                        sent.add(String.valueOf(req.headers().getFirst(CorrelationId.HEADER)));
                        return Mono.just(
                            ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
                      })
                  .build();

          // reactive service: id lives in the Reactor context
          client
              .get()
              .uri("http://catalog/api")
              .retrieve()
              .toBodilessEntity()
              .contextWrite(Context.of(CorrelationId.MDC_KEY, "req-reactive"))
              .block(Duration.ofSeconds(5));
          // servlet service calling .block(): id lives in the MDC
          MDC.put(CorrelationId.MDC_KEY, "req-servlet");
          client
              .get()
              .uri("http://catalog/api")
              .retrieve()
              .toBodilessEntity()
              .block(Duration.ofSeconds(5));
          MDC.clear();
          client
              .get()
              .uri("http://catalog/api")
              .retrieve()
              .toBodilessEntity()
              .block(Duration.ofSeconds(5));

          assertThat(sent).containsExactly("req-reactive", "req-servlet", "null");
        });
  }
}
