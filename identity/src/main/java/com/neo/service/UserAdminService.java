package com.neo.service;

import reactor.core.publisher.Mono;

public interface UserAdminService {

  /** Enables or disables the user in Keycloak and app_user; disabling also ends all sessions. */
  Mono<Void> setActive(String keycloakId, boolean active);
}
