package com.neo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neo.common.security.DwellJwt;
import com.neo.common.security.DwellJwtProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class WebSocketAuthInterceptorTest {

  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final WebSocketAuthInterceptor interceptor =
      new WebSocketAuthInterceptor(
          decoder, DwellJwt.authenticationConverter(new DwellJwtProperties(null, null, null)));
  private final MessageChannel channel = mock(MessageChannel.class);

  private static Jwt jwt(String... permissions) {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .claim("preferred_username", "alice")
        .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
        .claim("resource_access", Map.of("dwell-api", Map.of("roles", List.of(permissions))))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  private static Message<byte[]> frame(StompCommand command, String authorization) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    if (authorization != null) {
      accessor.setNativeHeader("Authorization", authorization);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void connectWithProductReadSetsTheUser() {
    when(decoder.decode("good")).thenReturn(jwt("product:read"));
    Message<?> out = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer good"), channel);
    assertThat(StompHeaderAccessor.wrap(out).getUser().getName()).isEqualTo("alice");
  }

  @Test
  void connectWithoutATokenIsRefused() {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), channel))
        .isInstanceOf(MessageDeliveryException.class)
        .hasMessageContaining("Authentication required");
  }

  @Test
  void connectWithAnInvalidTokenIsRefused() {
    when(decoder.decode("bad")).thenThrow(new BadJwtException("bad signature"));
    assertThatThrownBy(
            () -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer bad"), channel))
        .isInstanceOf(MessageDeliveryException.class)
        .hasMessageContaining("Authentication failed");
  }

  @Test
  void connectWithoutProductReadIsRefused() {
    when(decoder.decode("norole")).thenReturn(jwt("category:read"));
    assertThatThrownBy(
            () -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer norole"), channel))
        .isInstanceOf(MessageDeliveryException.class)
        .hasMessageContaining("Access denied");
  }

  @Test
  void subscribeWithoutAnAuthenticatedSessionIsRefused() {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null), channel))
        .isInstanceOf(MessageDeliveryException.class);
  }
}
