package com.neo.config;

import jakarta.annotation.PostConstruct;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

@Configuration
public class ReactorConfig {

    private static final String CORRELATION_ID_KEY = "correlationId";

    @PostConstruct
    public void init() {
        Hooks.enableAutomaticContextPropagation();

        //This hooks into EVERY operator including Netty I/O threads
        Hooks.onEachOperator("mdc-context-hook", Operators.lift((scannable, subscriber) ->
                new CoreSubscriber<Object>() {
                    final ContextView ctx = subscriber.currentContext();

                    @Override
                    public Context currentContext() {
                        return subscriber.currentContext();
                    }

                    @Override
                    public void onSubscribe(Subscription s) {
                        restoreMdc();
                        subscriber.onSubscribe(s);
                    }

                    @Override
                    public void onNext(Object o) {
                        restoreMdc();
                        subscriber.onNext(o);
                    }

                    @Override
                    public void onError(Throwable t) {
                        restoreMdc(); // This covers your error log case
                        subscriber.onError(t);
                    }

                    @Override
                    public void onComplete() {
                        restoreMdc();
                        subscriber.onComplete();
                    }

                    private void restoreMdc() {
                        if (ctx.hasKey(CORRELATION_ID_KEY)) {
                            MDC.put(CORRELATION_ID_KEY, ctx.get(CORRELATION_ID_KEY));
                        } else {
                            MDC.remove(CORRELATION_ID_KEY);
                        }
                    }
                }
        ));
    }
}
