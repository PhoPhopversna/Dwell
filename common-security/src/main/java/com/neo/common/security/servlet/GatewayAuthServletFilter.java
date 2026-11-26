package com.neo.common.security.servlet;

import com.neo.common.security.GatewayAuthProperties;
import com.neo.common.security.GatewaySecretCheck;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects (403) requests without the gateway secret. Registered before Spring Security. */
public class GatewayAuthServletFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(GatewayAuthServletFilter.class);

  private final GatewaySecretCheck check;

  public GatewayAuthServletFilter(GatewayAuthProperties properties) {
    this.check = new GatewaySecretCheck(properties);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return check.isExcluded(request.getRequestURI().substring(request.getContextPath().length()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (check.matches(request.getHeader(check.headerName()))) {
      chain.doFilter(request, response);
      return;
    }
    log.warn(
        "Rejected direct access to {} {} from {}",
        request.getMethod(),
        request.getRequestURI(),
        request.getRemoteAddr());
    ServletSecurityErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "Access denied", null);
  }
}
