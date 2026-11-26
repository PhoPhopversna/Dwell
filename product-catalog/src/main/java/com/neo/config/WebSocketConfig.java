package com.neo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final JwtDecoder jwtDecoder;
  private final JwtAuthenticationConverter jwtAuthConverter;

  public WebSocketConfig(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthConverter) {
    this.jwtDecoder = jwtDecoder;
    this.jwtAuthConverter = jwtAuthConverter;
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    // JWT on STOMP CONNECT, same checks as HTTP requests
    registration.interceptors(new WebSocketAuthInterceptor(jwtDecoder, jwtAuthConverter));
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry config) {
    config.enableSimpleBroker("/topic", "/queue"); // prefixes for outbound messages
    config.setApplicationDestinationPrefixes("/app"); // prefix for inbound messages
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws") // the endpoint clients connect to
        .setAllowedOriginPatterns("*")
        .withSockJS(); // optional fallback support
  }
}
