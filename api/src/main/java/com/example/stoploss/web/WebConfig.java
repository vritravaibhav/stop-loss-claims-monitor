package com.example.stoploss.web;

import com.example.stoploss.auth.AuthFilter;
import com.example.stoploss.auth.TokenVerifier;
import com.example.stoploss.config.AppSettings;
import jakarta.servlet.Filter;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Servlet filters, in order: security headers → CORS (dev only) → authentication for {@code /api/*}. */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

  /** "Today" for the current policy year (UTC). Tests replace it. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  FilterRegistrationBean<Filter> securityHeadersFilter() {
    FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>((req, res, chain) -> {
      ((jakarta.servlet.http.HttpServletResponse) res).setHeader("X-Content-Type-Options", "nosniff");
      chain.doFilter(req, res);
    });
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    reg.addUrlPatterns("/*");
    return reg;
  }

  @Bean
  FilterRegistrationBean<CorsFilter> corsFilter(AppSettings settings) {
    FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(settings.corsOrigins()));
    reg.setEnabled(!settings.corsOrigins().isEmpty());
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    reg.addUrlPatterns("/*");
    return reg;
  }

  @Bean
  FilterRegistrationBean<AuthFilter> authFilter(TokenVerifier verifier, NamedParameterJdbcTemplate jdbc,
      ErrorResponses errors) {
    FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>(new AuthFilter(verifier, jdbc, errors));
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
    reg.addUrlPatterns("/api/*");
    return reg;
  }
}
