package com.neo.repository;

import com.neo.entity.ApiRoute;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface RouteRepository extends R2dbcRepository<ApiRoute, Long> {
  Mono<ApiRoute> findById(Long routeId);
  Mono<ApiRoute> findFirstById(Long routeId);

  Flux<ApiRoute> findAll();

  Mono<ApiRoute> save(ApiRoute apiRoute);

  Flux<ApiRoute> findAllByPathPatternIsNotNull();

  @Query(
      """
    SELECT * FROM routes
    WHERE method = :method
    AND :path LIKE REPLACE(path, '**', '%')
    """)
  Mono<ApiRoute> findFirstByPathAndMethod(String path, String method);

  @Query(
      """
    UPDATE routes SET
        status = :status,
        updated_at = :now,
        updated_by = :updatedBy
    WHERE id = :id
    """)
  Mono<Integer> updateStatus(Long id, String status, LocalDateTime now, String updatedBy);

  @Query(
      """
        UPDATE routes SET
            uri = :uri,
            path = :path,
            method = :method,
            description = :description,
            group_code = :groupCode,
            rate_limit = :rateLimit,
            rate_limit_duration = :rateLimitDuration,
            status = :status,
            path_pattern = :pathPattern,
            normalized_path = :normalizedPath,
            updated_at = :now,
            updated_by = :updatedBy
        WHERE id = :id
        RETURNING *
        """)
  Mono<ApiRoute> updateRoute(
      Long id,
      String uri,
      String path,
      String method,
      String description,
      String groupCode,
      Integer rateLimit,
      Integer rateLimitDuration,
      String status,
      String pathPattern,
      String normalizedPath,
      LocalDateTime now,
      String updatedBy);
}
