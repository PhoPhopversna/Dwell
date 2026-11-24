package com.neo.logging.context;

import com.neo.logging.CorrelationId;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.MDC;

public final class CorrelationContext {

  private CorrelationContext() {}

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

  public static Executor wrapExecutor(Executor executor) {
    return task -> executor.execute(wrap(task));
  }

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

  /** The caller's id, or a new one so every background run is still traceable. */
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
