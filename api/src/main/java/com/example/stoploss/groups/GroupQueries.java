package com.example.stoploss.groups;

import static com.example.stoploss.auth.Scope.VISIBLE_GROUPS;

import com.example.stoploss.auth.AppUser;
import com.example.stoploss.auth.Scope;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Group, spend and member queries; every one is scoped through {@code visible_group}. */
@Repository
public class GroupQueries {
  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper json;

  public GroupQueries(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record PolicyYearRow(long id, String startDate, String endDate, long deductibleCents) {}

  public record GroupRow(String id, String name, String brokerageId, String brokerageName, int memberCount,
      int openAlertCount, List<PolicyYearRow> policyYears) {}

  /** The policy year containing {@code today}, else the latest one (by start date); null if the group has none. */
  public static Long currentPolicyYearId(List<PolicyYearRow> years, String today) {
    for (PolicyYearRow y : years) {
      if (y.startDate().compareTo(today) <= 0 && today.compareTo(y.endDate()) <= 0) return y.id();
    }
    PolicyYearRow latest = null;
    for (PolicyYearRow y : years) if (latest == null || y.startDate().compareTo(latest.startDate()) > 0) latest = y;
    return latest == null ? null : latest.id();
  }

  /** Visible groups (all for analysts, own brokerage's for brokers), optionally just one. */
  public List<GroupRow> visibleGroups(AppUser user, String groupId) {
    return jdbc.query(
        "WITH " + VISIBLE_GROUPS + """

        SELECT g.id, g.name, b.id AS brokerage_id, b.name AS brokerage_name,
               (SELECT count(*) FROM member m WHERE m.group_id = g.id)::int AS member_count,
               (SELECT count(*) FROM alert a WHERE a.group_id = g.id AND a.status = 'open')::int AS open_alert_count,
               COALESCE((SELECT json_agg(json_build_object('id', py.id, 'startDate', py.start_date,
                                                           'endDate', py.end_date,
                                                           'deductibleCents', py.specific_deductible_cents)
                                         ORDER BY py.start_date)
                         FROM policy_year py WHERE py.group_id = g.id), '[]'::json) AS policy_years
        FROM visible_group g LEFT JOIN brokerage b ON b.id = g.brokerage_id
        WHERE CAST(:groupId AS text) IS NULL OR g.id = :groupId
        ORDER BY g.id""",
        Scope.params(user).addValue("groupId", groupId, Types.VARCHAR),
        (rs, i) -> new GroupRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("brokerage_id"),
            rs.getString("brokerage_name"),
            rs.getInt("member_count"),
            rs.getInt("open_alert_count"),
            json.readTree(rs.getString("policy_years")).valueStream()
                .map(GroupQueries::policyYear)
                .toList()));
  }

  private static PolicyYearRow policyYear(JsonNode y) {
    return new PolicyYearRow(y.get("id").asLong(), y.get("startDate").asString(), y.get("endDate").asString(),
        y.get("deductibleCents").asLong());
  }

  public record MemberSpendRow(String memberId, String firstName, String lastName, String relationship,
      long spendCents, int claimCount, long deductibleCents, boolean hasLaser, List<Integer> thresholds) {}

