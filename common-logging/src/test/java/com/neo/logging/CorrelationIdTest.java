package com.neo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrelationIdTest {

  @Test
  void keepsValidIncomingId() {
    assertThat(CorrelationId.resolve("abc-123_x.y:z")).isEqualTo("abc-123_x.y:z");
  }

  @Test
  void generatesWhenMissing() {
    String id = CorrelationId.resolve(null);
    assertThat(id).matches("[0-9a-f-]{36}");
    assertThat(CorrelationId.resolve(null)).isNotEqualTo(id);
  }

  @Test
  void replacesInvalidIdsIncludingLogInjectionAttempts() {
    assertThat(CorrelationId.resolve("")).isNotEmpty().isNotEqualTo("");
    assertThat(CorrelationId.resolve("a\nFAKE LOG LINE")).doesNotContain("\n");
    assertThat(CorrelationId.resolve("\"},{\"level\":\"ERROR")).doesNotContain("\"");
    assertThat(CorrelationId.resolve("x".repeat(65))).hasSize(36);
  }
}
