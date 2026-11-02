package com.example.stoploss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.stoploss.config.AppSettings;
import com.example.stoploss.groups.GroupQueries;
import com.example.stoploss.groups.GroupQueries.PolicyYearRow;
import com.example.stoploss.seed.LoadSampleService;
import com.example.stoploss.seed.SeedService;
import com.example.stoploss.upload.IngestService;
import com.example.stoploss.web.Params;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelpersTest {

  @Nested
  class CurrentPolicyYearId {
    final List<PolicyYearRow> years = List.of(
        new PolicyYearRow(1, "2025-07-01", "2026-06-30", 1),
        new PolicyYearRow(2, "2026-07-01", "2027-06-30", 1));

    @Test
    void picksTheYearContainingTodayInclusiveBounds() {
      assertThat(GroupQueries.currentPolicyYearId(years, "2026-06-30")).isEqualTo(1);
      assertThat(GroupQueries.currentPolicyYearId(years, "2026-07-01")).isEqualTo(2);
    }

    @Test
    void fallsBackToTheLatestYear() {
      assertThat(GroupQueries.currentPolicyYearId(years, "2030-01-01")).isEqualTo(2);
      assertThat(GroupQueries.currentPolicyYearId(years, "2020-01-01")).isEqualTo(2);
      assertThat(GroupQueries.currentPolicyYearId(List.of(), "2026-01-01")).isNull();
    }
  }

  @Nested
  class FirebaseEmulatorGuard {
    @Test
    void allowsTheEmulatorOnlyWithADemoProjectId() {
      assertThatCode(() -> AppSettings.assertSafeEmulatorConfig("firebase:9099", "demo-stoploss")).doesNotThrowAnyException();
      assertThatThrownBy(() -> AppSettings.assertSafeEmulatorConfig("firebase:9099", "acme-prod"))
          .hasMessageContaining("demo-*");
      assertThatThrownBy(() -> AppSettings.assertSafeEmulatorConfig("firebase:9099", null)).hasMessageContaining("unset");
      assertThatCode(() -> AppSettings.assertSafeEmulatorConfig("", "acme-prod")).doesNotThrowAnyException();
      assertThatCode(() -> AppSettings.assertSafeEmulatorConfig(null, "acme-prod")).doesNotThrowAnyException();
    }

    @Test
    void readsTheProjectIdFromFirebaseProjectIdThenGcloudProjectThenGoogleCloudProjectElseDemoStoploss() {
      assertThat(AppSettings.firebaseProjectId(null, "b", "c")).isEqualTo("b");
      assertThat(AppSettings.firebaseProjectId("a", "b", null)).isEqualTo("a");
      assertThat(AppSettings.firebaseProjectId("", " ", "c")).isEqualTo("c");
      // No silent demo-* default: with the emulator host set and no explicit project id, startup must fail.
      assertThat(AppSettings.firebaseProjectId(null, null, null)).isNull();
      assertThatThrownBy(() -> AppSettings.assertSafeEmulatorConfig("firebase:9099", AppSettings.firebaseProjectId(null, " ", null)))
          .hasMessageContaining("unset");
    }
  }

  @Nested
  class SmallParsers {
    @Test
    void parseIdAcceptsPositiveIntegersOnly() {
      assertThat(Params.parseId("42")).isEqualTo(42L);
      for (String bad : new String[] {"0", "-1", "1.5", "1e3", "abc", "", " 1", "0012", null, "1234567890123456"}) {
        assertThat(Params.parseId(bad)).as(String.valueOf(bad)).isNull();
      }
    }

    @Test
    void parseCorsOriginsSplitsAndTrimsACommaList() {
      assertThat(AppSettings.parseCorsOrigins(" http://localhost:8081, http://127.0.0.1:8081 ,"))
          .containsExactly("http://localhost:8081", "http://127.0.0.1:8081");
      assertThat(AppSettings.parseCorsOrigins(null)).isEmpty();
    }

    @Test
    void baseFilenameStripsClientPaths() {
      assertThat(IngestService.baseFilename("C:\\fakepath\\GA_2026-04.csv")).isEqualTo("GA_2026-04.csv");
      assertThat(IngestService.baseFilename("dir/sub/GB_2026-05.csv ")).isEqualTo("GB_2026-05.csv");
      assertThat(IngestService.baseFilename("dir/")).isEqualTo("unnamed.csv");
      assertThat(IngestService.baseFilename("")).isEqualTo("unnamed.csv");
      assertThat(IngestService.baseFilename(null)).isEqualTo("unnamed.csv");
    }

    @Test
    void groupPrefixIsTheFileNameBeforeTheFirstUnderscore() {
      assertThat(IngestService.groupPrefixFromFilename("GA_2026-04_resend.csv")).isEqualTo("GA");
      assertThat(IngestService.groupPrefixFromFilename("gc-2_x.csv")).isEqualTo("gc-2");
      assertThat(IngestService.groupPrefixFromFilename("claims.csv")).isNull();
      assertThat(IngestService.groupPrefixFromFilename("_GA.csv")).isNull();
    }
  }

  @Nested
  class FileNameOrder {
    @Test
    void isPlainCodeUnitOrderNotLocaleOrder(@TempDir Path dir) throws Exception {
      for (String f : List.of("GB_2026-04.csv", "GA_2026-04_resend.csv", "GA_2026-04.csv", "GA_2026-10.CSV",
          "notes.txt")) {
        Files.writeString(dir.resolve(f), "x");
      }
      Files.createDirectory(dir.resolve("GA_dir.csv"));
      assertThat(LoadSampleService.listClaimFiles(dir))
          .containsExactly("GA_2026-04.csv", "GA_2026-04_resend.csv", "GA_2026-10.CSV", "GB_2026-04.csv");
    }
  }

  @Nested
  class Modes {
    @Test
    void modeComesFromTheCommandLine() {
      assertThat(StoplossApplication.mode(new String[] {"--app.mode=load-sample"})).isEqualTo("load-sample");
      assertThat(StoplossApplication.mode(new String[] {"--server.port=1", "--app.mode= migrate "})).isEqualTo("migrate");
    }

    @Test
    void oneShotModesIgnoreSeedOnStartupAndTheWebServerSeedsOnlyWhenAsked() {
      List<String> calls = new ArrayList<>();
      SeedService fakeSeed = new SeedService(null, null, null, null) {
        @Override
        public void seed() {
          calls.add("seed");
        }
      };
      for (String mode : AppModes.MODES) {
        new AppModes(settings(true, mode), fakeSeed, null, null).afterSingletonsInstantiated();
      }
      assertThat(calls).isEmpty();
      new AppModes(settings(false, null), fakeSeed, null, null).afterSingletonsInstantiated();
      assertThat(calls).isEmpty();
      new AppModes(settings(true, null), fakeSeed, null, null).afterSingletonsInstantiated();
      assertThat(calls).containsExactly("seed");
    }

    AppSettings settings(boolean seedOnStartup, String mode) {
      return new AppSettings(TestData.DATA_DIR, List.of(), seedOnStartup, "", false, "", "demo-stoploss", mode);
    }
  }
}
