package com.neo.service;

import com.neo.dto.CreateRoleRequest;
import com.neo.dto.RoleRepresentation;
import com.neo.dto.UserRolesResponse;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoleService {

  public Flux<RoleRepresentation> getAllRoles();

  public Mono<Void> createRole(CreateRoleRequest request);

  public Mono<Void> deleteRole(String roleName);

  public Mono<Void> assignRolesToUser(String userId, List<String> roleNames);

  public Mono<Void> removeRolesFromUser(String userId, List<String> roleNames);

  public Mono<UserRolesResponse> getUserRoles(String userId);

  public Flux<RoleRepresentation> resolveRoles(String adminToken, List<String> roleNames);
}
