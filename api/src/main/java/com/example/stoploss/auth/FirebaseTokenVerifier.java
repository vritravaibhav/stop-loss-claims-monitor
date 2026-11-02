package com.example.stoploss.auth;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.ObjectProvider;

/** Firebase ID token verification (signature, expiry, audience = project id; unsigned tokens only with the emulator). */
public final class FirebaseTokenVerifier implements TokenVerifier {
  private final ObjectProvider<FirebaseApp> app;

  public FirebaseTokenVerifier(ObjectProvider<FirebaseApp> app) {
    this.app = app;
  }

  @Override
  public VerifiedIdentity verify(String idToken) throws Exception {
    FirebaseToken decoded = FirebaseAuth.getInstance(app.getObject()).verifyIdToken(idToken);
    return new VerifiedIdentity(decoded.getEmail(), decoded.isEmailVerified());
  }
}
