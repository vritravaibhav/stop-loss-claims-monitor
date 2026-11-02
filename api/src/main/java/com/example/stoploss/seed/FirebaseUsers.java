package com.example.stoploss.seed;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Seed logins in Firebase Auth (the emulator locally). */
public final class FirebaseUsers {
  private FirebaseUsers() {}

  /** Create each user, or reset its password; always email-verified (the API rejects unverified emails). */
  public static void upsert(FirebaseAuth auth, List<String> emails, String password, Consumer<String> log)
      throws FirebaseAuthException {
    for (String email : emails) {
      String uid = null;
      try {
        uid = auth.getUserByEmail(email).getUid();
      } catch (FirebaseAuthException e) {
        if (e.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) throw e;
      }
      if (uid != null) {
        auth.updateUser(new UserRecord.UpdateRequest(uid).setPassword(password).setEmailVerified(true).setDisabled(false));
        log.accept("firebase user updated: " + email);
      } else {
        auth.createUser(new UserRecord.CreateRequest().setEmail(email).setPassword(password).setEmailVerified(true));
        log.accept("firebase user created: " + email);
      }
    }
  }

  /** Retry {@code fn} while the error looks like "service not up yet" (the emulator may still be starting). */
  public static <T> T retryWhileUnavailable(Callable<T> fn, int attempts, long delayMs) throws Exception {
    for (int i = 1; ; i++) {
      try {
        return fn.call();
      } catch (Exception e) {
        if (!isTransient(e) || i >= attempts) throw e;
        Thread.sleep(delayMs);
      }
    }
  }

  /** Connection refused / reset, unknown host: the network equivalents of ECONNREFUSED, ECONNRESET, ENOTFOUND. */
  static boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConnectException || t instanceof NoRouteToHostException || t instanceof UnknownHostException) {
        return true;
      }
      if (t instanceof SocketException && t.getMessage() != null && t.getMessage().contains("reset")) return true;
      if (t.getMessage() != null && t.getMessage().contains("Unexpected end of file from server")) return true;
      if (t.getCause() == t) break;
    }
    return false;
  }
}
