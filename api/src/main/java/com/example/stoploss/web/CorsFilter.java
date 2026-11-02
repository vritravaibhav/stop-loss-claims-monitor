package com.example.stoploss.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CORS for the configured dev origins only (in prod the web app and the API share an origin). Same behaviour as the
 * Node API's {@code cors} middleware: an allowed origin is echoed back, any other origin simply gets no CORS headers
 * (the request itself is not rejected, the browser enforces), and every OPTIONS request is answered here with 204
 * before authentication.
 */
public final class CorsFilter extends OncePerRequestFilter {
  private final Set<String> origins;

  public CorsFilter(List<String> origins) {
    this.origins = Set.copyOf(origins);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    if (origin != null && origins.contains(origin)) response.setHeader("Access-Control-Allow-Origin", origin);
    response.addHeader("Vary", "Origin");
    if ("OPTIONS".equals(request.getMethod())) {
      response.setHeader("Access-Control-Allow-Methods", "GET,POST");
      response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type");
      response.setHeader("Access-Control-Max-Age", "600");
      response.setStatus(204);
      response.setContentLength(0);
      return;
    }
    chain.doFilter(request, response);
  }
}
