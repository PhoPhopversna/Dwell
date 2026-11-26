package com.neo.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.neo.common.security.DwellPermissions;
import com.neo.common.security.KeycloakRoleConverter;
import com.neo.common.security.autoconfigure.DwellSecurityAutoConfiguration;
import com.neo.controller.RoleController;
import com.neo.controller.UserController;
import com.neo.service.RoleService;
import com.neo.service.UserAdminService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** identity's permission rules with the real filter chain (gateway secret off for this test). */
@WebFluxTest(controllers = {RoleController.class, UserController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(DwellSecurityAutoConfiguration.class)
@TestPropertySource(properties = "app.gateway-auth.enabled=false")
class SecurityConfigTest {

  private static final String USER = "/api/roles/users/0b4f7c1e-0000-0000-0000-000000000001";
  private static final String BODY = "{\"roleNames\":[\"CUSTOMER\"]}";

  @Autowired private WebTestClient client;
  @MockitoBean private RoleService roleService;
  @MockitoBean private UserAdminService userAdminService;

  @BeforeEach
  void stubServices() {
    when(roleService.getAllRoles()).thenReturn(Flux.empty());
    when(roleService.createRole(any())).thenReturn(Mono.empty());
    when(roleService.deleteRole(anyString())).thenReturn(Mono.empty());
    when(roleService.assignRolesToUser(anyString(), anyList())).thenReturn(Mono.empty());
    when(roleService.removeRolesFromUser(anyString(), anyList())).thenReturn(Mono.empty());
    when(roleService.getUserRoles(anyString())).thenReturn(Mono.empty());
    when(userAdminService.setActive(anyString(), anyBoolean())).thenReturn(Mono.empty());
  }

  /** Realm role ADMIN (never enough on its own) plus these dwell-api permissions. */
  private static JwtMutator user(String... permissions) {
    return mockJwt()
        .jwt(
            j ->
                j.claim("realm_access", Map.of("roles", List.of("ADMIN")))
                    .claim(
                        "resource_access",
                        Map.of(DwellPermissions.CLIENT, Map.of("roles", List.of(permissions)))))
        .authorities(new KeycloakRoleConverter(DwellPermissions.CLIENT));
  }

  private WebTestClient.ResponseSpec call(
      JwtMutator user, HttpMethod method, String path, String body) {
    WebTestClient c = user == null ? client : client.mutateWith(user);
    WebTestClient.RequestBodySpec request = c.method(method).uri(path);
    return body == null
        ? request.exchange()
        : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
  }

  @Test
  void noTokenIs401() {
    call(null, HttpMethod.GET, "/api/roles", null).expectStatus().isUnauthorized();
  }

  @Test
  void realmRoleAloneIsNotEnough() {
    call(user(), HttpMethod.GET, "/api/roles", null).expectStatus().isForbidden();
  }

  @Test
  void roleReadListsRolesAndAUsersRolesButChangesNothing() {
    call(user("role:read"), HttpMethod.GET, "/api/roles", null).expectStatus().isOk();
    call(user("role:read"), HttpMethod.GET, USER, null).expectStatus().isOk();
    call(user("role:read"), HttpMethod.POST, "/api/roles", "{\"name\":\"SELLER\"}")
        .expectStatus()
        .isForbidden();
    call(user("role:read"), HttpMethod.POST, USER + "/assign", BODY).expectStatus().isForbidden();
  }

  @Test
  void roleWriteCreatesAndDeletesRolesButCannotAssignThem() {
    call(user("role:write"), HttpMethod.POST, "/api/roles", "{\"name\":\"SELLER\"}")
        .expectStatus()
        .isCreated();
    call(user("role:write"), HttpMethod.DELETE, "/api/roles/SELLER", null)
        .expectStatus()
        .isNoContent();
    call(user("role:write"), HttpMethod.POST, USER + "/assign", BODY).expectStatus().isForbidden();
  }

  @Test
  void userRoleAssignAssignsAndRemovesButCannotChangeTheRealmsRoles() {
    call(user("user-role:assign"), HttpMethod.POST, USER + "/assign", BODY).expectStatus().isOk();
    call(user("user-role:assign"), HttpMethod.DELETE, USER + "/remove", BODY)
        .expectStatus()
        .isNoContent();
    // DELETE /api/roles/users/... must not count as deleting a role called "users"
    call(user("role:write"), HttpMethod.DELETE, USER + "/remove", BODY)
        .expectStatus()
        .isForbidden();
    call(user("user-role:assign"), HttpMethod.DELETE, "/api/roles/SELLER", null)
        .expectStatus()
        .isForbidden();
  }

  @Test
  void userManageActivatesAndDeactivates() {
    String path = "/api/users/0b4f7c1e-0000-0000-0000-000000000001/deactivate";
    call(user("user:manage"), HttpMethod.POST, path, null).expectStatus().isOk();
    call(user("role:write", "user-role:assign"), HttpMethod.POST, path, null)
        .expectStatus()
        .isForbidden();
  }
}
