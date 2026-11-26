package com.neo.common.security.servlet;

import com.neo.logging.CorrelationId;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** JSON 401/403 bodies (same shape as every other error) for servlet services. */
public final class ServletSecurityErrors {

  private static final Logger log = LoggerFactory.getLogger(ServletSecurityErrors.class);

  private ServletSecurityErrors() {}

  public static AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, ex) -> {
      boolean tokenSent = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
      if (tokenSent) {
        log.warn("Invalid JWT rejected: {}", ex.getMessage());
      }
      write(
          response,
          HttpServletResponse.SC_UNAUTHORIZED,
          tokenSent ? "Authentication failed" : "Authentication required",
          tokenSent ? "Bearer error=\"invalid_token\"" : "Bearer");
    };
  }

  public static AccessDeniedHandler accessDeniedHandler() {
    return (request, response, ex) -> {
      log.warn(
          "Denied {} {} for user {}",
          request.getMethod(),
          request.getRequestURI(),
          request.getUserPrincipal() == null ? "?" : request.getUserPrincipal().getName());
      write(
          response,
          HttpServletResponse.SC_FORBIDDEN,
          "Access denied",
          "Bearer error=\"insufficient_scope\"");
    };
  }

  static void write(HttpServletResponse response, int status, String message, String wwwAuth)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    if (wwwAuth != null) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuth);
    }
    response.getWriter().write(CorrelationId.errorJson(message, CorrelationId.current()));
  }
}
