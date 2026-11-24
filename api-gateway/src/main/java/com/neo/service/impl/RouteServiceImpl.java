package com.neo.service.impl;

import com.neo.constant.RouteConstant;
import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.exception.AppException;
import com.neo.exception.DataBaseException;
import com.neo.exception.RouteNotFoundException;
import com.neo.mapper.RouteMapper;
import com.neo.repository.RouteRepository;
import com.neo.security.RouteRoles;
import com.neo.service.RouteReloadService;
import com.neo.service.RouteService;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class RouteServiceImpl implements RouteService {
  public final RouteRepository routeRepository;

  public final RouteMapper routeMapper;
  public final RouteReloadService routeReloadService;

  public RouteServiceImpl(
      RouteRepository routeRepository,
      RouteMapper routeMapper,
      RouteReloadService routeReloadService) {
    this.routeRepository = routeRepository;
    this.routeMapper = routeMapper;
    this.routeReloadService = routeReloadService;
  }

  @Override
  public Mono<RouteResponse> saveRoute(RouteRequest routeRequest) {
    log.debug("Saving route {} {}", routeRequest.getMethod(), routeRequest.getPath());
    return routeRepository
        .save(routeMapper.toRoute(routeRequest))
        .map(routeMapper::toRouteResponse)
        .doOnSuccess(saved -> routeReloadService.reloadRoutes())
        .onErrorMap(e -> toRouteError("save", null, e));
  }

  @Override
  public Flux<RouteResponse> getRoutes() {
    return routeRepository
        .findAll()
        .map(routeMapper::toRouteResponse)
        .onErrorMap(
            e -> {
              log.error("Failed to load routes", e);
              return new DataBaseException(
                  HttpStatus.INTERNAL_SERVER_ERROR,
                  "00030",
                  "Failed to get data " + e.getLocalizedMessage());
            });
  }

  @Override
  public Mono<Void> deleteRoute(Long id) {
    return routeRepository
        .findFirstById(id)
        .switchIfEmpty(
            Mono.error(
                new RouteNotFoundException(
                    HttpStatus.NOT_FOUND,
                    "404",
                    String.format("Route with id %s is not found", id))))
        .flatMap(
            r -> {
              log.info("Deleting route {} {} {}", r.getId(), r.getMethod(), r.getPath());
              return routeRepository.deleteById(r.getId());
            })
        .doOnSuccess(v -> routeReloadService.reloadRoutes())
        .doOnError(
            e -> !(e instanceof RouteNotFoundException),
            e -> log.error("Failed to delete route {}", id, e));
  }

  @Override
  public Mono<RouteResponse> getRoute(Long id) {
    return routeRepository
        .findFirstById(id)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new RouteNotFoundException(
                        HttpStatus.NOT_FOUND,
                        "404",
                        String.format("Route with id %s is not found", id))))
        .map(routeMapper::toRouteResponse)
        // "not found" is the expected 404, not a database failure
        .onErrorMap(e -> !(e instanceof AppException), e -> toRouteError("load", id, e));
  }

  @Override
  public Mono<RouteResponse> updateRoute(Long id, RouteRequest routeRequest) {
    return routeRepository
        .updateRoute(
            id,
            routeRequest.getUri(),
            routeRequest.getPath(),
            routeRequest.getMethod(),
            routeRequest.getDescription(),
            routeRequest.getGroupCode(),
            routeRequest.getRateLimit(),
            routeRequest.getRateLimitDuration(),
            routeRequest.getStatus(),
            routeRequest.getPathPattern(),
            routeRequest.getNormalizedPath(),
            RouteRoles.join(routeRequest.getRoles()),
            LocalDateTime.now(),
            RouteConstant.SYSTEM)
        .switchIfEmpty(
            Mono.error(
                new RouteNotFoundException(
                    HttpStatus.NOT_FOUND,
                    "404",
                    String.format("Route with id %s is not found", id))))
        .doOnSuccess(consumer -> routeReloadService.reloadRoutes())
        .map(routeMapper::toRouteResponse)
        .onErrorMap(e -> !(e instanceof AppException), e -> toRouteError("update", id, e));
  }

  /**
   * Missing required columns, values too long, or a duplicate route are the caller's input: 400
   * with the detail logged at WARN. Anything else is a database failure: 500, logged at ERROR.
   */
  private static AppException toRouteError(String action, Long id, Throwable e) {
    if (e instanceof DataIntegrityViolationException) {
      log.warn(
          "Rejected route {} (id {}): {}",
          action,
          id,
          ((DataIntegrityViolationException) e).getMostSpecificCause().getMessage());
      return new AppException(
          HttpStatus.BAD_REQUEST,
          "00031",
          "Route data is invalid (missing required fields, too long, or a duplicate route)");
    }
    log.error("Failed to {} route (id {})", action, id, e);
    return new DataBaseException(
        HttpStatus.INTERNAL_SERVER_ERROR, "00030", "Failed to " + action + " route");
  }
}
;
