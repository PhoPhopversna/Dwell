package com.neo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePermissionsTest {

  @Test
  void parseTrimsAndDropsBlanks() {
    assertThat(RoutePermissions.parse(" product:read, ,product:write "))
        .containsExactly("product:read", "product:write");
    assertThat(RoutePermissions.parse(null)).isEmpty();
    assertThat(RoutePermissions.parse("  ")).isEmpty();
  }

  @Test
  void joinDedupesAndReturnsNullWhenEmpty() {
    assertThat(RoutePermissions.join(List.of("product:read", " product:read ", "product:write")))
        .isEqualTo("product:read,product:write");
    assertThat(RoutePermissions.join(Arrays.asList(" ", null))).isNull();
    assertThat(RoutePermissions.join(null)).isNull();
  }
}
