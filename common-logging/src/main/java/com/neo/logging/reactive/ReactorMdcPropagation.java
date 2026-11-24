package com.neo.logging.reactive;

import com.neo.logging.CorrelationId;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;

/**
 * Makes the correlation id in the Reactor {@code Context} show up in the SLF4J MDC on whatever
 * thread runs the next operator, and removes it when a thread is reused for work without one.
 *
 * <p>Root cause of the original bug: {@code Hooks.enableAutomaticContextPropagation()} only
 * restores ThreadLocals that have a registered {@link ThreadLocalAccessor}. None was registered for
 * the MDC key, so the id never reached the MDC on Netty / bounded-elastic threads.
 */
public final class ReactorMdcPropagation {

  private static final AtomicBoolean INSTALLED = new AtomicBoolean();

  private ReactorMdcPropagation() {}

  /** Idempotent. Call once at startup. */
  public static void install() {
    if (INSTALLED.compareAndSet(false, true)) {
      ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcAccessor());
      Hooks.enableAutomaticContextPropagation();
    }
  }

  /** Bridges the {@code correlationId} context key to the MDC entry of the same name. */
  static final class MdcAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
      return CorrelationId.MDC_KEY;
    }

    @Override
    public String getValue() {
      return MDC.get(CorrelationId.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
      MDC.put(CorrelationId.MDC_KEY, value);
    }

    @Override
    public void setValue() {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }
}
