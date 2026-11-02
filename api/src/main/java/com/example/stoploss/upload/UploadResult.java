package com.example.stoploss.upload;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of one claim-file upload (docs/SPEC.md "UploadResult"). Field order is the JSON key order.
 *
 * @param status {@code processed} or {@code rejected}
 */
public record UploadResult(
    long id,
    String filename,
    String groupId,
    String layout,
    String status,
    String rejectionReason,
    String uploadedBy,
    String uploadedAt,
    String sha256,
    Counts counts,
    List<RowError> errors,
    List<AlertRaised> alertsRaised) {

  public static final String PROCESSED = "processed";
  public static final String REJECTED = "rejected";

  public record Counts(int rows, int applied, int unchanged, int olderIgnored, int summary, int errors) {
    public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0);
  }

  public record RowError(int rowNumber, String reason, String raw) {}

  public record AlertRaised(long id, String memberId, int threshold, BigDecimal spend, BigDecimal deductible,
      BigDecimal pctUsed) {}

  /** {@code GET /api/uploads}: the same fields without the row errors. */
  public record Summary(
      long id,
      String filename,
      String groupId,
      String layout,
      String status,
      String rejectionReason,
      String uploadedBy,
      String uploadedAt,
      String sha256,
      Counts counts,
      List<AlertRaised> alertsRaised) {}

  /** {@code GET /api/uploads/:id}: the summary followed by the row errors. */
  public record Detail(
      long id,
      String filename,
      String groupId,
      String layout,
      String status,
      String rejectionReason,
      String uploadedBy,
      String uploadedAt,
      String sha256,
      Counts counts,
      List<AlertRaised> alertsRaised,
      List<RowError> errors) {

    static Detail of(Summary s, List<RowError> errors) {
      return new Detail(s.id(), s.filename(), s.groupId(), s.layout(), s.status(), s.rejectionReason(), s.uploadedBy(),
          s.uploadedAt(), s.sha256(), s.counts(), s.alertsRaised(), errors);
    }
  }
}
