package com.example.stoploss.auth;

import com.example.stoploss.domain.Js;
import com.example.stoploss.web.ErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Types;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every {@code /api} request, before any body is read: {@code Cache-Control: no-store}; 401 unless it carries a valid
 * ID token; 403 unless the token's verified email is a row in {@code app_user}. Role and brokerage come from that row,
 * never from token claims.
 */
public final class AuthFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

  // /^Bearer[ \t]+(\S+)[ \t]*$/i with JavaScript's \S (anything but ECMAScript whitespace).
  private static final String NOT_SPACE =
      "[^\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
  private static final Pattern BEARER = Pattern.compile("Bearer[ \\t]+(" + NOT_SPACE + "+)[ \\t]*", Pattern.CASE_INSENSITIVE);

  private final TokenVerifier verifier;
  private final NamedParameterJdbcTemplate jdbc;
  private final ErrorResponses errors;

  public AuthFilter(TokenVerifier verifier, NamedParameterJdbcTemplate jdbc, ErrorResponses errors) {
    this.verifier = verifier;
    this.jdbc = jdbc;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // Member health spend: never cached by browsers or proxies (errors included).
    response.setHeader("Cache-Control", "no-store");

    String header = request.getHeader("Authorization");
    Matcher match = BEARER.matcher(header == null ? "" : header);
    if (!match.matches()) {
      response.setHeader("WWW-Authenticate", "Bearer");
      errors.write(response, 401, "Missing bearer token");
      return;
    }

    TokenVerifier.VerifiedIdentity identity;
    try {
      identity = verifier.verify(match.group(1));
    } catch (Exception e) {
      response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
      errors.write(response, 401, "Invalid or expired token");
      return;
    }
    if (identity.email() == null || identity.email().isEmpty()) {
      errors.write(response, 403, "This sign-in has no email address");
      return;
    }
    // Accounts are matched by email, so the email must be proven to belong to the person signing in.
    if (!identity.emailVerified()) {
      errors.write(response, 403, "Email address is not verified");
      return;
    }

    AppUser user;
    try {
      user = findUser(Js.trim(identity.email()));
    } catch (RuntimeException e) {
      log.error("user lookup failed", e);
      errors.write(response, 500, "Internal server error");
      return;
    }
    if (user == null) {
      errors.write(response, 403, "This account has no access to the stop-loss monitor");
      return;
    }
    request.setAttribute(AppUser.REQUEST_ATTRIBUTE, user);
    chain.doFilter(request, response);
  }

  private AppUser findUser(String email) {
    List<AppUser> rows = jdbc.query(
        """
        SELECT u.id, u.email, u.role, b.id AS brokerage_id, b.name AS brokerage_name
        FROM app_user u LEFT JOIN brokerage b ON b.id = u.brokerage_id
        WHERE u.email = lower(:email)""",
        new MapSqlParameterSource().addValue("email", email, Types.VARCHAR),
        (rs, i) -> {
          String brokerageId = rs.getString("brokerage_id");
          String brokerageName = rs.getString("brokerage_name");
          return new AppUser(
              rs.getLong("id"),
              rs.getString("email"),
              rs.getString("role"),
              brokerageId == null || brokerageId.isEmpty()
                  ? null
                  : new AppUser.Brokerage(brokerageId, brokerageName == null ? brokerageId : brokerageName));
        });
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
