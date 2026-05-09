package com.neo.service;

import com.neo.dto.AppUserRequest;
import com.neo.dto.UserAuditRequest;
import reactor.core.publisher.Mono;

public interface UserService {

  public Mono<Void> saveUser(AppUserRequest appUserRequest, String action, String correlationId);

  public Mono<Void> saveUserAuditLog(UserAuditRequest userAuditRequest);
}
