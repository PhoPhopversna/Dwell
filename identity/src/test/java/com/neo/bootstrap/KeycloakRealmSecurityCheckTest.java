package com.neo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neo.config.KeycloakProperties;
import com.neo.service.impl.KeycloakAdminServiceImpl;
import com.neo.service.impl.KeycloakGenericConnector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class KeycloakRealmSecurityCheckTest {

  private static final String REALM = "http://kc/admin/realms/Dwell";
  private static final String COMPOSITES =
      REALM + "/roles/default-roles-dwell/composites/clients/rm-id";
  private static final String ADMIN_CLI = REALM + "/clients?clientId=admin-cli";
  private static final String LOGIN_CLIENT = REALM + "/clients?clientId=dwell-client";

  private final KeycloakProperties props = new KeycloakProperties();
  private final KeycloakAdminServiceImpl admin = mock(KeycloakAdminServiceImpl.class);
  private final KeycloakGenericConnector keycloak = mock(KeycloakGenericConnector.class);
  private final KeycloakRealmSecurityCheck check =
      new KeycloakRealmSecurityCheck(props, admin, keycloak);

  @BeforeEach
  void setUp() {
    props.setUrl("http://kc");
    props.setRealm("Dwell");
    props.setClientId("dwell-client");
    when(admin.getAccessTokenCache()).thenReturn(Mono.just("t"));
    when(keycloak.getMono(eq(REALM), eq("t"), eq(Map.class), any()))
        .thenReturn(Mono.just(Map.of("defaultRole", Map.of("name", "default-roles-dwell"))));
    when(keycloak.getMono(
            eq(REALM + "/clients?clientId=realm-management"), eq("t"), eq(Map.class), any()))
        .thenReturn(Mono.just(Map.of("id", "rm-id")));
    loginClientFullScope(false);
  }

  private void loginClientFullScope(boolean fullScope) {
    when(keycloak.getFlux(eq(LOGIN_CLIENT), any(), eq(Map.class), any()))
        .thenReturn(Flux.just(Map.of("fullScopeAllowed", fullScope)));
  }

  private void defaultRoleHasAdminRoles(String... names) {
    when(keycloak.getFlux(eq(COMPOSITES), eq("t"), eq(Map.class), any()))
        .thenReturn(Flux.fromArray(names).map(n -> Map.of("name", n)));
  }

  private void adminCli(boolean directAccessGrants) {
    when(keycloak.getFlux(eq(ADMIN_CLI), eq("t"), eq(Map.class), any()))
        .thenReturn(
            Flux.just(
                Map.of(
                    "enabled", true,
                    "publicClient", true,
                    "directAccessGrantsEnabled", directAccessGrants)));
  }

  @Test
  void safeRealmHasNoProblems() {
    defaultRoleHasAdminRoles();
    adminCli(false);

    assertThat(check.findProblems().block()).isEmpty();
  }

  @Test
  void adminRoleInTheDefaultRolesIsReported() {
    defaultRoleHasAdminRoles("manage-users");
    adminCli(false);

    assertThat(check.findProblems().block())
        .singleElement()
        .asString()
        .contains("every user", "manage-users");
  }

  @Test
  void passwordLoginOnAdminCliIsReported() {
    defaultRoleHasAdminRoles();
    adminCli(true);

    assertThat(check.findProblems().block()).singleElement().asString().contains("admin-cli");
  }

  @Test
  void bothProblemsAreReportedTogether() {
    defaultRoleHasAdminRoles("manage-users", "view-users");
    adminCli(true);

    List<String> problems = check.findProblems().block();
    assertThat(problems).hasSize(2);
    assertThat(problems.get(0)).contains("manage-users", "view-users");
  }

  @Test
  void keycloakDownNeverStopsStartup() {
    when(admin.getAccessTokenCache())
        .thenReturn(Mono.error(new IllegalStateException("Connection refused")));

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
  }

  @Test
  void revokedAdminTokenIsRefreshedOnce() {
    when(admin.getAccessTokenCache()).thenReturn(Mono.just("revoked"));
    when(keycloak.getMono(eq(REALM), eq("revoked"), eq(Map.class), any()))
        .thenReturn(
            Mono.error(
                new com.neo.exception.AuthException(
                    "Invalid or expired token", org.springframework.http.HttpStatus.UNAUTHORIZED)));
    when(keycloak.getMono(
            eq(REALM + "/clients?clientId=realm-management"), eq("revoked"), eq(Map.class), any()))
        .thenReturn(Mono.just(Map.of("id", "rm-id")));
    when(keycloak.getFlux(eq(ADMIN_CLI), eq("revoked"), eq(Map.class), any()))
        .thenReturn(Flux.empty());
    when(admin.forceRefresh()).thenReturn(Mono.just("t"));
    defaultRoleHasAdminRoles("manage-users");
    adminCli(false);

    assertThat(check.findProblems().block()).singleElement().asString().contains("manage-users");
  }

  @Test
  void fullScopeOnTheLoginClientIsReported() {
    defaultRoleHasAdminRoles();
    adminCli(false);
    loginClientFullScope(true);

    assertThat(check.findProblems().block())
        .singleElement()
        .asString()
        .contains("dwell-client", "full scope");
  }

  @Test
  void serviceAccountOnTheLoginClientIsReported() {
    defaultRoleHasAdminRoles();
    adminCli(false);
    props.setServiceClientId("dwell-admin");
    when(keycloak.getFlux(eq(LOGIN_CLIENT), any(), eq(Map.class), any()))
        .thenReturn(Flux.just(Map.of("fullScopeAllowed", false, "serviceAccountsEnabled", true)));

    assertThat(check.findProblems().block())
        .singleElement()
        .asString()
        .contains("dwell-client", "service account", "dwell-admin");
  }
}
