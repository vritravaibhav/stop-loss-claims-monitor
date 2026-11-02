package com.example.stoploss.groups;

import com.example.stoploss.auth.AppUser;
import com.example.stoploss.domain.Dates;
import com.example.stoploss.domain.Money;
import com.example.stoploss.groups.GroupQueries.GroupRow;
import com.example.stoploss.groups.GroupQueries.PolicyYearRow;
import com.example.stoploss.web.HttpError;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GroupController {
  private static final Pattern POLICY_YEAR_ID = Pattern.compile("[1-9]\\d{0,9}");

  private final GroupQueries queries;
  private final Clock clock;

  public GroupController(GroupQueries queries, Clock clock) {
    this.queries = queries;
    this.clock = clock;
  }

  // JSON shapes (docs/SPEC.md "HTTP API"); component order is key order.
  public record Me(String email, String role, AppUser.Brokerage brokerage) {}

  public record PolicyYearJson(long id, String startDate, String endDate, BigDecimal deductible) {
    static PolicyYearJson of(PolicyYearRow y) {
      return new PolicyYearJson(y.id(), y.startDate(), y.endDate(), Money.dollars(y.deductibleCents()));
    }
  }

  public record GroupJson(String id, String name, AppUser.Brokerage brokerage, int memberCount, int openAlertCount,
      List<PolicyYearJson> policyYears, Long currentPolicyYearId) {}

  public record GroupRef(String id, String name) {}

  public record MemberSpendJson(String memberId, String firstName, String lastName, String relationship,
      BigDecimal spend, BigDecimal deductible, boolean hasLaser, BigDecimal pctUsed, BigDecimal excess, int claimCount,
      List<Integer> alertThresholds) {}

  public record SpendJson(GroupRef group, PolicyYearJson policyYear, List<MemberSpendJson> members) {}

  public record MemberJson(String memberId, String groupId, String firstName, String lastName, String dateOfBirth,
      String relationship) {}

  public record MemberPolicyYearJson(long policyYearId, String startDate, String endDate, BigDecimal deductible,
      boolean hasLaser, BigDecimal spend, BigDecimal pctUsed, BigDecimal excess) {}

  public record VersionJson(int version, String memberId, String status, String serviceDate, String paidDate,
      String diagnosisCode, String filename, int rowNumber, String loadedAt, boolean isCurrent, BigDecimal paidAmount) {}

  public record ClaimJson(String claimId, Long policyYearId, int currentVersion, String status,
      BigDecimal effectiveAmount, String serviceDate, List<VersionJson> versions) {}

  public record MemberDetailJson(MemberJson member, List<MemberPolicyYearJson> policyYears, List<ClaimJson> claims) {}

  @GetMapping("/me")
  public Me me(HttpServletRequest request) {
    AppUser u = AppUser.current(request);
    return new Me(u.email(), u.role(), u.brokerage());
  }

  @GetMapping("/groups")
  public List<GroupJson> groups(HttpServletRequest request) {
    List<GroupRow> groups = queries.visibleGroups(AppUser.current(request), null);
    String today = Dates.today(clock);
    return groups.stream()
        .map(g -> new GroupJson(
            g.id(),
            g.name(),
            g.brokerageId() == null ? null : new AppUser.Brokerage(g.brokerageId(), g.brokerageName()),
            g.memberCount(),
            g.openAlertCount(),
            g.policyYears().stream().map(PolicyYearJson::of).toList(),
            GroupQueries.currentPolicyYearId(g.policyYears(), today)))
        .toList();
  }

  @GetMapping("/groups/{groupId}/spend")
  public SpendJson spend(@PathVariable String groupId, HttpServletRequest request) {
    AppUser user = AppUser.current(request);
    List<GroupRow> found = queries.visibleGroups(user, groupId);
    if (found.isEmpty()) throw new HttpError(404, "Group not found");
    GroupRow group = found.getFirst();

    PolicyYearRow year;
    String[] requested = request.getParameterValues("policyYearId");
    if (requested != null) {
      if (requested.length != 1 || !POLICY_YEAR_ID.matcher(requested[0]).matches()) {
        throw new HttpError(400, "policyYearId must be a positive integer");
      }
      long id = Long.parseLong(requested[0]);
      year = group.policyYears().stream().filter(y -> y.id() == id).findFirst()
          .orElseThrow(() -> new HttpError(404, "Policy year not found for this group"));
    } else {
      Long id = GroupQueries.currentPolicyYearId(group.policyYears(), Dates.today(clock));
      year = group.policyYears().stream().filter(y -> id != null && y.id() == id).findFirst()
          .orElseThrow(() -> new HttpError(404, "This group has no policy years"));
    }

    record Ranked(long pctTenths, MemberSpendJson json) {}
    List<Ranked> ranked = new ArrayList<>();
    for (GroupQueries.MemberSpendRow m : queries.memberSpend(user, group.id(), year.id())) {
      ranked.add(new Ranked(
          Money.pctUsedTenths(m.spendCents(), m.deductibleCents()),
          new MemberSpendJson(
              m.memberId(),
              m.firstName(),
              m.lastName(),
              m.relationship(),
              Money.dollars(m.spendCents()),
              Money.dollars(m.deductibleCents()),
              m.hasLaser(),
              Money.pctUsed(m.spendCents(), m.deductibleCents()),
              Money.dollars(Money.excessCents(m.spendCents(), m.deductibleCents())),
              m.claimCount(),
              m.thresholds())));
    }
    // % used desc, then member id in plain code-unit order (no locale collation).
    ranked.sort(Comparator.comparingLong(Ranked::pctTenths).reversed()
        .thenComparing(r -> r.json().memberId()));

    return new SpendJson(new GroupRef(group.id(), group.name()), PolicyYearJson.of(year),
        ranked.stream().map(Ranked::json).toList());
  }

  @GetMapping("/members/{memberId}")
  public MemberDetailJson member(@PathVariable String memberId, HttpServletRequest request) {
    AppUser user = AppUser.current(request);
    GroupQueries.MemberRow member = queries.member(user, memberId);
    if (member == null) throw new HttpError(404, "Member not found");

    List<MemberPolicyYearJson> years = queries.memberPolicyYears(user, memberId).stream()
        .map(y -> new MemberPolicyYearJson(
            y.id(),
            y.startDate(),
            y.endDate(),
            Money.dollars(y.deductibleCents()),
            y.hasLaser(),
            Money.dollars(y.spendCents()),
            Money.pctUsed(y.spendCents(), y.deductibleCents()),
            Money.dollars(Money.excessCents(y.spendCents(), y.deductibleCents()))))
        .toList();

    List<ClaimJson> claims = queries.memberClaims(user, memberId).stream()
        .map(c -> new ClaimJson(
            c.claimId(),
            c.policyYearId(),
            c.currentVersion(),
            c.status(),
            Money.dollars(c.effectiveAmountCents()),
            c.serviceDate(),
            c.versions().stream()
                .map(v -> new VersionJson(v.version(), v.memberId(), v.status(), v.serviceDate(), v.paidDate(),
                    v.diagnosisCode(), v.filename(), v.rowNumber(), v.loadedAt(), v.isCurrent(),
                    Money.dollars(v.paidAmountCents())))
                .toList()))
        .toList();

    return new MemberDetailJson(
        new MemberJson(member.id(), member.groupId(), member.firstName(), member.lastName(), member.dateOfBirth(),
            member.relationship()),
        years,
        claims);
  }
}
