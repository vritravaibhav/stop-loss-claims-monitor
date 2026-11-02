package com.example.stoploss;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;

/**
 * {@code java -jar stoploss-api.jar} serves the API. {@code --app.mode=migrate|seed|load-sample} runs that one job
 * without a web server and exits (0 on success, 1 on failure).
 */
@SpringBootApplication
public class StoplossApplication {

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(StoplossApplication.class);
    String mode = mode(args);
    if (mode == null) {
      app.run(args);
      return;
    }
    if (!AppModes.MODES.contains(mode)) {
      System.err.println("unknown app.mode " + mode + "; expected one of " + AppModes.MODES);
      System.exit(2);
    }
    app.setWebApplicationType(WebApplicationType.NONE);
    if (mode.equals("migrate")) {
      // The Cloud Run service runs with SPRING_FLYWAY_ENABLED=false so only the migration job migrates; that job must
      // migrate whatever the inherited environment says. Highest-precedence source, so spring.flyway.* still applies.
      app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources()
          .addFirst(new MapPropertySource("app.mode=migrate", Map.of("spring.flyway.enabled", "true"))));
    }
    int exitCode;
    try (ConfigurableApplicationContext ctx = app.run(args)) {
      ctx.getBean(AppModes.class).run(mode);
      exitCode = 0;
    } catch (Throwable e) {
      LoggerFactory.getLogger(StoplossApplication.class).error("{} failed", mode, e);
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  /** {@code --app.mode=...} from the command line, else the {@code APP_MODE} environment variable. */
  static String mode(String[] args) {
    String fromArgs = new SimpleCommandLinePropertySource(args).getProperty("app.mode");
    String mode = fromArgs != null ? fromArgs : System.getenv("APP_MODE");
    return mode == null || mode.isBlank() ? null : mode.strip();
  }
}
