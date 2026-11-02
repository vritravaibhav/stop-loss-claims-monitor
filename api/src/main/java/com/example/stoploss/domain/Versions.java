package com.example.stoploss.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Claim-version rules (STOP_LOSS_RULES.md "Claim versions"):
 *
 * <ol start="3">
 *   <li>the newest (highest) version replaces all earlier ones;
 *   <li value="5">a version older than one already loaded changes nothing;
 *   <li>the same version again with the same values changes nothing; with different values it is an error.
 * </ol>
 */
public final class Versions {
  private Versions() {}

  public enum Outcome {
    /** Highest version so far: becomes current. */
    NEW,
    /** Stored for history, current unchanged. */
    OLDER_IGNORED,
    /** Exact duplicate of a stored version. */
    UNCHANGED,
    /** Same version, different values: row error. */
    CONFLICT
  }

  public record Decision(Outcome outcome, List<String> differences) {
    static final Decision NEW = new Decision(Outcome.NEW, List.of());
    static final Decision OLDER_IGNORED = new Decision(Outcome.OLDER_IGNORED, List.of());
    static final Decision UNCHANGED = new Decision(Outcome.UNCHANGED, List.of());
  }

  /** The values that make a version "the same" (rule 6). */
  public interface VersionValues {
    String memberId();

    String serviceDate();

    String paidDate();

    long amountCents();

    String status();

    String diagnosisCode();
  }

  /** A stored version, with where it was first loaded (for the conflict message; may be null). */
  public record StoredVersion(
      int version,
      String memberId,
      String serviceDate,
      String paidDate,
      long amountCents,
      String status,
      String diagnosisCode,
      String filename,
      Integer rowNumber) implements VersionValues {

    public static StoredVersion of(int version, VersionValues v, String filename, Integer rowNumber) {
      return new StoredVersion(version, v.memberId(), v.serviceDate(), v.paidDate(), v.amountCents(), v.status(),
          v.diagnosisCode(), filename, rowNumber);
    }
  }

  /** Everything stored for one claim; {@code currentVersion} is the highest stored version number. */
  public static final class ClaimHistory {
    private int currentVersion;
    private final Map<Integer, StoredVersion> versions = new HashMap<>();

    public int currentVersion() {
      return currentVersion;
    }

    public Map<Integer, StoredVersion> versions() {
      return versions;
    }
  }

  public static List<String> compareValues(VersionValues stored, VersionValues incoming) {
    List<String> diffs = new ArrayList<>();
    if (!Objects.equals(stored.memberId(), incoming.memberId())) {
      diffs.add("member " + stored.memberId() + " → " + incoming.memberId());
    }
    if (!Objects.equals(stored.serviceDate(), incoming.serviceDate())) {
      diffs.add("service date " + stored.serviceDate() + " → " + incoming.serviceDate());
    }
    if (!Objects.equals(stored.paidDate(), incoming.paidDate())) {
      diffs.add("paid date " + show(stored.paidDate()) + " → " + show(incoming.paidDate()));
    }
    if (stored.amountCents() != incoming.amountCents()) {
      diffs.add("paid amount " + Money.formatCents(stored.amountCents()) + " → "
          + Money.formatCents(incoming.amountCents()));
    }
    if (!Objects.equals(stored.status(), incoming.status())) {
      diffs.add("status " + stored.status() + " → " + incoming.status());
    }
    if (!Objects.equals(stored.diagnosisCode(), incoming.diagnosisCode())) {
      diffs.add("diagnosis code " + show(stored.diagnosisCode()) + " → " + show(incoming.diagnosisCode()));
    }
    return diffs;
  }

  private static String show(String v) {
    return v == null ? "(blank)" : v;
  }

  /** Decide what an incoming row does to a claim, given everything already stored for that claim (null: nothing). */
  public static Decision decideVersion(ClaimHistory history, int version, VersionValues incoming) {
    if (history == null) return Decision.NEW;
    StoredVersion stored = history.versions.get(version);
    if (stored != null) {
      List<String> differences = compareValues(stored, incoming);
      return differences.isEmpty() ? Decision.UNCHANGED : new Decision(Outcome.CONFLICT, List.copyOf(differences));
    }
    if (version > history.currentVersion) return Decision.NEW;
    return Decision.OLDER_IGNORED;
  }

  /** Record a newly stored version into the in-memory history (used while processing a file in order). */
  public static void recordVersion(Map<String, ClaimHistory> histories, String claimId, StoredVersion version) {
    ClaimHistory h = histories.get(claimId);
    if (h == null) {
      h = new ClaimHistory();
      h.currentVersion = version.version();
      h.versions.put(version.version(), version);
      histories.put(claimId, h);
      return;
    }
    h.versions.put(version.version(), version);
    if (version.version() > h.currentVersion) h.currentVersion = version.version();
  }

  public static String conflictReason(String claimId, int version, StoredVersion stored, List<String> differences) {
    String where = stored.filename() != null && stored.rowNumber() != null
        ? " (first loaded from " + stored.filename() + " row " + stored.rowNumber() + ")"
        : "";
    return "Conflict: claim " + claimId + " version " + version + " was already loaded with different values" + where
        + ": " + String.join(", ", differences) + ". The first-loaded values were kept";
  }
}
