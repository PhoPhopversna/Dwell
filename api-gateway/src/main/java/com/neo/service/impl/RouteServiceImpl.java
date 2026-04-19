package com.neo.service.impl;

import com.neo.constant.RouteConstant;
import com.neo.dto.RouteRequest;
import com.neo.dto.RouteResponse;
import com.neo.exception.DataBaseException;
import com.neo.exception.RouteNotFoundException;
import com.neo.mapper.RouteMapper;
import com.neo.repository.RouteRepository;
import com.neo.service.RouteReloadService;
import com.neo.service.RouteService;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
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
    log.info("Request data : {}", routeRequest);
    return routeRepository
        .save(routeMapper.toRoute(routeRequest))
        .map(routeMapper::toRouteResponse)
        .onErrorMap(
            e -> {
              log.error("Failed to save data: {}" + e.getMessage());
              return new DataBaseException(
                  HttpStatus.INTERNAL_SERVER_ERROR,
                  "00030",
                  "Failed to save data " + e.getLocalizedMessage());
            });
  }

  @Override
  public Flux<RouteResponse> getRoutes() {
    return routeRepository
        .findAll()
        .map(routeMapper::toRouteResponse)
        .onErrorMap(
            e -> {
              log.error("Failed to get route: {}" + e.getMessage());
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
              System.out.println(r);
              return routeRepository.deleteById(r.getId());
            })
        .doFinally(signal -> log.info("Signal: {}", signal))
        .doOnError(e -> log.error("Error: ", e));
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
        .onErrorResume(
            e -> {
              log.error("Failed to delete route: {}" + e.getMessage());
              return Mono.error(
                  new DataBaseException(
                      HttpStatus.INTERNAL_SERVER_ERROR,
                      "00030",
                      "Failed to get data " + e.getLocalizedMessage()));
            });
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
            LocalDateTime.now(),
            RouteConstant.SYSTEM)
        .switchIfEmpty(
            Mono.error(
                new RouteNotFoundException(
                    HttpStatus.NOT_FOUND,
                    "404",
                    String.format("Route with id %s is not found", id))))
        .doOnSuccess(consumer -> routeReloadService.reloadRoutes())
        .map(routeMapper::toRouteResponse);
  }
}
;
