package com.neo.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration
public class ReactorContextConfig {

  @PostConstruct
  public void enableContextPropagation() {
    // This will trigger Reactor to auto-propagate context (including MDC) across threads
    Hooks.enableAutomaticContextPropagation();
  }
}
