package com.example.stoploss.auth;

import com.example.stoploss.web.HttpError;
import jakarta.servlet.http.HttpServletRequest;

/** The caller, as loaded from {@code app_user} by {@link AuthFilter}. Role and brokerage come from the DB row. */
public record AppUser(long id, String email, String role, Brokerage brokerage) {
  public static final String ANALYST = "analyst";
  public static final String BROKER = "broker";
  static final String REQUEST_ATTRIBUTE = AppUser.class.getName();

  public record Brokerage(String id, String name) {}

  public boolean isAnalyst() {
    return ANALYST.equals(role);
  }

  /** The authenticated caller of this request (every {@code /api} request has one, or it never got this far). */
  public static AppUser current(HttpServletRequest request) {
    Object user = request.getAttribute(REQUEST_ATTRIBUTE);
    if (user instanceof AppUser u) return u;
    throw new IllegalStateException("AppUser.current() used on a request that AuthFilter did not authenticate");
  }

  /** 403 unless the caller is an analyst. Call before reading the request body. */
  public AppUser requireAnalyst() {
    if (!isAnalyst()) throw new HttpError(403, "Only analysts can do this");
    return this;
  }
}
