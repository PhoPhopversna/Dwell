package com.neo.repository;

import com.neo.entity.AppUser;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface AppUserRepository extends R2dbcRepository<AppUser, Long> {

  Mono<AppUser> findByKeycloakId(String keycloakId);

  Mono<Boolean> existsByUsernameIgnoreCase(String username);

  Mono<Boolean> existsByEmailIgnoreCase(String email);

  @Modifying
  @Query("UPDATE app_user SET last_login_at = :at WHERE id = :id")
  Mono<Integer> updateLastLogin(Long id, LocalDateTime at);

  @Modifying
  @Query("UPDATE app_user SET active = :active WHERE keycloak_id = :keycloakId")
  Mono<Integer> updateActive(String keycloakId, boolean active);
}
