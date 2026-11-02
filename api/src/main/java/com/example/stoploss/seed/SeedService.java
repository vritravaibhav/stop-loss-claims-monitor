package com.example.stoploss.seed;

import com.example.stoploss.config.AppSettings;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent seed: upserts brokerages, groups, policy years, members, lasers and app users from {@code DATA_DIR}; then,
 * with the Auth emulator (or {@code SEED_FIREBASE_USERS=true}), creates/updates the seed logins in Firebase Auth with
 * password {@code SEED_PASSWORD} (default {@value #DEFAULT_PASSWORD}, emulator only), always email-verified.
 */
@Service
public class SeedService {
  private static final Logger log = LoggerFactory.getLogger(SeedService.class);
  public static final String DEFAULT_PASSWORD = "Password123!";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AppSettings settings;
  private final ObjectProvider<FirebaseApp> firebaseApp;

  public SeedService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, AppSettings settings,
      ObjectProvider<FirebaseApp> firebaseApp) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.settings = settings;
    this.firebaseApp = firebaseApp;
  }

  /** The whole seed: database rows, then Firebase users when configured. */
  public void seed() throws Exception {
    Map<String, Integer> counts = seedDatabase(ReferenceData.load(settings.dataDir()));
    log.info("seed: database upserted from {}: {}", settings.dataDir(), counts);

    if (!settings.emulator() && !settings.seedFirebaseUsers()) {
      log.info("seed: Firebase users skipped (set FIREBASE_AUTH_EMULATOR_HOST or SEED_FIREBASE_USERS=true)");
      return;
    }
    String password = !settings.seedPassword().isEmpty()
        ? settings.seedPassword()
        : settings.emulator() ? DEFAULT_PASSWORD : null;
    if (password == null) {
      throw new IllegalStateException("SEED_PASSWORD must be set to seed users in a real Firebase project");
    }
    FirebaseAuth auth = FirebaseAuth.getInstance(firebaseApp.getObject());
    List<String> emails = ReferenceData.SEED_USERS.stream().map(ReferenceData.SeedUser::email).toList();
    FirebaseUsers.retryWhileUnavailable(() -> {
      FirebaseUsers.upsert(auth, emails, password, m -> log.info("seed: {}", m));
      return null;
    }, 30, 1000);
  }

  /** Database part of the seed, in one transaction; several API instances may start at once, so one at a time. */
  public Map<String, Integer> seedDatabase(ReferenceData data) {
    return tx.execute(status -> {
      jdbc.query("SELECT pg_advisory_xact_lock(hashtext('stoploss.seed'))", Map.of(), rs -> null);
      return upsert(data);
    });
  }

  private Map<String, Integer> upsert(ReferenceData data) {
    Map<String, String> brokerageOf = new LinkedHashMap<>();
    for (ReferenceData.SeedBrokerage b : ReferenceData.SEED_BROKERAGES) {
      jdbc.update("INSERT INTO brokerage (id, name) VALUES (:id, :name) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
          Map.of("id", b.id(), "name", b.name()));
      for (String g : b.groups()) brokerageOf.put(g, b.id());
    }

    for (ReferenceData.Group g : data.groups()) {
      jdbc.update(
          """
          INSERT INTO employer_group (id, name, brokerage_id) VALUES (:id, :name, :brokerageId)
          ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, brokerage_id = EXCLUDED.brokerage_id""",
          new MapSqlParameterSource()
              .addValue("id", g.id())
              .addValue("name", g.name())
              .addValue("brokerageId", brokerageOf.get(g.id()), Types.VARCHAR));
    }

    for (ReferenceData.PolicyYear p : data.policyYears()) {
      jdbc.update(
          """
          INSERT INTO policy_year (group_id, start_date, end_date, specific_deductible_cents)
          VALUES (:groupId, CAST(:startDate AS date), CAST(:endDate AS date), :deductible)
          ON CONFLICT (group_id, start_date)
          DO UPDATE SET end_date = EXCLUDED.end_date, specific_deductible_cents = EXCLUDED.specific_deductible_cents""",
          Map.of("groupId", p.groupId(), "startDate", p.startDate(), "endDate", p.endDate(),
              "deductible", p.deductibleCents()));
    }

    List<ReferenceData.Member> m = data.members();
    jdbc.update(
        """
        INSERT INTO member (id, group_id, first_name, last_name, date_of_birth, relationship)
        SELECT * FROM unnest(CAST(:ids AS text[]), CAST(:groupIds AS text[]), CAST(:firstNames AS text[]),
                             CAST(:lastNames AS text[]), CAST(:dobs AS date[]), CAST(:relationships AS text[]))
        ON CONFLICT (id) DO UPDATE SET group_id = EXCLUDED.group_id, first_name = EXCLUDED.first_name,
          last_name = EXCLUDED.last_name, date_of_birth = EXCLUDED.date_of_birth, relationship = EXCLUDED.relationship""",
        new MapSqlParameterSource()
            .addValue("ids", text(m.stream().map(ReferenceData.Member::id).toList()))
            .addValue("groupIds", text(m.stream().map(ReferenceData.Member::groupId).toList()))
            .addValue("firstNames", text(m.stream().map(ReferenceData.Member::firstName).toList()))
            .addValue("lastNames", text(m.stream().map(ReferenceData.Member::lastName).toList()))
            .addValue("dobs", text(m.stream().map(ReferenceData.Member::dateOfBirth).toList()))
            .addValue("relationships", text(m.stream().map(ReferenceData.Member::relationship).toList())));

    for (ReferenceData.Laser l : data.lasers()) {
      record Target(int policyYearId, String memberGroup) {}
      List<Target> rows = jdbc.query(
          """
          SELECT py.id AS policy_year_id, (SELECT group_id FROM member WHERE id = :memberId) AS member_group
          FROM policy_year py WHERE py.group_id = :groupId AND py.start_date = CAST(:policyStart AS date)""",
          Map.of("groupId", l.groupId(), "memberId", l.memberId(), "policyStart", l.policyStart()),
          (rs, i) -> new Target(rs.getInt("policy_year_id"), rs.getString("member_group")));
      if (rows.isEmpty()) {
        throw new IllegalStateException("laser for " + l.memberId() + ": group " + l.groupId()
            + " has no policy year starting " + l.policyStart());
      }
      if (!l.groupId().equals(rows.getFirst().memberGroup())) {
        throw new IllegalStateException("laser for " + l.memberId() + ": member is not in group " + l.groupId());
      }
      jdbc.update(
          """
          INSERT INTO laser (member_id, policy_year_id, specific_deductible_cents) VALUES (:memberId, :policyYearId, :deductible)
          ON CONFLICT (member_id, policy_year_id) DO UPDATE SET specific_deductible_cents = EXCLUDED.specific_deductible_cents""",
          Map.of("memberId", l.memberId(), "policyYearId", rows.getFirst().policyYearId(), "deductible",
              l.deductibleCents()));
    }

    for (ReferenceData.SeedUser u : ReferenceData.SEED_USERS) {
      jdbc.update(
          """
          INSERT INTO app_user (email, role, brokerage_id) VALUES (:email, :role, :brokerageId)
          ON CONFLICT (email) DO UPDATE SET role = EXCLUDED.role, brokerage_id = EXCLUDED.brokerage_id""",
          new MapSqlParameterSource()
              .addValue("email", u.email())
              .addValue("role", u.role())
              .addValue("brokerageId", u.brokerageId(), Types.VARCHAR));
    }

    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("brokerages", ReferenceData.SEED_BROKERAGES.size());
    counts.put("groups", data.groups().size());
    counts.put("policyYears", data.policyYears().size());
    counts.put("members", data.members().size());
    counts.put("lasers", data.lasers().size());
    counts.put("users", ReferenceData.SEED_USERS.size());
    return counts;
  }

  private static SqlArrayValue text(List<String> values) {
    return new SqlArrayValue("text", values.toArray(new String[0]));
  }
}