  /** Every member of the group with their spend in the policy year. Scoped again here, not only by the lookup. */
  public List<MemberSpendRow> memberSpend(AppUser user, String groupId, long policyYearId) {
    return jdbc.query(
        "WITH " + VISIBLE_GROUPS + """
        ,
        spend AS (
          SELECT cc.member_id, sum(cc.effective_amount_cents)::bigint AS spend_cents, count(*)::int AS claim_count
          FROM claim_current cc WHERE cc.policy_year_id = :policyYearId GROUP BY cc.member_id)
        SELECT m.id AS member_id, m.first_name, m.last_name, m.relationship,
               COALESCE(s.spend_cents, 0)::bigint AS spend_cents, COALESCE(s.claim_count, 0) AS claim_count,
               COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS deductible_cents,
               (l.member_id IS NOT NULL) AS has_laser,
               ARRAY(SELECT a.threshold FROM alert a
                     WHERE a.member_id = m.id AND a.policy_year_id = py.id ORDER BY a.threshold) AS thresholds
        FROM member m
        JOIN visible_group g ON g.id = m.group_id
        JOIN policy_year py ON py.id = :policyYearId AND py.group_id = m.group_id
        LEFT JOIN laser l ON l.member_id = m.id AND l.policy_year_id = py.id
        LEFT JOIN spend s ON s.member_id = m.id
        WHERE m.group_id = :groupId""",
        Scope.params(user).addValue("groupId", groupId, Types.VARCHAR).addValue("policyYearId", policyYearId, Types.BIGINT),
        (rs, i) -> new MemberSpendRow(
            rs.getString("member_id"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("relationship"),
            rs.getLong("spend_cents"),
            rs.getInt("claim_count"),
            rs.getLong("deductible_cents"),
            rs.getBoolean("has_laser"),
            Arrays.asList((Integer[]) rs.getArray("thresholds").getArray())));
  }

  public record MemberRow(String id, String groupId, String firstName, String lastName, String dateOfBirth,
      String relationship) {}

  public MemberRow member(AppUser user, String memberId) {
    List<MemberRow> rows = jdbc.query(
        "WITH " + VISIBLE_GROUPS + """

        SELECT m.id, m.group_id, m.first_name, m.last_name, m.date_of_birth, m.relationship
        FROM member m JOIN visible_group g ON g.id = m.group_id
        WHERE m.id = :memberId""",
        Scope.params(user).addValue("memberId", memberId, Types.VARCHAR),
        (rs, i) -> new MemberRow(rs.getString("id"), rs.getString("group_id"), rs.getString("first_name"),
            rs.getString("last_name"), rs.getString("date_of_birth"), rs.getString("relationship")));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public record MemberYearRow(long id, String startDate, String endDate, long deductibleCents, boolean hasLaser,
      long spendCents) {}

  public List<MemberYearRow> memberPolicyYears(AppUser user, String memberId) {
    return jdbc.query(
        "WITH " + VISIBLE_GROUPS + """

        SELECT py.id, py.start_date, py.end_date,
               COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS deductible_cents,
               (l.member_id IS NOT NULL) AS has_laser,
               COALESCE((SELECT sum(cc.effective_amount_cents) FROM claim_current cc
                         WHERE cc.member_id = m.id AND cc.policy_year_id = py.id), 0)::bigint AS spend_cents
        FROM member m
        JOIN visible_group g ON g.id = m.group_id
        JOIN policy_year py ON py.group_id = m.group_id
        LEFT JOIN laser l ON l.member_id = m.id AND l.policy_year_id = py.id
        WHERE m.id = :memberId
        ORDER BY py.start_date""",
        Scope.params(user).addValue("memberId", memberId, Types.VARCHAR),
        (rs, i) -> new MemberYearRow(rs.getLong("id"), rs.getString("start_date"), rs.getString("end_date"),
            rs.getLong("deductible_cents"), rs.getBoolean("has_laser"), rs.getLong("spend_cents")));
  }

  public record VersionRow(int version, String memberId, String status, long paidAmountCents, String serviceDate,
      String paidDate, String diagnosisCode, String filename, int rowNumber, String loadedAt, boolean isCurrent) {}

  public record ClaimRow(String claimId, Long policyYearId, int currentVersion, String status, long effectiveAmountCents,
      String serviceDate, List<VersionRow> versions) {}

  /** Claims whose current version belongs to this member, each with its full version history. */
  public List<ClaimRow> memberClaims(AppUser user, String memberId) {
    return jdbc.query(
        "WITH " + VISIBLE_GROUPS + """

        SELECT cc.claim_id, cc.policy_year_id, cc.version AS current_version, cur.status, cc.effective_amount_cents,
               cc.service_date,
               (SELECT json_agg(json_build_object(
                         'version', v.version, 'memberId', v.member_id, 'status', v.status,
                         'paidAmountCents', v.paid_amount_cents, 'serviceDate', v.service_date,
                         'paidDate', v.paid_date, 'diagnosisCode', v.diagnosis_code, 'filename', u.filename,
                         'rowNumber', v.row_number,
                         'loadedAt', to_char(v.loaded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                         'isCurrent', v.id = cc.claim_version_id)
                       ORDER BY v.version DESC)
                FROM claim_version v JOIN upload u ON u.id = v.upload_id
                WHERE v.group_id = cc.group_id AND v.claim_id = cc.claim_id) AS versions
        FROM claim_current cc
        JOIN visible_group g ON g.id = cc.group_id
        JOIN claim_version cur ON cur.id = cc.claim_version_id
        WHERE cc.member_id = :memberId
        ORDER BY cc.service_date DESC, cc.claim_id""",
        Scope.params(user).addValue("memberId", memberId, Types.VARCHAR),
        (rs, i) -> new ClaimRow(
            rs.getString("claim_id"),
            nullableLong(rs, "policy_year_id"),
            rs.getInt("current_version"),
            rs.getString("status"),
            rs.getLong("effective_amount_cents"),
            rs.getString("service_date"),
            json.readTree(rs.getString("versions")).valueStream().map(GroupQueries::version).toList()));
  }

  private static VersionRow version(JsonNode v) {
    return new VersionRow(
        v.get("version").asInt(),
        v.get("memberId").asString(),
        v.get("status").asString(),
        v.get("paidAmountCents").asLong(),
        v.get("serviceDate").asString(),
        textOrNull(v.get("paidDate")),
        textOrNull(v.get("diagnosisCode")),
        v.get("filename").asString(),
        v.get("rowNumber").asInt(),
        v.get("loadedAt").asString(),
        v.get("isCurrent").asBoolean());
  }

  private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }

  private static String textOrNull(JsonNode n) {
    return n == null || n.isNull() ? null : n.asString();
  }
}
