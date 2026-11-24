package com.neo.logging.context;

import com.neo.logging.CorrelationId;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

public class CorrelationTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    // Scheduled tasks are decorated once at schedule time, so the id is resolved per execution.
    String submitted = MDC.get(CorrelationId.MDC_KEY);
    String captured = CorrelationId.isValid(submitted) ? submitted : null;
    return () ->
        CorrelationContext.runWith(
            captured != null ? captured : CorrelationId.generate(), runnable);
  }
}
