package com.neo.logging.context;

import com.neo.logging.CorrelationId;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Carries the current correlation id to work that runs on another thread.
 *
 * <p>Spring-managed pools ({@code @Async}, the auto-configured {@code applicationTaskExecutor},
 * {@code @Scheduled}) are already covered by {@link CorrelationTaskDecorator}. Use this class for
 * everything else, for example {@code CompletableFuture} on the common pool or your own executor:
 *
 * <pre>{@code
 * CompletableFuture.supplyAsync(CorrelationContext.wrapSupplier(() -> load(id)));
 * CompletableFuture.runAsync(task, CorrelationContext.wrapExecutor(myExecutor));
 * executor.submit(CorrelationContext.wrapCallable(() -> compute()));
 * new Thread(CorrelationContext.wrap(() -> work())).start();
 * }</pre>
 *
 * <p>The worker thread gets the caller's id (or a new one if the caller had none) while the task
 * runs, and its previous MDC value is restored afterwards, so pooled threads never leak an id.
 */
public final class CorrelationContext {

  private CorrelationContext() {}

  /** The current thread's correlation id, or {@code null}. */
  public static String current() {
    return MDC.get(CorrelationId.MDC_KEY);
  }

  public static Runnable wrap(Runnable task) {
    String id = captureOrGenerate();
    return () -> runWith(id, task);
  }

  public static <T> Callable<T> wrapCallable(Callable<T> task) {
    String id = captureOrGenerate();
    return () -> callWith(id, task);
  }

  public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
    String id = captureOrGenerate();
    return () -> {
      try {
        return callWith(id, task::get);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e); // unreachable: Supplier cannot throw checked
      }
    };
  }

  /** An executor whose tasks all carry the submitting thread's correlation id. */
  public static Executor wrapExecutor(Executor executor) {
    return task -> executor.execute(wrap(task));
  }

  /** Runs {@code task} with {@code id} in the MDC, then restores the thread's previous value. */
  public static void runWith(String id, Runnable task) {
    String previous = MDC.get(CorrelationId.MDC_KEY);
    MDC.put(CorrelationId.MDC_KEY, id);
    try {
      task.run();
    } finally {
      restore(previous);
    }
  }

  static <T> T callWith(String id, Callable<T> task) throws Exception {
    String previous = MDC.get(CorrelationId.MDC_KEY);
    MDC.put(CorrelationId.MDC_KEY, id);
    try {
      return task.call();
    } finally {
      restore(previous);
    }
  }

  /**
   * The caller's id; a new one when there is none (e.g. a scheduler thread), so every background
   * run is still traceable as a unit.
   */
  static String captureOrGenerate() {
    String id = MDC.get(CorrelationId.MDC_KEY);
    return CorrelationId.isValid(id) ? id : CorrelationId.generate();
  }

  private static void restore(String previous) {
    if (previous == null) {
      MDC.remove(CorrelationId.MDC_KEY);
    } else {
      MDC.put(CorrelationId.MDC_KEY, previous);
    }
  }
}
