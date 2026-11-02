package com.example.stoploss.auth;

/**
 * Verifies a bearer token or throws. A Spring bean so tests can replace it with a fake; production only ever builds
 * {@link FirebaseTokenVerifier} (see {@code FirebaseConfig}).
 */
public interface TokenVerifier {
  VerifiedIdentity verify(String idToken) throws Exception;

  /** What the API needs from a verified ID token. {@code email} may be null. */
  record VerifiedIdentity(String email, boolean emailVerified) {}
}
