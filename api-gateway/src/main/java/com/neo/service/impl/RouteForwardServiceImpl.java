package com.neo.service.impl;

import com.neo.entity.ApiRoute;
import com.neo.repository.RouteRepository;
import com.neo.security.RouteRoles;
import java.util.Map;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.BooleanSpec;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class RouteForwardServiceImpl implements RouteLocator {

  private final RouteRepository routeRepository;
  private final RouteLocatorBuilder routeLocatorBuilder;

  public RouteForwardServiceImpl(
      RouteRepository routeRepository, RouteLocatorBuilder routeLocatorBuilder) {
    this.routeRepository = routeRepository;
    this.routeLocatorBuilder = routeLocatorBuilder;
  }

  @Override
  public Flux<Route> getRoutes() {
    RouteLocatorBuilder.Builder builder = routeLocatorBuilder.routes();

    return routeRepository
        .findAll()
        .map(
            apiApiRoute ->
                builder.route(
                    apiApiRoute.getId().toString(),
                    predicateSpec -> setPredicateSpec(predicateSpec, apiApiRoute)))
        .collectList()
        .flatMapMany(builders -> builder.build().getRoutes());
  }

  @Override
  public Flux<Route> getRoutesByMetadata(Map<String, Object> metadata) {
    return RouteLocator.super.getRoutesByMetadata(metadata);
  }

  private Buildable<Route> setPredicateSpec(PredicateSpec predicateSpec, ApiRoute apiRoute) {
    boolean anyMethod = apiRoute.getMethod() == null || apiRoute.getMethod().isBlank();
    // Method-specific routes are matched before catch-all ones, so e.g. a GET row can grant
    // wider access than the catch-all row for the same path.
    BooleanSpec booleanSpec = predicateSpec.order(anyMethod ? 1 : 0).path(apiRoute.getPath());
    if (!anyMethod) {
      booleanSpec = booleanSpec.and().method(apiRoute.getMethod());
    }
    return booleanSpec
        .metadata(RouteRoles.METADATA_KEY, apiRoute.getRoles() == null ? "" : apiRoute.getRoles())
        .uri(apiRoute.getUri());
  }
}
