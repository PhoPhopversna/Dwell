package com.neo.service;

import com.neo.dto.AppUserRequest;
import com.neo.dto.TokenClaims;
import com.neo.dto.UserAuditRequest;
import com.neo.entity.AppUser;
import reactor.core.publisher.Mono;

public interface UserService {

  public Mono<Void> saveUser(AppUserRequest appUserRequest, String action, String correlationId);

  public Mono<Void> saveUserAuditLog(UserAuditRequest userAuditRequest);

  public Mono<Void> ensureAvailable(String username, String email);

  public Mono<AppUser> syncAuthenticatedUser(
      TokenClaims claims, String action, String correlationId);

  public Mono<Void> auditLogout(TokenClaims claims, String correlationId);
}
