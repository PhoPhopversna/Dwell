package com.neo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods. Without it they are silently ignored: the route path-pattern
 * cache in {@code RateLimitServiceImpl} was only ever loaded once at startup, so route changes (and
 * a cache that failed to load at boot) were never picked up by rate limiting until a restart.
 *
 * <p>Each scheduled run gets its own correlation id (common-logging), so its logs can be traced.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
