package com.example.stoploss.domain;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;

/**
 * Alert thresholds (STOP_LOSS_RULES.md "Alerts"). "Reaches" means {@code >=}. Integer maths only: spend reaches t% of
 * the deductible ⇔ spend_cents × 100 ≥ t × deductible_cents.
 */
public final class Alerts {
  private Alerts() {}

  public static final List<Integer> THRESHOLDS = List.of(50, 75, 100);

  public static boolean reachesThreshold(long spendCents, long deductibleCents, int threshold) {
    if (deductibleCents <= 0) throw new IllegalArgumentException("deductible must be positive");
    return BigInteger.valueOf(spendCents).multiply(BigInteger.valueOf(100))
        .compareTo(BigInteger.valueOf(threshold).multiply(BigInteger.valueOf(deductibleCents))) >= 0;
  }

  /** Every threshold the spend has reached (one file can cross several; each becomes its own alert). */
  public static List<Integer> reachedThresholds(long spendCents, long deductibleCents) {
    return THRESHOLDS.stream().filter(t -> reachesThreshold(spendCents, deductibleCents, t)).toList();
  }

  /**
   * Thresholds that should raise a <em>new</em> alert: reached now and never alerted before for this member and policy
   * year. (The DB's unique constraint is the real guard; this keeps the rule testable without a DB.)
   */
  public static List<Integer> newAlertThresholds(long spendCents, long deductibleCents, Collection<Integer> alreadyAlerted) {
    return reachedThresholds(spendCents, deductibleCents).stream().filter(t -> !alreadyAlerted.contains(t)).toList();
  }
}
