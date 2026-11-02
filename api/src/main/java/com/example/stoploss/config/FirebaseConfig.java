package com.example.stoploss.config;

import com.example.stoploss.auth.FirebaseTokenVerifier;
import com.example.stoploss.auth.TokenVerifier;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.IOException;
import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * Firebase Admin for the configured project: Application Default Credentials in production, the Auth emulator locally
 * (firebase-admin reads {@code FIREBASE_AUTH_EMULATOR_HOST} itself). The emulator guard runs at startup in every mode.
 */
@Configuration(proxyBeanMethods = false)
public class FirebaseConfig {

  @Bean
  AppSettings appSettings(Environment env) {
    AppSettings settings = AppSettings.from(env);
    AppSettings.assertSafeEmulatorConfig(settings.firebaseEmulatorHost(), settings.firebaseProjectId());
    return settings;
  }

  /** Created on first use only (token verification or seeding users), so one-shot modes and tests never build it. */
  @Bean(destroyMethod = "delete")
  @Lazy
  FirebaseApp firebaseApp(AppSettings settings) {
    AppSettings.assertSafeEmulatorConfig(settings.firebaseEmulatorHost(), settings.firebaseProjectId());
    GoogleCredentials credentials = settings.emulator() ? emulatorCredentials() : new LazyApplicationDefaultCredentials(List.of());
    FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(credentials);
    // Unset (only possible outside the emulator): firebase-admin falls back to the credentials' project.
    if (settings.firebaseProjectId() != null) options.setProjectId(settings.firebaseProjectId());
    return FirebaseApp.initializeApp(options.build(), "stoploss-api");
  }

  /** The production verifier. Tests replace this bean; nothing else in main code builds a {@link TokenVerifier}. */
  @Bean
  TokenVerifier tokenVerifier(ObjectProvider<FirebaseApp> firebaseApp) {
    return new FirebaseTokenVerifier(firebaseApp);
  }

  /** The Auth emulator accepts the admin token {@code owner}; no Google credentials are needed or used. */
  static GoogleCredentials emulatorCredentials() {
    return GoogleCredentials.create(new AccessToken("owner", Date.from(Instant.parse("9999-12-31T00:00:00Z"))));
  }

  /**
   * Application Default Credentials resolved on first use. Verifying ID tokens needs only the project id (Google's
   * public keys are fetched without credentials); only user management (seeding real Firebase users) needs an access
   * token. Like firebase-admin for Node, the API therefore starts without ADC and fails only an operation that needs it.
   */
  static final class LazyApplicationDefaultCredentials extends GoogleCredentials {
    @Serial private static final long serialVersionUID = 1L;
    private final Collection<String> scopes;
    private transient volatile GoogleCredentials delegate;

    LazyApplicationDefaultCredentials(Collection<String> scopes) {
      this.scopes = List.copyOf(scopes);
    }

    @Override
    public AccessToken refreshAccessToken() throws IOException {
      GoogleCredentials d = delegate;
      if (d == null) {
        GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
        d = scopes.isEmpty() ? adc : adc.createScoped(scopes);
        delegate = d;
      }
      d.refreshIfExpired();
      return d.getAccessToken();
    }

    @Override
    public boolean createScopedRequired() {
      return false;
    }

    @Override
    public GoogleCredentials createScoped(Collection<String> newScopes) {
      return new LazyApplicationDefaultCredentials(newScopes);
    }
  }
}
