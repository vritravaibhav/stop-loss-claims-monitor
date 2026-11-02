package com.example.stoploss;

import com.example.stoploss.config.AppSettings;
import com.example.stoploss.seed.LoadSampleService;
import com.example.stoploss.seed.SeedService;
import com.example.stoploss.upload.UploadResult;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * What the process does besides serving HTTP:
 *
 * <ul>
 *   <li>web server (no {@code app.mode}): Flyway migrates during startup (unless {@code spring.flyway.enabled=false});
 *       with {@code SEED_ON_STARTUP=true} the seed runs next, still before the web server accepts connections;
 *   <li>{@code --app.mode=migrate|seed|load-sample}: one-shot, no web server, {@code SEED_ON_STARTUP} ignored; the
 *       process exits 0 on success, 1 on failure (see {@link StoplossApplication#main}).
 * </ul>
 */
@Component
public class AppModes implements SmartInitializingSingleton {
  private static final Logger log = LoggerFactory.getLogger(AppModes.class);
  public static final List<String> MODES = List.of("migrate", "seed", "load-sample");

  private final AppSettings settings;
  private final SeedService seed;
  private final LoadSampleService loadSample;
  private final ObjectProvider<Flyway> flyway;

  public AppModes(AppSettings settings, SeedService seed, LoadSampleService loadSample, ObjectProvider<Flyway> flyway) {
    this.settings = settings;
    this.seed = seed;
    this.loadSample = loadSample;
    this.flyway = flyway;
  }

  /** Runs once every bean exists (so after Flyway) and before the web server starts listening. */
  @Override
  public void afterSingletonsInstantiated() {
    if (settings.mode() != null || !settings.seedOnStartup()) return;
    try {
      seed.seed();
    } catch (Exception e) {
      throw new IllegalStateException("seed on startup failed: " + e.getMessage(), e);
    }
  }

  /** The one-shot mode's work (the context is already started, so migrations have run). */
  public void run(String mode) throws Exception {
    switch (mode) {
      case "migrate" -> {
        Flyway f = flyway.getIfAvailable();
        if (f == null) throw new IllegalStateException("Flyway is not configured");
        var current = f.info().current();
        log.info("migrate: schema is at version {}", current == null ? "(empty)" : current.getVersion());
      }
      case "seed" -> seed.seed();
      case "load-sample" -> {
        var dir = settings.dataDir().resolve("claims");
        List<UploadResult> results = loadSample.loadSampleClaims(dir, "load-sample", r -> {
          UploadResult.Counts c = r.counts();
          log.info(UploadResult.PROCESSED.equals(r.status())
              ? "%s: rows=%d applied=%d unchanged=%d olderIgnored=%d summary=%d errors=%d alerts=%d".formatted(
                  r.filename(), c.rows(), c.applied(), c.unchanged(), c.olderIgnored(), c.summary(), c.errors(),
                  r.alertsRaised().size())
              : "%s: REJECTED %s".formatted(r.filename(), r.rejectionReason()));
          for (UploadResult.RowError e : r.errors()) log.info("    line {}: {}", e.rowNumber(), e.reason());
        });
        log.info("load-sample: {} files from {}", results.size(), dir);
      }
      default -> throw new IllegalArgumentException("unknown app.mode " + mode + "; expected one of " + MODES);
    }
  }
}
