package com.neo.service.impl;

import com.neo.dto.AppUserRequest;
import com.neo.dto.UserAuditRequest;
import com.neo.entity.AppUser;
import com.neo.entity.UserLogAudit;
import com.neo.repository.AppUserRepository;
import com.neo.repository.UserLogAuditRepository;
import com.neo.service.UserService;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

  private final AppUserRepository appUserRepository;
  private final UserLogAuditRepository userLogAuditRepository;

  public UserServiceImpl(
      AppUserRepository appUserRepository, UserLogAuditRepository userLogAuditRepository) {
    this.appUserRepository = appUserRepository;
    this.userLogAuditRepository = userLogAuditRepository;
  }

  @Override
  public Mono<Void> saveUser(AppUserRequest appUserRequest, String action, String correlationId) {
    return appUserRepository
        .save(mapToAppUserEntity(appUserRequest))
        .onErrorResume(
            ex ->
                saveUserAuditLog(
                        new UserAuditRequest(null, action, false, ex.getMessage(), correlationId))
                    .then(Mono.error(ex)))
        .flatMap(
            saved ->
                saveUserAuditLog(
                    new UserAuditRequest(
                        saved.getId(), action, true, "Save User Successfully", correlationId)))
        .then();
  }

  @Override
  public Mono<Void> saveUserAuditLog(UserAuditRequest userAuditRequest) {
    return userLogAuditRepository
        .save(mapToUserLogAuditEntity(userAuditRequest))
        .doOnError(
            ex ->
                log.error(
                    "Failed to save audit log, correlationId={}",
                    userAuditRequest.getCorrelationId(),
                    ex))
        .onErrorComplete()
        .then();
  }

  private AppUser mapToAppUserEntity(AppUserRequest request) {
    return AppUser.builder()
        .username(request.getUsername())
        .email(request.getEmail())
        .active(request.getActive())
        .keycloakId(request.getKeycloakId())
        .firstName(request.getFirstName())
        .lastName(request.getLastName())
        .createdAt(LocalDateTime.now())
        .createdBy(request.getCreatedBy())
        .build();
  }

  private UserLogAudit mapToUserLogAuditEntity(UserAuditRequest request) {
    return UserLogAudit.builder()
        .userId(request.getUserId())
        .action(request.getAction())
        .success(request.getSuccess())
        .response(request.getResponse())
        .correlationId(request.getCorrelationId())
        .createdAt(LocalDateTime.now())
        .build();
  }
}
