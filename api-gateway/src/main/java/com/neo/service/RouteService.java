package com.neo.service;

import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RouteService {
  Mono<RouteResponse> saveRoute(RouteRequest routeRequest);

  Flux<RouteResponse> getRoutes();

  Mono<Void> deleteRoute(Long id);

  Mono<RouteResponse> getRoute(Long id);

  Mono<RouteResponse> updateRoute(Long id, RouteRequest routeRequest);
}
