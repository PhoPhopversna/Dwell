package com.neo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neo.common.security.DwellJwtProperties;
import com.neo.common.security.DwellPermissions;
import com.neo.config.KeycloakProperties;
import com.neo.exception.AuthException;
import com.neo.service.impl.KeycloakAdminServiceImpl;
import com.neo.service.impl.KeycloakGenericConnector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.BodyInserter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A tiny in-memory Keycloak behind a mocked connector: the permission client's roles, the holder's
 * composites, realm roles' composites and the login client's scope. Writes go through postMono and
 * are recorded, so each test can check exactly what the sync changed.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class KeycloakPermissionSyncTest {

  private static final String REALM = "http://kc/admin/realms/Dwell";
  private static final String API_ROLES = REALM + "/clients/api/roles";
  private static final String HOLDER =
      API_ROLES + "/" + DwellPermissions.SCOPE_ROLE + "/composites";
  private static final String SCOPE = REALM + "/clients/login/scope-mappings/clients/api";

  private final KeycloakProperties props = new KeycloakProperties();
  private final KeycloakAdminServiceImpl admin = mock(KeycloakAdminServiceImpl.class);
  private final KeycloakGenericConnector keycloak = mock(KeycloakGenericConnector.class);

  /** Keycloak's state */
  private final Map<String, Map> apiRoles = new LinkedHashMap<>();

  private final Set<String> holder = new LinkedHashSet<>();
  private final Set<String> scope = new LinkedHashSet<>();
  private final Map<String, Set<String>> realmRoleComposites = new LinkedHashMap<>();

  /** What the sync wrote: "url names" */
  private final List<String> writes = new ArrayList<>();

  private PermissionSyncProperties sync =
      new PermissionSyncProperties(
          true,
          Map.of("ADMIN", List.of("*"), "CUSTOMER", List.of("category:read", "product:read")));

  private KeycloakPermissionSync check() {
    return new KeycloakPermissionSync(
        props, admin, keycloak, sync, new DwellJwtProperties(null, null, null));
  }

  @BeforeEach
  void setUp() {
    props.setUrl("http://kc");
    props.setRealm("Dwell");
    props.setClientId("dwell-client");
    when(admin.getAccessTokenCache()).thenReturn(Mono.just("t"));
    realmRoleComposites.put("ADMIN", new LinkedHashSet<>());
    realmRoleComposites.put("CUSTOMER", new LinkedHashSet<>());

    when(keycloak.getFlux(anyString(), anyString(), eq(Map.class), any()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.equals(REALM + "/clients?clientId=dwell-api")) {
                return Flux.just(Map.of("id", "api"));
              }
              if (url.equals(REALM + "/clients?clientId=dwell-client")) {
                return Flux.just(Map.of("id", "login"));
              }
              if (url.equals(API_ROLES)) {
                return Flux.fromIterable(new ArrayList<>(apiRoles.values()));
              }
              if (url.equals(HOLDER)) {
                return Flux.fromIterable(new ArrayList<>(holder)).map(n -> (Map) Map.of("name", n));
              }
              if (url.equals(SCOPE)) {
                return Flux.fromIterable(new ArrayList<>(scope)).map(n -> (Map) Map.of("name", n));
              }
              return Flux.error(new AssertionError("unexpected GET " + url));
            });
    when(keycloak.postMono(anyString(), any(), any(), anyString(), eq(Void.class), any()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              Object body = bodyOf(inv.getArgument(2));
              if (url.equals(API_ROLES)) {
                String name = String.valueOf(((Map) body).get("name"));
                apiRoles.put(name, Map.of("id", "id-" + name, "name", name));
                writes.add("create " + name);
              } else if (url.equals(HOLDER)) {
                List<String> names = names(body);
                holder.addAll(names);
                writes.add("holder " + names);
              } else if (url.startsWith(REALM + "/roles/")) {
                String role =
                    url.substring((REALM + "/roles/").length()).replace("/composites", "");
                if (!realmRoleComposites.containsKey(role)) {
                  return Mono.error(new AuthException("Could not find role", HttpStatus.NOT_FOUND));
                }
                realmRoleComposites.get(role).addAll(names(body));
                writes.add("grant " + role + " " + names(body));
              } else {
                return Mono.error(new AssertionError("unexpected POST " + url));
              }
              return Mono.empty();
            });
  }

  /** The test inserts are BodyInserters.fromValue(x); read x back through the test hook. */
  private static Object bodyOf(BodyInserter<?, ?> inserter) {
    try {
      var f = inserter.getClass().getDeclaredFields();
      for (var field : f) {
        field.setAccessible(true);
        Object v = field.get(inserter);
        if (v instanceof Map || v instanceof List) {
          return v;
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    throw new AssertionError("no body in " + inserter);
  }

  private static List<String> names(Object body) {
    return ((List<Map>) body).stream().map(m -> String.valueOf(m.get("name"))).toList();
  }

  private void keycloakHasEverything() {
    apiRoles.put(
        DwellPermissions.SCOPE_ROLE, Map.of("id", "h", "name", DwellPermissions.SCOPE_ROLE));
    DwellPermissions.ALL.forEach(p -> apiRoles.put(p, Map.of("id", "id-" + p, "name", p)));
    holder.addAll(DwellPermissions.ALL);
    scope.add(DwellPermissions.SCOPE_ROLE);
  }

  @Test
  void aNewKeycloakGetsEveryPermissionTheHolderAndTheInitialGrants() {
    scope.add(DwellPermissions.SCOPE_ROLE); // the one-time setup step

    KeycloakPermissionSync.Report r = check().sync().block();

    assertThat(r.created()).containsExactlyElementsOf(DwellPermissions.ALL);
    assertThat(apiRoles).containsKey(DwellPermissions.SCOPE_ROLE);
    assertThat(holder).containsExactlyInAnyOrderElementsOf(DwellPermissions.ALL);
    assertThat(realmRoleComposites.get("ADMIN"))
        .containsExactlyInAnyOrderElementsOf(DwellPermissions.ALL);
    assertThat(realmRoleComposites.get("CUSTOMER"))
        .containsExactlyInAnyOrder("category:read", "product:read");
    assertThat(r.problems()).isEmpty();
  }

  @Test
  void aKeycloakInSyncIsNotWrittenTo() {
    keycloakHasEverything();

    KeycloakPermissionSync.Report r = check().sync().block();

    assertThat(writes).isEmpty();
    assertThat(r.created()).isEmpty();
    assertThat(r.problems()).isEmpty();
  }

  @Test
  void aNewPermissionInTheCodeIsCreatedAndOnlyItIsGranted() {
    keycloakHasEverything();
    apiRoles.remove("route:manage");
    holder.remove("route:manage");
    // An admin had unticked user:manage from ADMIN earlier: it must stay unticked
    realmRoleComposites.get("ADMIN").addAll(DwellPermissions.ALL);
    realmRoleComposites.get("ADMIN").remove("user:manage");

    KeycloakPermissionSync.Report r = check().sync().block();

    assertThat(r.created()).containsExactly("route:manage");
    assertThat(holder).contains("route:manage");
    assertThat(writes)
        .containsExactly(
            "create route:manage", "holder [route:manage]", "grant ADMIN [route:manage]");
    assertThat(realmRoleComposites.get("ADMIN")).doesNotContain("user:manage");
    assertThat(realmRoleComposites.get("CUSTOMER")).isEmpty();
  }

  @Test
  void aPermissionCreatedByHandReachesTokensButIsGrantedToNobody() {
    keycloakHasEverything();
    apiRoles.put("report:export", Map.of("id", "id-report", "name", "report:export"));

    KeycloakPermissionSync.Report r = check().sync().block();

    assertThat(r.addedToScope()).containsExactly("report:export");
    assertThat(holder).contains("report:export");
    assertThat(writes).containsExactly("holder [report:export]");
    assertThat(r.problems()).isEmpty();
  }

  @Test
  void theHolderMissingFromTheLoginClientsScopeIsReported() {
    keycloakHasEverything();
    scope.clear();

    assertThat(check().sync().block().problems())
        .singleElement()
        .asString()
        .contains("permission-scope is not in the scope of dwell-client", "step 4.4");
  }

  @Test
  void anOlderSetupWithEveryPermissionDirectlyInScopeIsFine() {
    keycloakHasEverything();
    scope.clear();
    scope.addAll(DwellPermissions.ALL);

    assertThat(check().sync().block().problems()).isEmpty();
  }

  @Test
  void switchedOffItOnlyReports() {
    sync = new PermissionSyncProperties(false, Map.of("ADMIN", List.of("*")));
    keycloakHasEverything();
    apiRoles.remove("route:manage");

    KeycloakPermissionSync.Report r = check().sync().block();

    assertThat(writes).isEmpty();
    assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("route:manage"));
  }

  @Test
  void aMissingRoleForTheInitialGrantsIsReportedAndTheRestGoesOn() {
    realmRoleComposites.remove("CUSTOMER");
    scope.add(DwellPermissions.SCOPE_ROLE);

    KeycloakPermissionSync.Report r = check().sync().block();

    assertThat(realmRoleComposites.get("ADMIN"))
        .containsExactlyInAnyOrderElementsOf(DwellPermissions.ALL);
    assertThat(r.problems()).singleElement().asString().contains("CUSTOMER");
  }

  @Test
  void keycloakRefusingGivesAClearHint() {
    when(keycloak.getFlux(
            eq(REALM + "/clients?clientId=dwell-api"), anyString(), eq(Map.class), any()))
        .thenReturn(Flux.error(new AuthException("Forbidden", HttpStatus.FORBIDDEN)));

    assertThatThrownBy(() -> check().sync().block())
        .hasMessageContaining("needs 'manage' on client dwell-api", "step 5.6");
  }
}
