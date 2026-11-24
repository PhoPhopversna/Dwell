package com.neo.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neo.dto.TokenClaims;
import com.neo.entity.AppUser;
import com.neo.entity.UserLogAudit;
import com.neo.exception.AuthException;
import com.neo.exception.ConflictException;
import com.neo.repository.AppUserRepository;
import com.neo.repository.UserLogAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

class UserServiceImplTest {

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final UserLogAuditRepository audits = mock(UserLogAuditRepository.class);
  private final UserServiceImpl service = new UserServiceImpl(users, audits);
  private final TokenClaims claims =
      new TokenClaims("kc-1", "alice", "alice@example.com", "Alice", "Smith");

  @BeforeEach
  void auditSucceeds() {
    when(audits.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
  }

  private static AppUser user(long id, Boolean active) {
    return AppUser.builder().id(id).keycloakId("kc-1").username("alice").active(active).build();
  }

  @Test
  void loginUpdatesLastLoginAndAudits() {
    when(users.findByKeycloakId("kc-1")).thenReturn(Mono.just(user(7, true)));
    when(users.updateLastLogin(eq(7L), any())).thenReturn(Mono.just(1));

    AppUser synced = service.syncAuthenticatedUser(claims, "LOGIN", "c-1").block();

    assertThat(synced.getLastLoginAt()).isNotNull();
    ArgumentCaptor<UserLogAudit> audit = ArgumentCaptor.forClass(UserLogAudit.class);
    verify(audits).save(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("LOGIN");
    assertThat(audit.getValue().getUserId()).isEqualTo(7L);
  }

  @Test
  void missingRowIsCreatedFromTheToken() {
    when(users.findByKeycloakId("kc-1")).thenReturn(Mono.empty());
    when(users.save(any())).thenAnswer(i -> Mono.just(i.<AppUser>getArgument(0)));

    AppUser created = service.syncAuthenticatedUser(claims, "REFRESH", "c-1").block();

    assertThat(created.getKeycloakId()).isEqualTo("kc-1");
    assertThat(created.getEmail()).isEqualTo("alice@example.com");
    assertThat(created.getCreatedBy()).isEqualTo("KEYCLOAK_SYNC");
    assertThat(created.getActive()).isTrue();
    verify(users, never()).updateLastLogin(anyLong(), any()); // only LOGIN records it
  }

  @Test
  void concurrentInsertFallsBackToTheExistingRow() {
    when(users.findByKeycloakId("kc-1")).thenReturn(Mono.empty(), Mono.just(user(9, true)));
    when(users.save(any())).thenReturn(Mono.error(new DataIntegrityViolationException("dup")));

    assertThat(service.syncAuthenticatedUser(claims, "REFRESH", "c-1").block().getId())
        .isEqualTo(9L);
  }

  @Test
  void inactiveUserIs403AndAudited() {
    when(users.findByKeycloakId("kc-1")).thenReturn(Mono.just(user(7, false)));

    assertThatThrownBy(() -> service.syncAuthenticatedUser(claims, "LOGIN", "c-1").block())
        .isInstanceOf(AuthException.class)
        .satisfies(
            e -> assertThat(((AuthException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    verify(users, never()).updateLastLogin(anyLong(), any());
    verify(audits).save(any());
  }

  @Test
  void databaseDownIs503() {
    when(users.findByKeycloakId("kc-1"))
        .thenReturn(Mono.error(new DataAccessResourceFailureException("connection refused")));

    assertThatThrownBy(() -> service.syncAuthenticatedUser(claims, "LOGIN", "c-1").block())
        .isInstanceOf(AuthException.class)
        .satisfies(
            e ->
                assertThat(((AuthException) e).getStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void takenUsernameOrEmailIs409() {
    when(users.existsByUsernameIgnoreCase("alice")).thenReturn(Mono.just(false));
    when(users.existsByEmailIgnoreCase("alice@example.com")).thenReturn(Mono.just(true));

    assertThatThrownBy(() -> service.ensureAvailable("alice", "alice@example.com").block())
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void freeUsernameAndEmailPass() {
    when(users.existsByUsernameIgnoreCase("bob")).thenReturn(Mono.just(false));
    when(users.existsByEmailIgnoreCase("bob@example.com")).thenReturn(Mono.just(false));

    service.ensureAvailable("bob", "bob@example.com").block();
  }
}
