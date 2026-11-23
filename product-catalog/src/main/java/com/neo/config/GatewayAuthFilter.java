package com.neo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dto.CustomApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that do not carry the gateway's shared secret, so clients must go through the
 * API gateway instead of calling this service directly.
 */
@Slf4j
public class GatewayAuthFilter extends OncePerRequestFilter {

  private final GatewayAuthProperties properties;
  private final ObjectMapper objectMapper;
  private final byte[] expectedSecret;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public GatewayAuthFilter(GatewayAuthProperties properties, ObjectMapper objectMapper) {
    if (properties.secret() == null || properties.secret().isBlank()) {
      throw new IllegalStateException(
          "app.gateway-auth.secret must be set when app.gateway-auth.enabled=true");
    }
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.expectedSecret = properties.secret().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return properties.excludedPaths().stream().anyMatch(p -> pathMatcher.match(p, path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String provided = request.getHeader(properties.headerName());
    if (provided != null
        && MessageDigest.isEqual(expectedSecret, provided.getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request, response);
      return;
    }

    log.warn(
        "Rejected direct access to {} {} from {}",
        request.getMethod(),
        request.getRequestURI(),
        request.getRemoteAddr());
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(
        response.getOutputStream(),
        CustomApiResponse.error("Direct access is not allowed; use the API gateway"));
  }
}
