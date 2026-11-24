package com.neo.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neo.dto.RouteRequest;
import com.neo.entity.ApiRoute;
import com.neo.exception.AppException;
import com.neo.exception.DataBaseException;
import com.neo.exception.RouteNotFoundException;
import com.neo.mapper.RouteMapper;
import com.neo.repository.RouteRepository;
import com.neo.service.RouteReloadService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RouteServiceImplTest {

  private final RouteRepository repository = mock(RouteRepository.class);
  private final RouteMapper mapper = mock(RouteMapper.class);
  private final RouteServiceImpl service =
      new RouteServiceImpl(repository, mapper, mock(RouteReloadService.class));

  @Test
  void missingRouteIs404_notADatabaseError() {
    when(repository.findFirstById(9L)).thenReturn(Mono.empty());
    StepVerifier.create(service.getRoute(9L))
        .expectErrorSatisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(e)
                    .isInstanceOf(RouteNotFoundException.class)
                    .extracting(x -> ((AppException) x).getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND))
        .verify();
  }

  @Test
  void databaseFailureOnGetIs500() {
    when(repository.findFirstById(9L)).thenReturn(Mono.error(new RuntimeException("db down")));
    StepVerifier.create(service.getRoute(9L)).expectError(DataBaseException.class).verify();
  }

  @Test
  void invalidOrDuplicateRouteDataIs400() {
    when(mapper.toRoute(any())).thenReturn(new ApiRoute());
    when(repository.save(any()))
        .thenReturn(
            Mono.error(new DataIntegrityViolationException("null value in column \"uri\"")));
    StepVerifier.create(service.saveRoute(new RouteRequest()))
        .expectErrorSatisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(((AppException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST))
        .verify();
  }
}
