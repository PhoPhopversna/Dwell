package com.neo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.neo.repository.RouteRepository;
import com.neo.service.impl.RateLimitServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import reactor.core.publisher.Flux;

class SchedulingConfigTest {

  @Test
  void pathPatternCacheRefreshIsActuallyScheduledEvery60Seconds() {
    RouteRepository repo = mock(RouteRepository.class);
    org.mockito.Mockito.when(repo.findAllByPathPatternIsNotNull()).thenReturn(Flux.empty());

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(SchedulingConfig.class)
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(RouteRepository.class, () -> repo)
        .withBean(RateLimitServiceImpl.class)
        .run(
            ctx -> {
              var tasks =
                  ctx.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                      .flatMap(h -> h.getScheduledTasks().stream())
                      .map(t -> t.getTask())
                      .filter(t -> t.toString().contains("refreshPatternCache"))
                      .toList();
              assertThat(tasks).hasSize(1);
              assertThat(tasks.get(0)).isInstanceOf(FixedDelayTask.class);
              assertThat(((FixedDelayTask) tasks.get(0)).getIntervalDuration().toMillis())
                  .isEqualTo(60_000);
            });
  }
}
