package com.example.stoploss.upload;

import com.example.stoploss.domain.Dates;
import com.example.stoploss.upload.UploadResult.AlertRaised;
import com.example.stoploss.upload.UploadResult.Counts;
import com.example.stoploss.upload.UploadResult.RowError;
import com.example.stoploss.upload.UploadResult.Summary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Upload history for analysts. */
@Repository
public class UploadQueries {
  private static final String SELECT_UPLOAD = """
      SELECT u.id, u.group_id, u.filename, u.sha256, u.layout, u.status, u.rejection_reason, u.uploaded_by, u.uploaded_at,
             u.row_count, u.applied_count, u.unchanged_count, u.older_ignored_count, u.summary_count, u.error_count,
             COALESCE((SELECT json_agg(json_build_object('id', a.id, 'member_id', a.member_id, 'threshold', a.threshold,
                                                        'spend', a.spend_cents_at_trigger,
                                                        'ded', a.deductible_cents_at_trigger)
                                       ORDER BY a.member_id, a.threshold)
                       FROM alert a WHERE a.upload_id = u.id), '[]'::json) AS alerts
      FROM upload u""";

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper json;

  public UploadQueries(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public UploadResult.Detail getUpload(long id) {
    List<Summary> rows = jdbc.query(SELECT_UPLOAD + " WHERE u.id = :id", Map.of("id", id), (rs, i) -> toSummary(rs));
    if (rows.isEmpty()) return null;
    List<RowError> errors = jdbc.query(
        "SELECT row_number, reason, raw FROM upload_row_error WHERE upload_id = :id ORDER BY row_number, id",
        Map.of("id", id),
        (rs, i) -> new RowError(rs.getInt("row_number"), rs.getString("reason"), rs.getString("raw")));
    return UploadResult.Detail.of(rows.getFirst(), errors);
  }

  /** Newest first, without row errors. */
  public List<Summary> listUploads(int limit) {
    return jdbc.query(SELECT_UPLOAD + " ORDER BY u.uploaded_at DESC, u.id DESC LIMIT :limit", Map.of("limit", limit),
        (rs, i) -> toSummary(rs));
  }

  private Summary toSummary(ResultSet rs) throws SQLException {
    List<AlertRaised> alerts = json.readTree(rs.getString("alerts")).valueStream()
        .map(this::alertRaised)
        .toList();
    return new Summary(
        rs.getLong("id"),
        rs.getString("filename"),
        rs.getString("group_id"),
        rs.getString("layout"),
        rs.getString("status"),
        rs.getString("rejection_reason"),
        rs.getString("uploaded_by"),
        Dates.timestamp(rs.getObject("uploaded_at", OffsetDateTime.class)),
        rs.getString("sha256"),
        new Counts(
            rs.getInt("row_count"),
            rs.getInt("applied_count"),
            rs.getInt("unchanged_count"),
            rs.getInt("older_ignored_count"),
            rs.getInt("summary_count"),
            rs.getInt("error_count")),
        alerts);
  }

  private AlertRaised alertRaised(JsonNode a) {
    return IngestService.alertRaised(a.get("id").asLong(), a.get("member_id").asString(), a.get("threshold").asInt(),
        a.get("spend").asLong(), a.get("ded").asLong());
  }
}
