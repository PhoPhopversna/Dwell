package com.neo.logging.context;

import com.neo.logging.CorrelationId;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Applied automatically (see {@code CorrelationLoggingAutoConfiguration}) to:
 *
 * <ul>
 *   <li>the {@code applicationTaskExecutor} used by {@code @Async} and injected {@code
 *       TaskExecutor}s: the task runs with the id of the request that submitted it;
 *   <li>the {@code @Scheduled} task scheduler: there is no request, so each execution gets its own
 *       new id and all logs of one run can be found together.
 * </ul>
 */
public class CorrelationTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    // Captured on the submitting thread. Scheduled tasks are decorated once at schedule time, when
    // no id exists, so the id is resolved per execution instead.
    String submitted = MDC.get(CorrelationId.MDC_KEY);
    String captured = CorrelationId.isValid(submitted) ? submitted : null;
    return () ->
        CorrelationContext.runWith(
            captured != null ? captured : CorrelationId.generate(), runnable);
  }
}
