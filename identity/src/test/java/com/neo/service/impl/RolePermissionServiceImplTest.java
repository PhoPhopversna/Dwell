package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neo.common.security.DwellJwtProperties;
import com.neo.config.KeycloakProperties;
import com.neo.dto.PermissionMatrixResponse;
import com.neo.dto.RolePermissionsResponse;
import com.neo.exception.AuthException;
import com.neo.exception.ConflictException;
import com.neo.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SuppressWarnings({"rawtypes", "unchecked"})
class RolePermissionServiceImplTest {

  private static final String REALM = "http://kc/admin/realms/Dwell";

  private final KeycloakProperties props = new KeycloakProperties();
  private final KeycloakAdminServiceImpl admin = mock(KeycloakAdminServiceImpl.class);
  private final KeycloakGenericConnector keycloak = mock(KeycloakGenericConnector.class);
  private final UserService userService = mock(UserService.class);
  private final RolePermissionServiceImpl service =
      new RolePermissionServiceImpl(
          props, admin, keycloak, userService, new DwellJwtProperties(null, null, null));

  @BeforeEach
  void setUp() {
    props.setUrl("http://kc");
    props.setRealm("Dwell");
    when(admin.getAccessTokenCache()).thenReturn(Mono.just("t"));
    when(userService.saveUserAuditLog(any())).thenReturn(Mono.empty());
    // The permission client dwell-api and the permissions it defines
    when(keycloak.getFlux(eq(REALM + "/clients?clientId=dwell-api"), eq("t"), eq(Map.class), any()))
        .thenReturn(Flux.just(Map.of("id", "api")));
    when(keycloak.getFlux(eq(REALM + "/clients/api/roles"), eq("t"), eq(Map.class), any()))
        .thenReturn(
            Flux.fromIterable(
                    List.of(
                        "category:read",
                        "product:read",
                        "product:write",
                        "role:write",
                        "permission-scope"))
                .map(p -> (Map) Map.of("id", "id-" + p, "name", p, "description", "d " + p)));
    when(keycloak.postMono(anyString(), any(), any(), any(), any(), any()))
        .thenReturn(Mono.empty());
    when(keycloak.deleteWithBody(anyString(), any(), anyString(), any())).thenReturn(Mono.empty());
  }

  private void role(String name, String... permissions) {
    when(keycloak.getMono(eq(REALM + "/roles/" + name), eq("t"), eq(Map.class), any()))
        .thenReturn(Mono.just(Map.of("name", name, "description", "the " + name + " team")));
    when(keycloak.getFlux(
            eq(REALM + "/roles/" + name + "/composites/clients/api"),
            eq("t"),
            eq(Map.class),
            any()))
        .thenReturn(Flux.fromArray(permissions).map(p -> (Map) Map.of("name", p)));
  }

  private static JwtAuthenticationToken caller(String... realmRoles) {
    Jwt jwt =
        Jwt.withTokenValue("x")
            .header("alg", "none")
            .claim("preferred_username", "alice")
            .claim("realm_access", Map.of("roles", List.of(realmRoles)))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    return new JwtAuthenticationToken(jwt, List.of(), "alice");
  }

  @Test
  void tickingAddsWhatsMissingAndRemovesTheRest() {
    role("backend", "category:read", "product:read");

    RolePermissionsResponse r =
        service
            .setRolePermissions("backend", List.of("product:read", "product:write"))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller("ADMIN")))
            .block();

    assertThat(r.permissions()).containsExactly("product:read", "product:write");
    verify(keycloak)
        .postMono(
            eq(REALM + "/roles/backend/composites"), any(), any(), eq("t"), eq(Void.class), any());
    verify(keycloak)
        .deleteWithBody(
            eq(REALM + "/roles/backend/composites"),
            argThat(
                body ->
                    body.equals(
                        List.of(Map.of("id", "id-category:read", "name", "category:read")))),
            eq("t"),
            any());
    verify(userService)
        .saveUserAuditLog(
            argThat(
                a ->
                    "ROLE_PERMISSIONS".equals(a.getAction())
                        && a.getResponse().contains("by alice")
                        && a.getResponse().contains("added [product:write]")
                        && a.getResponse().contains("removed [category:read]")));
  }

  @Test
  void theSameTicksChangeNothing() {
    role("backend", "product:read");

    service.setRolePermissions("backend", List.of("product:read")).block();

    verify(keycloak, never()).postMono(anyString(), any(), any(), any(), any(), any());
    verify(keycloak, never()).deleteWithBody(anyString(), any(), anyString(), any());
  }

  @Test
  void anUnknownPermissionIsRejectedAndNothingChanges() {
    role("backend", "product:read");

    assertThatThrownBy(() -> service.setRolePermissions("backend", List.of("product:fly")).block())
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("product:fly")
        .satisfies(
            e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(keycloak, never()).postMono(anyString(), any(), any(), any(), any(), any());
    verify(keycloak, never()).deleteWithBody(anyString(), any(), anyString(), any());
  }

  @Test
  void anUnknownRoleIs404() {
    when(keycloak.getMono(eq(REALM + "/roles/ghost"), eq("t"), eq(Map.class), any()))
        .thenReturn(Mono.error(new AuthException("HTTP 404 Not Found", HttpStatus.NOT_FOUND)));

    assertThatThrownBy(() -> service.getRolePermissions("ghost").block())
        .isInstanceOf(AuthException.class)
        .hasMessage("Role not found: ghost");
  }

  @Test
  void youCannotRemoveRoleWriteFromARoleYouHave() {
    role("backend", "role:write", "product:read");

    assertThatThrownBy(
            () ->
                service
                    .setRolePermissions("backend", List.of("product:read"))
                    .contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(caller("backend")))
                    .block())
        .isInstanceOf(ConflictException.class);
    verify(keycloak, never()).deleteWithBody(anyString(), any(), anyString(), any());

    // Someone without that role may do it
    service
        .setRolePermissions("backend", List.of("product:read"))
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller("ADMIN")))
        .block();
    verify(keycloak).deleteWithBody(anyString(), any(), anyString(), any());
  }

  @Test
  void theMatrixShowsEveryBusinessRoleAndEveryPermission() {
    when(keycloak.getFlux(eq(REALM + "/roles"), eq("t"), eq(Map.class), any()))
        .thenReturn(
            Flux.just(
                Map.of("name", "ADMIN"),
                Map.of("name", "backend"),
                Map.of("name", "offline_access"),
                Map.of("name", "uma_authorization"),
                Map.of("name", "default-roles-dwell")));
    role("ADMIN", "category:read", "product:read", "product:write", "role:write");
    role("backend", "product:write");

    PermissionMatrixResponse m = service.getMatrix().block();

    assertThat(m.permissions())
        .extracting(p -> p.name())
        .containsExactly("category:read", "product:read", "product:write", "role:write");
    assertThat(m.roles())
        .extracting(RolePermissionsResponse::role)
        .containsExactly("ADMIN", "backend");
    assertThat(m.roles().get(1).permissions()).containsExactly("product:write");
  }

  @Test
  void theScopeHolderIsNeverListedOrTickable() {
    assertThat(service.getPermissions().collectList().block())
        .extracting(p -> p.name())
        .doesNotContain("permission-scope");
    role("backend");
    assertThatThrownBy(
            () -> service.setRolePermissions("backend", List.of("permission-scope")).block())
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("Unknown permissions");
  }
}
