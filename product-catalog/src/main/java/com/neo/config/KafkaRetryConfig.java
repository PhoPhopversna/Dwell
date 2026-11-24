package com.neo.config;

import java.util.function.Consumer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Non-blocking retries for the {@code @RetryableTopic} listeners (see docs/LOGGING.md). There are
 * no in-place retries on top, so each attempt happens exactly once.
 */
@Configuration
public class KafkaRetryConfig extends RetryTopicConfigurationSupport {

  private final KafkaTemplate<String, byte[]> bytesTemplate;

  public KafkaRetryConfig(ProducerFactory<?, ?> producerFactory) {
    // Records that failed deserialization only have their raw bytes: forward them as-is
    this.bytesTemplate =
        new KafkaTemplate<>(
            new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(),
                new StringSerializer(),
                new ByteArraySerializer()));
  }

  @Override
  protected Consumer<DeadLetterPublishingRecovererFactory>
      configureDeadLetterPublishingContainerFactory() {
    return factory ->
        factory.setDeadLetterPublisherCreator(
            (templateResolver, destinationResolver) ->
                new DeadLetterPublishingRecoverer(
                    record ->
                        record.value() instanceof byte[]
                            ? bytesTemplate
                            : templateResolver.apply(record),
                    destinationResolver));
  }

  @Override
  protected void configureCustomizers(CustomizersConfigurer customizers) {
    // Manual acks: commit the forwarded record, or it is forwarded again after a restart
    customizers.customizeErrorHandler(handler -> handler.setCommitRecovered(true));
  }

  @Bean
  public RetryTopicSchedulerWrapper retryTopicSchedulerWrapper() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setThreadNamePrefix("kafka-retry-");
    scheduler.initialize();
    return new RetryTopicSchedulerWrapper(scheduler);
  }
}
