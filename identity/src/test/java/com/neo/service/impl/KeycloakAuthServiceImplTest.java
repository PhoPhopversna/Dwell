package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neo.config.KeycloakProperties;
import com.neo.config.RegistrationProperties;
import com.neo.dto.RegisterRequest;
import com.neo.dto.TokenResponse;
import com.neo.entity.AppUser;
import com.neo.exception.AuthException;
import com.neo.exception.ConflictException;
import com.neo.service.RoleService;
import com.neo.service.UserService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KeycloakAuthServiceImplTest {

  private final KeycloakProperties props = new KeycloakProperties();
  private final KeycloakGenericConnector connector = mock(KeycloakGenericConnector.class);
  private final KeycloakAdminServiceImpl admin = mock(KeycloakAdminServiceImpl.class);
  private final UserService userService = mock(UserService.class);
  private final RoleService roleService = mock(RoleService.class);
  private final RegistrationProperties registration = new RegistrationProperties();

  /** Keycloak's admin "create user" answer: 201 with the new user's id in Location. */
  private final WebClient keycloak =
      WebClient.builder()
          .exchangeFunction(
              request ->
                  Mono.just(
                      ClientResponse.create(HttpStatus.CREATED)
                          .header("Location", "http://kc/admin/realms/Dwell/users/kc-new")
                          .build()))
          .build();

  private final KeycloakAuthServiceImpl service =
      new KeycloakAuthServiceImpl(
          props, keycloak, connector, admin, userService, roleService, registration);

  @BeforeEach
  void setUp() {
    props.setUrl("http://kc");
    props.setRealm("Dwell");
    when(admin.getAccessTokenCache()).thenReturn(Mono.just("admin-token"));
    when(userService.saveUserAuditLog(any())).thenReturn(Mono.empty());
    when(roleService.assignRolesToUser(anyString(), any())).thenReturn(Mono.empty());
  }

  private static RegisterRequest registration() {
    RegisterRequest.Credential password = new RegisterRequest.Credential();
    password.setType("password");
    password.setValue("Secret@123");
    RegisterRequest r = new RegisterRequest();
    r.setUserName("alice");
    r.setEmail("alice@example.com");
    r.setCredentials(List.of(password));
    return r;
  }

  private static String jwt(String sub) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"sub\":\"" + sub + "\",\"preferred_username\":\"alice\"}";
    return b64.encodeToString("{}".getBytes())
        + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  private static TokenResponse tokens() {
    TokenResponse t = new TokenResponse();
    t.setAccessToken(jwt("kc-1"));
    t.setRefreshToken(jwt("kc-1"));
    return t;
  }

  @Test
  void localDuplicateIsRejectedBeforeKeycloakIsCalled() {
    when(userService.ensureAvailable("alice", "alice@example.com"))
        .thenReturn(Mono.error(new ConflictException("taken", HttpStatus.CONFLICT)));

    assertThatThrownBy(() -> service.register(registration()).block())
        .isInstanceOf(ConflictException.class);
    verify(admin, never()).getAccessTokenCache();
  }

  @Test
  void failedLocalSaveDeletesTheKeycloakUser() {
    when(userService.ensureAvailable(anyString(), anyString())).thenReturn(Mono.empty());
    RuntimeException dbDown = new RuntimeException("db down");
    when(userService.saveUser(any(), eq("REGISTER"), anyString())).thenReturn(Mono.error(dbDown));
    when(connector.delete(
            eq("http://kc/admin/realms/Dwell/users/kc-new"), eq("admin-token"), any()))
        .thenReturn(Mono.empty());

    assertThatThrownBy(() -> service.register(registration()).block()).isSameAs(dbDown);
    verify(connector).delete(eq("http://kc/admin/realms/Dwell/users/kc-new"), any(), any());
    verify(userService).saveUserAuditLog(argThat(a -> "REGISTER_ROLLBACK".equals(a.getAction())));
  }

  @Test
  void failedRollbackIsAuditedAndTheOriginalErrorIsKept() {
    when(userService.ensureAvailable(anyString(), anyString())).thenReturn(Mono.empty());
    RuntimeException dbDown = new RuntimeException("db down");
    when(userService.saveUser(any(), eq("REGISTER"), anyString())).thenReturn(Mono.error(dbDown));
    when(connector.delete(anyString(), anyString(), any()))
        .thenReturn(Mono.error(new AuthException("unreachable", HttpStatus.SERVICE_UNAVAILABLE)));

    assertThatThrownBy(() -> service.register(registration()).block()).isSameAs(dbDown);
    verify(userService)
        .saveUserAuditLog(argThat(a -> "REGISTER_ROLLBACK_FAILED".equals(a.getAction())));
  }

  @Test
  void loginReturnsTokensOnceTheLocalUserIsInSync() {
    TokenResponse t = tokens();
    when(connector.postMono(anyString(), any(), any(), any(), eq(TokenResponse.class), any()))
        .thenReturn(Mono.just(t));
    when(userService.syncAuthenticatedUser(
            argThat(c -> c.subject().equals("kc-1")), eq("LOGIN"), anyString()))
        .thenReturn(Mono.just(new AppUser()));

    assertThat(service.login("alice", "pw").block()).isSameAs(t);
  }

  @Test
  void refusedLoginRevokesTheKeycloakSession() {
    when(connector.postMono(anyString(), any(), any(), any(), eq(TokenResponse.class), any()))
        .thenReturn(Mono.just(tokens()));
    when(connector.postMono(
            eq("http://kc/realms/Dwell/protocol/openid-connect/logout"),
            any(),
            any(),
            any(),
            eq(Void.class),
            any()))
        .thenReturn(Mono.empty());
    when(userService.syncAuthenticatedUser(any(), eq("LOGIN"), anyString()))
        .thenReturn(Mono.error(new AuthException("Account is disabled", HttpStatus.FORBIDDEN)));

    assertThatThrownBy(() -> service.login("alice", "pw").block())
        .isInstanceOf(AuthException.class)
        .satisfies(
            e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    verify(connector)
        .postMono(
            eq("http://kc/realms/Dwell/protocol/openid-connect/logout"),
            any(),
            any(),
            any(),
            eq(Void.class),
            any());
  }

  @Test
  void wrongPasswordIsAuditedWithoutTouchingAppUser() {
    when(connector.postMono(anyString(), any(), any(), any(), eq(TokenResponse.class), any()))
        .thenReturn(Mono.error(new AuthException("Invalid", HttpStatus.UNAUTHORIZED)));

    assertThatThrownBy(() -> service.login("alice", "bad").block())
        .isInstanceOf(AuthException.class);
    verify(userService)
        .saveUserAuditLog(argThat(a -> "LOGIN".equals(a.getAction()) && !a.getSuccess()));
    verify(userService, never()).syncAuthenticatedUser(any(), any(), any());
  }

  @Test
  void selfRegisteredUserGetsCustomerBeforeTheLocalRowIsSaved() {
    when(userService.ensureAvailable(anyString(), anyString())).thenReturn(Mono.empty());
    when(userService.saveUser(any(), eq("REGISTER"), anyString())).thenReturn(Mono.empty());

    service.register(registration()).block();

    InOrder order = inOrder(roleService, userService);
    order.verify(roleService).assignRolesToUser("kc-new", List.of("CUSTOMER"));
    order.verify(userService).saveUser(any(), eq("REGISTER"), anyString());
    verify(connector, never()).delete(anyString(), anyString(), any());
  }

  @Test
  void failedRoleAssignmentDeletesTheKeycloakUserAndGives503() {
    when(userService.ensureAvailable(anyString(), anyString())).thenReturn(Mono.empty());
    when(roleService.assignRolesToUser(anyString(), any()))
        .thenReturn(
            Mono.error(new AuthException("Role not found: CUSTOMER", HttpStatus.NOT_FOUND)));
    when(connector.delete(
            eq("http://kc/admin/realms/Dwell/users/kc-new"), eq("admin-token"), any()))
        .thenReturn(Mono.empty());

    assertThatThrownBy(() -> service.register(registration()).block())
        .isInstanceOf(AuthException.class)
        .hasMessage("Registration is temporarily unavailable")
        .satisfies(
            e ->
                assertThat(((AuthException) e).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    // Nobody is left registered without a role, and no local row is written
    verify(connector).delete(eq("http://kc/admin/realms/Dwell/users/kc-new"), any(), any());
    verify(userService, never()).saveUser(any(), any(), any());
  }

  @Test
  void configuredRolesAreUsedAndAnEmptyListAssignsNone() {
    when(userService.ensureAvailable(anyString(), anyString())).thenReturn(Mono.empty());
    when(userService.saveUser(any(), eq("REGISTER"), anyString())).thenReturn(Mono.empty());

    registration.setDefaultRoles(List.of("CUSTOMER", "NEWSLETTER"));
    service.register(registration()).block();
    verify(roleService).assignRolesToUser("kc-new", List.of("CUSTOMER", "NEWSLETTER"));

    registration.setDefaultRoles(List.of());
    service.register(registration()).block();
    verify(roleService, org.mockito.Mockito.times(1)).assignRolesToUser(anyString(), any());
  }

  @Test
  void explicitRolesReplaceTheRegistrationDefaults() {
    when(userService.ensureAvailable(anyString(), anyString())).thenReturn(Mono.empty());
    when(userService.saveUser(any(), eq("REGISTER"), anyString())).thenReturn(Mono.empty());

    service.register(registration(), List.of("ADMIN")).block();

    verify(roleService).assignRolesToUser("kc-new", List.of("ADMIN"));
    verify(roleService, never()).assignRolesToUser(anyString(), eq(List.of("CUSTOMER")));
  }
}
