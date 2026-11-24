package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neo.config.KeycloakProperties;
import com.neo.entity.AppUser;
import com.neo.exception.AuthException;
import com.neo.repository.AppUserRepository;
import com.neo.service.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

class UserAdminServiceImplTest {

  private static final String ID = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";
  private static final String URL = "http://kc/admin/realms/Dwell/users/" + ID;

  private final KeycloakProperties props = new KeycloakProperties();
  private final KeycloakGenericConnector keycloak = mock(KeycloakGenericConnector.class);
  private final KeycloakAdminServiceImpl admin = mock(KeycloakAdminServiceImpl.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final UserService userService = mock(UserService.class);
  private final UserAdminServiceImpl service =
      new UserAdminServiceImpl(props, keycloak, admin, users, userService);

  @BeforeEach
  void setUp() {
    props.setUrl("http://kc");
    props.setRealm("Dwell");
    when(admin.getAccessTokenCache()).thenReturn(Mono.just("admin-token"));
    when(keycloak.putMono(anyString(), any(), any(), anyString(), eq(Void.class), any()))
        .thenReturn(Mono.empty());
    when(keycloak.postMono(anyString(), any(), any(), anyString(), eq(Void.class), any()))
        .thenReturn(Mono.empty());
    when(users.updateActive(anyString(), any(Boolean.class))).thenReturn(Mono.just(1));
    when(users.findByKeycloakId(ID)).thenReturn(Mono.just(AppUser.builder().id(5L).build()));
    when(userService.saveUserAuditLog(any())).thenReturn(Mono.empty());
  }

  private static Mono<Void> asAdmin(Mono<Void> call, String adminSub) {
    Jwt jwt =
        Jwt.withTokenValue("t").header("alg", "none").subject(adminSub).claim("x", "y").build();
    return call.contextWrite(
        ReactiveSecurityContextHolder.withAuthentication(
            new JwtAuthenticationToken(jwt, List.of())));
  }

  @Test
  void deactivateDisablesInKeycloak_endsSessions_updatesAppUser_andAudits() {
    asAdmin(service.setActive(ID, false), "some-other-admin").block();

    verify(keycloak).putMono(eq(URL), any(), any(), eq("admin-token"), eq(Void.class), any());
    verify(keycloak).postMono(eq(URL + "/logout"), any(), any(), any(), eq(Void.class), any());
    verify(users).updateActive(ID, false);
    verify(userService)
        .saveUserAuditLog(argThat(a -> "DEACTIVATE".equals(a.getAction()) && a.getUserId() == 5L));
  }

  @Test
  void activateEnablesWithoutTouchingSessions() {
    service.setActive(ID, true).block();

    verify(keycloak).putMono(eq(URL), any(), any(), any(), eq(Void.class), any());
    verify(keycloak, never()).postMono(anyString(), any(), any(), any(), any(), any());
    verify(users).updateActive(ID, true);
  }

  @Test
  void adminCannotDeactivateThemselves() {
    assertThatThrownBy(() -> asAdmin(service.setActive(ID, false), ID).block())
        .isInstanceOf(AuthException.class)
        .satisfies(e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    verifyNoInteractions(keycloak);
  }

  @Test
  void nonUuidIdIsRejectedBeforeAnyCall() {
    assertThatThrownBy(() -> service.setActive("../roles", false).block())
        .isInstanceOf(AuthException.class)
        .satisfies(
            e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(keycloak, users);
  }

  @Test
  void unknownKeycloakUserIs404AndAppUserIsUntouched() {
    when(keycloak.putMono(anyString(), any(), any(), anyString(), eq(Void.class), any()))
        .thenReturn(Mono.error(new AuthException("Resource not found", HttpStatus.NOT_FOUND)));

    assertThatThrownBy(() -> service.setActive(ID, false).block())
        .satisfies(
            e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(users, never()).updateActive(anyString(), any(Boolean.class));
  }

  @Test
  void expiredAdminTokenIsRefreshedOnce() {
    when(keycloak.putMono(anyString(), any(), any(), eq("admin-token"), eq(Void.class), any()))
        .thenReturn(Mono.error(new AuthException("expired", HttpStatus.UNAUTHORIZED)));
    when(admin.forceRefresh()).thenReturn(Mono.just("fresh-token"));
    when(keycloak.putMono(anyString(), any(), any(), eq("fresh-token"), eq(Void.class), any()))
        .thenReturn(Mono.empty());

    service.setActive(ID, true).block();

    verify(admin, times(1)).forceRefresh();
    verify(users).updateActive(ID, true);
  }
}
