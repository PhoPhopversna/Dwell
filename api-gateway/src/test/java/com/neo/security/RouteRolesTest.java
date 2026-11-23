package com.neo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteRolesTest {

  @Test
  void parseTrimsAndDropsBlanks() {
    assertThat(RouteRoles.parse(" ADMIN, ,CUSTOMER ")).containsExactly("ADMIN", "CUSTOMER");
    assertThat(RouteRoles.parse(null)).isEmpty();
    assertThat(RouteRoles.parse("  ")).isEmpty();
  }

  @Test
  void joinDedupesAndReturnsNullWhenEmpty() {
    assertThat(RouteRoles.join(List.of("ADMIN", " ADMIN ", "CUSTOMER")))
        .isEqualTo("ADMIN,CUSTOMER");
    assertThat(RouteRoles.join(Arrays.asList(" ", null))).isNull();
    assertThat(RouteRoles.join(null)).isNull();
  }
}
