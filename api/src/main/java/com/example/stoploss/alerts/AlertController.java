package com.example.stoploss.alerts;

import static com.example.stoploss.auth.Scope.VISIBLE_GROUPS;

import com.example.stoploss.auth.AppUser;
import com.example.stoploss.auth.Scope;
import com.example.stoploss.domain.Dates;
import com.example.stoploss.domain.Js;
import com.example.stoploss.domain.Money;
import com.example.stoploss.web.HttpError;
import com.example.stoploss.web.JsonBody;
import com.example.stoploss.web.Params;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class AlertController {
  public static final int MAX_NOTE_LENGTH = 2000;
  private static final Set<String> STATUSES = Set.of("open", "acknowledged", "all");

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonBody jsonBody;

  public AlertController(NamedParameterJdbcTemplate jdbc, JsonBody jsonBody) {
    this.jdbc = jdbc;
    this.jsonBody = jsonBody;
  }

  /** docs/SPEC.md alert shape; component order is key order. */
  public record AlertJson(
      long id,
      String groupId,
      String groupName,
      String memberId,
      String memberName,
      long policyYearId,
      String policyYearStart,
      String policyYearEnd,
      int threshold,
      BigDecimal spendAtTrigger,
      BigDecimal deductibleAtTrigger,
      BigDecimal currentSpend,
      BigDecimal currentPctUsed,
      String status,
      String createdAt,
      String uploadFilename,
      String acknowledgedBy,
      String acknowledgedAt,
      String note) {}

  /** Alerts the caller can see, newest first (created_at desc, then threshold desc). */
  private List<AlertJson> queryAlerts(AppUser user, String status, String groupId, Long id) {
    return jdbc.query(
        "WITH " + VISIBLE_GROUPS + """

        SELECT a.id, a.group_id, g.name AS group_name, a.member_id, m.first_name || ' ' || m.last_name AS member_name,
               a.policy_year_id, py.start_date, py.end_date, a.threshold, a.spend_cents_at_trigger,
               a.deductible_cents_at_trigger,
               COALESCE((SELECT sum(cc.effective_amount_cents) FROM claim_current cc
                         WHERE cc.member_id = a.member_id AND cc.policy_year_id = a.policy_year_id), 0)::bigint
                 AS current_spend_cents,
               COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS current_deductible_cents,
               a.status, a.created_at, u.filename AS upload_filename, a.acknowledged_by, a.acknowledged_at, a.ack_note
        FROM alert a
        JOIN visible_group g ON g.id = a.group_id
        JOIN member m ON m.id = a.member_id
        JOIN policy_year py ON py.id = a.policy_year_id
        LEFT JOIN laser l ON l.member_id = a.member_id AND l.policy_year_id = a.policy_year_id
        LEFT JOIN upload u ON u.id = a.upload_id
        WHERE (CAST(:status AS text) = 'all' OR a.status = :status)
          AND (CAST(:groupId AS text) IS NULL OR a.group_id = :groupId)
          AND (CAST(:id AS bigint) IS NULL OR a.id = :id)
        ORDER BY a.created_at DESC, a.threshold DESC, a.id DESC""",
        Scope.params(user)
            .addValue("status", status, Types.VARCHAR)
            .addValue("groupId", groupId, Types.VARCHAR)
            .addValue("id", id, Types.BIGINT),
        (rs, i) -> {
          long spendAtTrigger = rs.getLong("spend_cents_at_trigger");
          long deductibleAtTrigger = rs.getLong("deductible_cents_at_trigger");
          long currentSpend = rs.getLong("current_spend_cents");
          long currentDeductible = rs.getLong("current_deductible_cents");
          return new AlertJson(
              rs.getLong("id"),
              rs.getString("group_id"),
              rs.getString("group_name"),
              rs.getString("member_id"),
              rs.getString("member_name"),
              rs.getLong("policy_year_id"),
              rs.getString("start_date"),
              rs.getString("end_date"),
              rs.getInt("threshold"),
              Money.dollars(spendAtTrigger),
              Money.dollars(deductibleAtTrigger),
              Money.dollars(currentSpend),
              Money.pctUsed(currentSpend, currentDeductible),
              rs.getString("status"),
              Dates.timestamp(rs.getObject("created_at", OffsetDateTime.class)),
              rs.getString("upload_filename"),
              rs.getString("acknowledged_by"),
              Dates.timestamp(rs.getObject("acknowledged_at", OffsetDateTime.class)),
              rs.getString("ack_note"));
        });
  }

  @GetMapping("/alerts")
  public List<AlertJson> alerts(HttpServletRequest request) {
    AppUser user = AppUser.current(request);
    String status = Params.single(request, "status");
    if (status == null) status = request.getParameterValues("status") == null ? "open" : "";
    if (!STATUSES.contains(status)) throw new HttpError(400, "status must be one of open, acknowledged, all");
    String[] groupIds = request.getParameterValues("groupId");
    if (groupIds != null && groupIds.length != 1) throw new HttpError(400, "groupId must be a string");
    String groupId = groupIds == null || groupIds[0].isEmpty() ? null : groupIds[0];
    if (groupId != null) {
      // Same rule as the group endpoints: a group you cannot see does not exist.
      List<Integer> g = jdbc.queryForList("WITH " + VISIBLE_GROUPS + " SELECT 1 FROM visible_group WHERE id = :groupId",
          Scope.params(user).addValue("groupId", groupId, Types.VARCHAR), Integer.class);
      if (g.isEmpty()) throw new HttpError(404, "Group not found");
    }
    return queryAlerts(user, status, groupId, null);
  }

  @PostMapping("/alerts/{id}/acknowledge")
  public AlertJson acknowledge(@PathVariable("id") String rawId, HttpServletRequest request) throws IOException {
    AppUser user = AppUser.current(request).requireAnalyst();
    JsonNode body = jsonBody.read(request);
    Long id = Params.parseId(rawId);
    if (id == null) throw new HttpError(404, "Alert not found");
    JsonNode noteNode = body == null || !body.isObject() ? null : body.get("note");
    String note = noteNode != null && noteNode.isString() ? Js.trim(noteNode.asString()) : "";
    if (note.isEmpty()) throw new HttpError(400, "A note is required to acknowledge an alert");
    if (note.length() > MAX_NOTE_LENGTH) {
      throw new HttpError(400, "The note must be at most " + MAX_NOTE_LENGTH + " characters");
    }

    // Atomic open → acknowledged; a concurrent second acknowledge updates nothing and gets 409.
    int updated = jdbc.update(
        "WITH " + VISIBLE_GROUPS + """

        UPDATE alert a SET status = 'acknowledged', acknowledged_by = :email, acknowledged_at = now(), ack_note = :note
        WHERE a.id = :id AND a.status = 'open' AND a.group_id IN (SELECT id FROM visible_group)""",
        Scope.params(user)
            .addValue("id", id, Types.BIGINT)
            .addValue("email", user.email(), Types.VARCHAR)
            .addValue("note", note, Types.VARCHAR));
    List<AlertJson> found = queryAlerts(user, "all", null, id);
    if (found.isEmpty()) throw new HttpError(404, "Alert not found");
    AlertJson alert = found.getFirst();
    if (updated == 0) throw new HttpError(409, "Alert is already " + alert.status());
    return alert;
  }
}
