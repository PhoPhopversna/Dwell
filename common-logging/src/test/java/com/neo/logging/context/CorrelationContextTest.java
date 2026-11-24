package com.neo.logging.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.neo.logging.CorrelationId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationContextTest {

  private final ExecutorService pool = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    MDC.clear();
  }

  private String mdcOnPoolThread() throws Exception {
    return pool.submit(() -> MDC.get(CorrelationId.MDC_KEY)).get();
  }

  @Test
  void completableFutureOnCommonPoolGetsCallersId() {
    MDC.put(CorrelationId.MDC_KEY, "req-cf");
    String seen =
        CompletableFuture.supplyAsync(
                CorrelationContext.wrapSupplier(() -> MDC.get(CorrelationId.MDC_KEY)))
            .join();
    assertThat(seen).isEqualTo("req-cf");
  }

  @Test
  void wrappedExecutorCarriesIdAndCleansUpThePooledThread() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "req-ex");
    String seen =
        CompletableFuture.supplyAsync(
                () -> MDC.get(CorrelationId.MDC_KEY), CorrelationContext.wrapExecutor(pool))
            .get();
    assertThat(seen).isEqualTo("req-ex");
    assertThat(mdcOnPoolThread()).isNull(); // nothing left behind for the next task
  }

  @Test
  void callableAndRunnableAreWrappedToo() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "req-call");
    assertThat(
            pool.submit(CorrelationContext.wrapCallable(() -> MDC.get(CorrelationId.MDC_KEY)))
                .get())
        .isEqualTo("req-call");
    String[] fromRunnable = new String[1];
    pool.submit(
            CorrelationContext.wrap(
                () -> {
                  fromRunnable[0] = MDC.get(CorrelationId.MDC_KEY);
                }))
        .get();
    assertThat(fromRunnable[0]).isEqualTo("req-call");
  }

  @Test
  void noCallerIdMeansANewIdForTheTask() throws Exception {
    String seen =
        pool.submit(CorrelationContext.wrapCallable(() -> MDC.get(CorrelationId.MDC_KEY))).get();
    assertThat(CorrelationId.isValid(seen)).isTrue();
  }

  @Test
  void callerMdcIsUntouched() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "caller");
    CorrelationContext.runWith("other", () -> {});
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("caller");
  }
}
