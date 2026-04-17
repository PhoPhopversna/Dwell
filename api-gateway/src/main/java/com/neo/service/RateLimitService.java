package com.neo.service;

import reactor.core.publisher.Mono;

public interface RateLimitService {
  public Mono<Boolean> verifyRatingLimit(String path, String method, String identifier);

  public void refreshPatternCache();
}
