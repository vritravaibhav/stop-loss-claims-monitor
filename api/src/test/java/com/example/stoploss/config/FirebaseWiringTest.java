package com.example.stoploss.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.stoploss.TestData;
import com.example.stoploss.auth.FirebaseTokenVerifier;
import com.example.stoploss.auth.TokenVerifier;
import com.google.firebase.FirebaseApp;
import com.google.firebase.internal.FirebaseProcessEnvironment;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * The production Firebase wiring (no fake): the app builds without Google credentials, a real-project verifier rejects
 * garbage, and in emulator mode an unsigned emulator token is accepted only for the configured project.
 */
class FirebaseWiringTest {
  private FirebaseApp app;

  @AfterEach
  void cleanUp() {
    if (app != null) app.delete();
    FirebaseProcessEnvironment.clearCache();
  }

  static AppSettings settings(String emulatorHost, String projectId) {
    return new AppSettings(TestData.DATA_DIR, List.of(), false, "", false, emulatorHost, projectId, null);
  }

  TokenVerifier verifierFor(AppSettings settings) {
    app = new FirebaseConfig().firebaseApp(settings);
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    beans.registerSingleton("firebaseApp", app);
    return new FirebaseTokenVerifier(beans.getBeanProvider(FirebaseApp.class));
  }

  static String unsignedToken(String projectId, String email) {
    long now = Instant.now().getEpochSecond();
    String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
    String payload = "{\"iss\":\"https://securetoken.google.com/" + projectId + "\",\"aud\":\"" + projectId
        + "\",\"auth_time\":" + now + ",\"user_id\":\"u1\",\"sub\":\"u1\",\"iat\":" + now + ",\"exp\":" + (now + 3600)
        + ",\"email\":\"" + email + "\",\"email_verified\":true}";
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    return b64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".";
  }

  @Test
  void aRealProjectBuildsWithoutCredentialsAndRejectsAnythingButASignedToken() {
    TokenVerifier verifier = verifierFor(settings("", "acme-prod"));
    assertThatThrownBy(() -> verifier.verify("not-a-jwt")).isInstanceOf(Exception.class);
    // an unsigned (emulator-style) token is refused outside emulator mode
    assertThatThrownBy(() -> verifier.verify(unsignedToken("acme-prod", "analyst@example.test")))
        .isInstanceOf(Exception.class);
  }

  @Test
  void theEmulatorAcceptsUnsignedTokensForTheDemoProjectOnly() throws Exception {
    // In emulator mode firebase-admin (Java) also looks the user up in the emulator on every verification, to refuse
    // tokens of deleted or disabled accounts. A stub answers that lookup.
    List<String> lookups = new CopyOnWriteArrayList<>();
    HttpServer emulator = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    emulator.createContext("/", exchange -> {
      lookups.add(exchange.getRequestURI().getPath());
      byte[] body = "{\"users\":[{\"localId\":\"u1\",\"email\":\"analyst@example.test\",\"emailVerified\":true}]}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    emulator.start();
    try {
      String host = "127.0.0.1:" + emulator.getAddress().getPort();
      FirebaseProcessEnvironment.setenv("FIREBASE_AUTH_EMULATOR_HOST", host);
      TokenVerifier verifier = verifierFor(settings(host, "demo-stoploss"));
      assertThat(verifier.verify(unsignedToken("demo-stoploss", "analyst@example.test")))
          .isEqualTo(new TokenVerifier.VerifiedIdentity("analyst@example.test", true));
      assertThat(lookups).containsExactly("/identitytoolkit.googleapis.com/v1/projects/demo-stoploss/accounts:lookup");
      // a token for another project is refused before any lookup
      assertThatThrownBy(() -> verifier.verify(unsignedToken("demo-other", "analyst@example.test")))
          .isInstanceOf(Exception.class);
      assertThat(lookups).hasSize(1);
    } finally {
      emulator.stop(0);
    }
  }

  @Test
  void theEmulatorIsRefusedForANonDemoProject() {
    assertThatThrownBy(() -> new FirebaseConfig().firebaseApp(settings("firebase:9099", "acme-prod")))
        .hasMessageContaining("demo-*");
  }
}
