package com.neo.service.impl;

import com.neo.repository.RouteRepository;
import com.neo.service.RateLimitService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class RateLimitServiceImpl implements RateLimitService {

  private final StringRedisTemplate stringRedisTemplate;
  private final RouteRepository routeRepository;
  private final RedisScript<Long> rateLimiterScript;

  private volatile Map<Pattern, String> pathPatternCache = new LinkedHashMap<>();

  public RateLimitServiceImpl(
      StringRedisTemplate stringRedisTemplate, RouteRepository routeRepository) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.routeRepository = routeRepository;
    this.rateLimiterScript = loadScript();
  }

  private RedisScript<Long> loadScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource("rate_limiter.lua")));
    script.setResultType(Long.class);
    return script;
  }

  @PostConstruct
  public void loadPatternCache() {
    refreshPatternCache();
  }

  @Scheduled(fixedDelay = 60_000)
  public void refreshPatternCache() {
    routeRepository
        .findAllByPathPatternIsNotNull()
        .collectList()
        .subscribe(
            routes -> {
              Map<Pattern, String> updated = new LinkedHashMap<>();
              routes.forEach(
                  route -> {
                    try {
                      Pattern compiled = Pattern.compile(route.getPathPattern());
                      updated.put(compiled, route.getNormalizedPath());
                    } catch (Exception e) {
                      log.warn(
                          "Invalid pathPattern for route {}: {}", route.getPath(), e.getMessage());
                    }
                  });
              pathPatternCache = updated;
              log.info("Path pattern cache refreshed: {} patterns loaded", updated.size());
            },
            err -> log.error("Failed to refresh path pattern cache", err));
  }

  @Override
  public Mono<Boolean> verifyRatingLimit(String path, String method, String identifier) {
    final String normalizedPath = normalizePath(path);
    log.info("=========== Verify Rate Limit =============");
    log.info("Request : {} , {}, {}", path, method, identifier);
    return routeRepository
        .findFirstByPathAndMethod(normalizedPath, method)
        .flatMap(
            routeConfig -> {
              if (routeConfig.getRateLimit() == null) {
                log.info("Here");
                return Mono.just(true);
              }

              log.info(
                  "Rate limiting — path: {}, method: {}, identifier: {}",
                  normalizedPath,
                  method,
                  identifier);

              String redisKey = String.format("%s:%s:%s", identifier, normalizedPath, method);
              log.info("Redis key: {}", redisKey);

              return Mono.fromCallable(
                      () ->
                          stringRedisTemplate.execute(
                              rateLimiterScript,
                              Collections.singletonList(redisKey),
                              routeConfig.getRateLimit().toString(),
                              routeConfig.getRateLimitDuration().toString()))
                  .subscribeOn(Schedulers.boundedElastic())
                  .map(result -> result != null && result == 1L);
            })
        .defaultIfEmpty(true);
  }

  private String normalizePath(String path) {
    for (Map.Entry<Pattern, String> entry : pathPatternCache.entrySet()) {
      String replaced = entry.getKey().matcher(path).replaceAll(entry.getValue());
      if (!replaced.equals(path)) {
        return replaced;
      }
    }
    log.debug("Normalize Path : {}", path);
    return path;
  }
}
