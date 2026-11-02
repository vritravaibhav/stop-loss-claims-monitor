import 'package:flutter_test/flutter_test.dart';
import 'package:stoploss_monitor/models.dart';

import 'fixtures.dart';

void main() {
  group('Profile', () {
    test('analyst without brokerage', () {
      final me = Profile.fromJson(fixtureObject('me_analyst'));
      expect(me.email, 'analyst@example.test');
      expect(me.role, Role.analyst);
      expect(me.isAnalyst, isTrue);
      expect(me.brokerage, isNull);
    });

    test('broker with brokerage, numeric id kept as an opaque string', () {
      final me = Profile.fromJson(fixtureObject('me_broker'));
      expect(me.role, Role.broker);
      expect(me.isAnalyst, isFalse);
      expect(me.brokerage!.id, '1');
      expect(me.brokerage!.name, 'Brokerage A');
    });

    test('unknown role is rejected rather than defaulting', () {
      expect(
        () => Profile.fromJson({'email': 'x@y', 'role': 'admin'}),
        throwsFormatException,
      );
    });
  });

  test('Group list', () {
    final groups = fixtureList('groups').map(Group.fromJson).toList();
    expect(groups.map((g) => g.id), ['GA', 'GB', 'GC']);

    final ga = groups[0];
    expect(ga.name, 'Group A');
    expect(ga.brokerage!.name, 'Brokerage A');
    expect(ga.memberCount, 300);
    expect(ga.openAlertCount, 4);
    expect(ga.currentPolicyYearId, '1');
    expect(ga.currentPolicyYear!.deductibleCents, 5000000);
    expect(ga.currentPolicyYear!.startDate, DateTime(2026, 1, 1));
    expect(ga.currentPolicyYear!.endDate, DateTime(2026, 12, 31));

    final gb = groups[1];
    expect(gb.policyYears, hasLength(2));
    expect(gb.currentPolicyYear!.id, '3');
    expect(gb.currentPolicyYear!.deductibleCents, 8000050);

    final gc = groups[2];
    expect(gc.brokerage, isNull);
    expect(gc.policyYears, isEmpty);
    expect(gc.currentPolicyYearId, isNull);
    expect(gc.currentPolicyYear, isNull);
  });

  test('GroupSpend', () {
    final spend = GroupSpend.fromJson(fixtureObject('group_spend'));
    expect(spend.group.id, 'GA');
    expect(spend.policyYear.id, '1');
    expect(spend.policyYear.deductibleCents, 5000000);
    expect(spend.members.map((m) => m.memberId), ['A-0077', 'A-0007', 'A-0001']);

    final top = spend.members.first;
    expect(top.fullName, 'Priya Shah');
    expect(top.relationship, 'Employee');
    expect(top.spendCents, 6123456);
    expect(top.deductibleCents, 5000000);
    expect(top.excessCents, 1123456);
    expect(top.pctUsed, 122.5);
    expect(top.claimCount, 9);
    expect(top.alertThresholds, [50, 75, 100]);
    expect(top.hasLaser, isFalse);

    final lasered = spend.members[1];
    expect(lasered.hasLaser, isTrue);
    expect(lasered.deductibleCents, 12000000);
    expect(lasered.pctUsed, 50.0);

    expect(spend.members.last.alertThresholds, isEmpty);
    expect(spend.members.last.spendCents, 0);
  });

  test('MemberDetail with versions and a claim outside any policy year', () {
    final detail = MemberDetail.fromJson(fixtureObject('member'));
    expect(detail.member.fullName, 'Priya Shah');
    expect(detail.member.groupId, 'GA');
    expect(detail.member.dateOfBirth, DateTime(1980, 2, 29));

    final py = detail.policyYears.single;
    expect(py.policyYearId, '1');
    expect(py.spendCents, 6123456);
    expect(py.excessCents, 1123456);
    expect(py.hasLaser, isFalse);

    final claim = detail.claims.first;
    expect(claim.claimId, '100159');
    expect(claim.policyYearId, '1');
    expect(claim.currentVersion, 2);
    expect(claim.status, ClaimStatus.paid);
    expect(claim.effectiveAmountCents, 1445500);
    expect(claim.versions, hasLength(3));
    expect(claim.versions.where((v) => v.isCurrent).single.version, 2);

    final reversed = claim.versions[1];
    expect(reversed.status, ClaimStatus.reversed);
    expect(reversed.filename, 'GA_2026-02.csv');
    expect(reversed.rowNumber, 40);
    expect(reversed.loadedAt.isUtc, isFalse, reason: 'shown in local time');
    expect(
      reversed.loadedAt.toUtc(),
      DateTime.utc(2026, 11, 2, 9, 14),
    );
    expect(claim.versions.last.diagnosisCode, isNull);

    final outside = detail.claims.last;
    expect(outside.policyYearId, isNull);
    expect(outside.effectiveAmountCents, 79068);
    expect(outside.versions.single.paidDate, isNull);
  });

  test('Alert list, open and acknowledged', () {
    final alerts = fixtureList('alerts').map(Alert.fromJson).toList();
    final open = alerts.first;
    expect(open.id, '12');
    expect(open.isOpen, isTrue);
    expect(open.threshold, 100);
    expect(open.spendAtTriggerCents, 5201010);
    expect(open.deductibleAtTriggerCents, 5000000);
    expect(open.currentSpendCents, 6123456);
    expect(open.currentPctUsed, 122.5);
    expect(open.uploadFilename, 'GA_2026-05.csv');
    expect(open.policyYearStart, DateTime(2026, 1, 1));
    expect(open.createdAt.toUtc(), DateTime.utc(2026, 11, 2, 9, 15));
    expect(open.acknowledgedBy, isNull);
    expect(open.acknowledgedAt, isNull);
    expect(open.note, isNull);

    final acked = alerts.last;
    expect(acked.status, AlertStatus.acknowledged);
    expect(acked.acknowledgedBy, 'analyst@example.test');
    expect(acked.acknowledgedAt!.toUtc(), DateTime.utc(2026, 11, 2, 8));
    expect(acked.note, 'Case manager assigned');
  });

  test('UploadResult with row errors and alerts raised', () {
    final result = UploadResult.fromJson(fixtureObject('upload_result'));
    expect(result.id, '5');
    expect(result.filename, 'GA_2026-04_resend.csv');
    expect(result.groupId, 'GA');
    expect(result.layout, 'tpa_one');
    expect(result.status, UploadStatus.processed);
    expect(result.isRejected, isFalse);
    expect(result.rejectionReason, isNull);
    expect(result.uploadedBy, 'analyst@example.test');
    expect(result.sha256, hasLength(64));

    final c = result.counts;
    expect(
      [c.rows, c.applied, c.unchanged, c.olderIgnored, c.summary, c.errors],
      [42, 37, 1, 1, 1, 2],
    );

    expect(result.errors.map((e) => e.rowNumber), [7, 19]);
    expect(result.errors.first.reason, 'Unknown member B-0999');
    expect(result.errors.first.raw, startsWith('100301,0,B-0999'));

    expect(result.alertsRaised.map((a) => a.threshold), [75, 100]);
    final hundred = result.alertsRaised.last;
    expect(hundred.id, '13');
    expect(hundred.memberId, 'A-0077');
    expect(hundred.spendCents, 5201010);
    expect(hundred.deductibleCents, 5000000);
    expect(hundred.pctUsed, 104.0);
  });

  test('rejected UploadResult tolerates a missing group and layout', () {
    final result = UploadResult.fromJson(fixtureObject('upload_rejected'));
    expect(result.isRejected, isTrue);
    expect(result.rejectionReason, 'Unknown group GZ');
    expect(result.groupId, isNull);
    expect(result.layout, isNull);
    expect(result.errors, isEmpty);
  });

  test('upload history list omits row errors', () {
    final list = fixtureList('uploads').map(UploadResult.fromJson).toList();
    expect(list.map((u) => u.id), ['6', '5']);
    expect(list.every((u) => u.errors.isEmpty), isTrue);
    expect(list.last.counts.errors, 2);
  });
}
