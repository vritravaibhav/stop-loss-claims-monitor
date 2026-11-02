package com.example.stoploss.support;

import com.example.stoploss.auth.TokenVerifier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only token verifier: {@code test:verified:<email>} / {@code test:unverified:<email>} / {@code test:verified:} (no
 * email). Anything else is rejected like a bad Firebase token. Lives in the test sources, so production cannot use it.
 */
public final class FakeTokenVerifier implements TokenVerifier {
  private static final Pattern TOKEN = Pattern.compile("test:(verified|unverified):(.*)");

  public static final String ANALYST = "analyst@example.test";
  public static final String BROKER_A = "broker.a@example.test";
  public static final String BROKER_B = "broker.b@example.test";

  @Override
  public VerifiedIdentity verify(String idToken) {
    Matcher m = TOKEN.matcher(idToken);
    if (!m.matches()) throw new IllegalArgumentException("invalid token");
    return new VerifiedIdentity(m.group(2).isEmpty() ? null : m.group(2), m.group(1).equals("verified"));
  }

  public static String bearer(String email) {
    return bearer(email, true);
  }

  public static String bearer(String email, boolean verified) {
    return "Bearer test:" + (verified ? "verified" : "unverified") + ":" + email;
  }
}
