package com.example.stoploss.auth;

import java.sql.Types;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Broker scoping is done in SQL. Every group-scoped query starts with this CTE and joins through it, with
 * {@code :isAnalyst} = caller is an analyst and {@code :brokerageId} = caller's brokerage id. A broker sees only groups
 * of their brokerage; groups without a brokerage are analyst-only (NULL = :brokerageId is never true).
 */
public final class Scope {
  private Scope() {}

  public static final String VISIBLE_GROUPS = """
      visible_group AS (
        SELECT g.id, g.name, g.brokerage_id FROM employer_group g
        WHERE CAST(:isAnalyst AS boolean) OR g.brokerage_id = CAST(:brokerageId AS text)
      )""";

  /** The scope parameters for {@link #VISIBLE_GROUPS}; add the query's own parameters to the returned source. */
  public static MapSqlParameterSource params(AppUser user) {
    boolean analyst = user.isAnalyst();
    String brokerageId = AppUser.BROKER.equals(user.role()) && user.brokerage() != null ? user.brokerage().id() : null;
    return new MapSqlParameterSource()
        .addValue("isAnalyst", analyst, Types.BOOLEAN)
        .addValue("brokerageId", brokerageId, Types.VARCHAR);
  }
}
