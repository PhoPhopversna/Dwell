package com.neo.controller;

import com.neo.dto.ApiResponse;
import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.service.RouteService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

  @Autowired private RouteService routeService;

  @GetMapping
  public Mono<ApiResponse<List<RouteResponse>>> getRoutes() {
    return routeService.getRoutes().collectList().map(routeResponse -> ApiResponse.ok("Successfully", routeResponse));
  }

  @GetMapping("/{id}")
  public Mono<ApiResponse<RouteResponse>> getRoute(@PathVariable Long id) {
    return routeService
        .getRoute(id)
        .map(routeResponse -> ApiResponse.ok("Successfully", routeResponse));
  }

  @DeleteMapping("/{id}")
  public Mono<ApiResponse<String>> deleteRoute(@PathVariable Long id) {
    return routeService.deleteRoute(id).then(Mono.just(ApiResponse.ok("Delete Successfully")));
  }

  @PostMapping
  public Mono<ApiResponse<RouteResponse>> postRoute(@RequestBody RouteRequest routeRequest) {
    return routeService
        .saveRoute(routeRequest)
        .map(routeResponse -> ApiResponse.ok("Successfully", routeResponse));
  }

  @PutMapping("/{id}")
  public Mono<ApiResponse<RouteResponse>> updateRoute(
      @PathVariable Long id, @RequestBody RouteRequest routeRequest) {
    return routeService
        .updateRoute(id, routeRequest)
        .map(routeResponse -> ApiResponse.ok("Successfully", routeResponse));
  }
}
