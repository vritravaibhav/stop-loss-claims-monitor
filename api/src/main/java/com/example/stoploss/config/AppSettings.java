package com.example.stoploss.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * Process settings, read from the environment (env vars, or the same names as {@code --NAME=value} arguments).
 *
 * @param dataDir {@code DATA_DIR} (default {@code ../data}): reference CSVs and {@code claims/}
 * @param corsOrigins {@code CORS_ORIGINS}: comma list of browser origins allowed by CORS (dev only)
 * @param seedOnStartup {@code SEED_ON_STARTUP=true}: seed before the web server starts (ignored by one-shot modes)
 * @param seedPassword {@code SEED_PASSWORD}: password for the seeded Firebase users (empty: unset)
 * @param seedFirebaseUsers {@code SEED_FIREBASE_USERS=true}: seed Firebase users even without the emulator
 * @param firebaseEmulatorHost {@code FIREBASE_AUTH_EMULATOR_HOST} (empty: real Firebase)
 * @param firebaseProjectId {@code FIREBASE_PROJECT_ID}, else {@code GCLOUD_PROJECT}, else {@code GOOGLE_CLOUD_PROJECT},
 *     else {@code demo-stoploss}
 * @param mode {@code app.mode}: {@code migrate}, {@code seed} or {@code load-sample}; null for the web server
 */
public record AppSettings(
    Path dataDir,
    List<String> corsOrigins,
    boolean seedOnStartup,
    String seedPassword,
    boolean seedFirebaseUsers,
    String firebaseEmulatorHost,
    String firebaseProjectId,
    String mode) {

  public static final String DEFAULT_PROJECT_ID = "demo-stoploss";

  public static AppSettings from(Environment env) {
    return new AppSettings(
        Path.of(env.getProperty("DATA_DIR", "../data")).toAbsolutePath().normalize(),
        parseCorsOrigins(env.getProperty("CORS_ORIGINS")),
        isTrue(env.getProperty("SEED_ON_STARTUP")),
        env.getProperty("SEED_PASSWORD", ""),
        isTrue(env.getProperty("SEED_FIREBASE_USERS")),
        env.getProperty("FIREBASE_AUTH_EMULATOR_HOST", ""),
        firebaseProjectId(env.getProperty("FIREBASE_PROJECT_ID"), env.getProperty("GCLOUD_PROJECT"),
            env.getProperty("GOOGLE_CLOUD_PROJECT")),
        blankToNull(env.getProperty("app.mode")));
  }

  public boolean emulator() {
    return !firebaseEmulatorHost.isEmpty();
  }

  /** {@code " http://a, http://b ,"} → {@code [http://a, http://b]}. */
  public static List<String> parseCorsOrigins(String value) {
    if (value == null) return List.of();
    return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  /** First non-blank of the candidates, else {@value #DEFAULT_PROJECT_ID}. */
  public static String firebaseProjectId(String... candidates) {
    for (String c : candidates) if (c != null && !c.isBlank()) return c.strip();
    return DEFAULT_PROJECT_ID;
  }

  /**
   * With FIREBASE_AUTH_EMULATOR_HOST set, firebase-admin accepts <em>unsigned</em> tokens. Only allow that for
   * {@code demo-*} project ids, which can never be real Firebase projects, so a stray env var cannot open up production.
   */
  public static void assertSafeEmulatorConfig(String emulatorHost, String projectId) {
    if (emulatorHost == null || emulatorHost.isEmpty()) return;
    if (projectId == null || !projectId.startsWith("demo-")) {
      throw new IllegalStateException("FIREBASE_AUTH_EMULATOR_HOST=" + emulatorHost
          + " is set but the Firebase project id is " + (projectId == null ? "unset" : projectId)
          + "; the Auth emulator may only be used with a demo-* project id");
    }
  }

  private static boolean isTrue(String v) {
    return v != null && v.strip().equalsIgnoreCase("true");
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }
}
