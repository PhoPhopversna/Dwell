package com.neo.logging.reactive;

import com.neo.logging.CorrelationId;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;

public final class ReactorMdcPropagation {

  private static final AtomicBoolean INSTALLED = new AtomicBoolean();

  private ReactorMdcPropagation() {}

  /** Idempotent; call once at startup. */
  public static void install() {
    if (INSTALLED.compareAndSet(false, true)) {
      ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcAccessor());
      Hooks.enableAutomaticContextPropagation();
    }
  }

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
