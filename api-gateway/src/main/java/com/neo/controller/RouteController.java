package com.neo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.service.RouteService;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

  @Autowired private RouteService routeService;

  @GetMapping
  public ResponseEntity<Flux<RouteResponse>> getRoutes() {
    Flux<RouteResponse> routes = routeService.getRoutes();
    return ResponseEntity.ok(routes);
  }

  @GetMapping("/{id}")
  public ResponseEntity<Mono<RouteResponse>> getRoute(@PathVariable Long id) {
    Mono<RouteResponse> route = routeService.getRoute(id);
    return ResponseEntity.ok(route);
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteRoute(@PathVariable Long id) {
    return routeService.deleteRoute(id)
            .then(Mono.just(ResponseEntity.noContent().build()));
  }

  @PostMapping
  public ResponseEntity<Mono<RouteResponse>> postRoute(@RequestBody RouteRequest routeRequest) {
    Mono<RouteResponse> route = routeService.saveRoute(routeRequest);
    return ResponseEntity.ok(route);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Mono<RouteResponse>> updateRoute(
      @PathVariable Long id, @RequestBody RouteRequest routeRequest) {
    Mono<RouteResponse> route = routeService.updateRoute(id, routeRequest);
    return ResponseEntity.ok(route);
  }
}
