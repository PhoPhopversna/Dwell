package com.neo.config;

import com.neo.common.security.DwellPermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * WebSocket (STOMP) auth: the client sends {@code Authorization: Bearer <token>} as a STOMP header
 * on CONNECT (SockJS can't set HTTP headers). The token goes through the same checks as HTTP
 * requests, and the user needs {@code product:read} (the messages are product image updates). Every
 * later frame needs that authenticated session; a frame without it is refused.
 */
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

  private static final String BEARER = "Bearer ";

  private final JwtDecoder jwtDecoder;
  private final JwtAuthenticationConverter converter;

  public WebSocketAuthInterceptor(JwtDecoder jwtDecoder, JwtAuthenticationConverter converter) {
    this.jwtDecoder = jwtDecoder;
    this.converter = converter;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message; // heartbeats
    }
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
    } else if (accessor.getUser() == null
        && !StompCommand.DISCONNECT.equals(accessor.getCommand())) {
      throw new MessageDeliveryException("Authentication required");
    }
    return message;
  }

  private AbstractAuthenticationToken authenticate(String header) {
    if (header == null || !header.startsWith(BEARER)) {
      log.warn("WebSocket CONNECT without a bearer token rejected");
      throw new MessageDeliveryException("Authentication required");
    }
    try {
      AbstractAuthenticationToken auth =
          converter.convert(jwtDecoder.decode(header.substring(BEARER.length())));
      boolean allowed =
          auth.getAuthorities().stream()
              .anyMatch(a -> DwellPermissions.PRODUCT_READ.equals(a.getAuthority()));
      if (!allowed) {
        log.warn(
            "WebSocket CONNECT denied for user {}: needs {}",
            auth.getName(),
            DwellPermissions.PRODUCT_READ);
        throw new MessageDeliveryException("Access denied");
      }
      return auth;
    } catch (JwtException e) {
      log.warn("WebSocket CONNECT with invalid JWT rejected: {}", e.getMessage());
      throw new MessageDeliveryException("Authentication failed");
    }
  }
}
