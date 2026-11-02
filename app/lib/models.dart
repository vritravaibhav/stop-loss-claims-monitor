// Typed models for every response in docs/SPEC.md "HTTP API".
//
// Money arrives as a JSON number of dollars with at most 2 dp. It is converted
// once, here, to integer cents (`...Cents` fields) so the rest of the app never
// does arithmetic on floating-point dollars.

typedef Json = Map<String, dynamic>;

enum Role { analyst, broker }

enum AlertStatus { open, acknowledged }

enum ClaimStatus { paid, reversed }

enum UploadStatus { processed, rejected }

class Brokerage {
  const Brokerage({required this.id, required this.name});

  factory Brokerage.fromJson(Json json) =>
      Brokerage(id: _id(json['id']), name: json['name'] as String);

  final String id;
  final String name;
}

/// `GET /api/me`.
class Profile {
  const Profile({required this.email, required this.role, this.brokerage});

  factory Profile.fromJson(Json json) => Profile(
    email: json['email'] as String,
    role: _enum(Role.values, json['role']),
    brokerage: _opt(json['brokerage'], Brokerage.fromJson),
  );

  final String email;
  final Role role;
  final Brokerage? brokerage;

  bool get isAnalyst => role == Role.analyst;
}

class PolicyYear {
  const PolicyYear({
    required this.id,
    required this.startDate,
    required this.endDate,
    required this.deductibleCents,
  });

  factory PolicyYear.fromJson(Json json) => PolicyYear(
    id: _id(json['id']),
    startDate: _date(json['startDate']),
    endDate: _date(json['endDate']),
    deductibleCents: _cents(json['deductible']),
  );

  final String id;
  final DateTime startDate;
  final DateTime endDate;
  final int deductibleCents;
}

/// An element of `GET /api/groups`.
class Group {
  const Group({
    required this.id,
    required this.name,
    required this.brokerage,
    required this.memberCount,
    required this.openAlertCount,
    required this.policyYears,
    required this.currentPolicyYearId,
  });

  factory Group.fromJson(Json json) => Group(
    id: _id(json['id']),
    name: json['name'] as String,
    brokerage: _opt(json['brokerage'], Brokerage.fromJson),
    memberCount: json['memberCount'] as int,
    openAlertCount: json['openAlertCount'] as int,
    policyYears: _list(json['policyYears'], PolicyYear.fromJson),
    currentPolicyYearId: _idOrNull(json['currentPolicyYearId']),
  );

  final String id;
  final String name;
  final Brokerage? brokerage;
  final int memberCount;
  final int openAlertCount;
  final List<PolicyYear> policyYears;

  /// The policy year containing today, else the latest one; null when the
  /// group has no policy years at all.
  final String? currentPolicyYearId;

  PolicyYear? get currentPolicyYear {
    for (final py in policyYears) {
      if (py.id == currentPolicyYearId) return py;
    }
    return null;
  }
}

class GroupRef {
  const GroupRef({required this.id, required this.name});

  factory GroupRef.fromJson(Json json) =>
      GroupRef(id: _id(json['id']), name: json['name'] as String);

  final String id;
  final String name;
}

class MemberSpend {
  const MemberSpend({
    required this.memberId,
    required this.firstName,
    required this.lastName,
    required this.relationship,
    required this.spendCents,
    required this.deductibleCents,
    required this.hasLaser,
    required this.pctUsed,
    required this.excessCents,
    required this.claimCount,
    required this.alertThresholds,
  });

  factory MemberSpend.fromJson(Json json) => MemberSpend(
    memberId: _id(json['memberId']),
    firstName: json['firstName'] as String,
    lastName: json['lastName'] as String,
    relationship: json['relationship'] as String,
    spendCents: _cents(json['spend']),
    deductibleCents: _cents(json['deductible']),
    hasLaser: json['hasLaser'] as bool,
    pctUsed: _pct(json['pctUsed']),
    excessCents: _cents(json['excess']),
    claimCount: json['claimCount'] as int,
    alertThresholds: [
      for (final t in json['alertThresholds'] as List) (t as num).toInt(),
    ],
  );

  final String memberId;
  final String firstName;
  final String lastName;
  final String relationship;
  final int spendCents;
  final int deductibleCents;
  final bool hasLaser;
  final double pctUsed;
  final int excessCents;
  final int claimCount;

  /// Thresholds (50/75/100) that have raised an alert for this member and
  /// policy year.
  final List<int> alertThresholds;

  String get fullName => '$firstName $lastName';
}

/// `GET /api/groups/:groupId/spend`.
class GroupSpend {
  const GroupSpend({
    required this.group,
    required this.policyYear,
    required this.members,
  });

  factory GroupSpend.fromJson(Json json) => GroupSpend(
    group: GroupRef.fromJson(json['group'] as Json),
    policyYear: PolicyYear.fromJson(json['policyYear'] as Json),
    members: _list(json['members'], MemberSpend.fromJson),
  );

  final GroupRef group;
  final PolicyYear policyYear;

  /// Sorted by % used desc, then member id (server order).
  final List<MemberSpend> members;
}

class MemberInfo {
  const MemberInfo({
    required this.memberId,
    required this.groupId,
    required this.firstName,
    required this.lastName,
    required this.dateOfBirth,
    required this.relationship,
  });

  factory MemberInfo.fromJson(Json json) => MemberInfo(
    memberId: _id(json['memberId']),
    groupId: _id(json['groupId']),
    firstName: json['firstName'] as String,
    lastName: json['lastName'] as String,
    dateOfBirth: _dateOrNull(json['dateOfBirth']),
    relationship: json['relationship'] as String,
  );

  final String memberId;
  final String groupId;
  final String firstName;
  final String lastName;
  final DateTime? dateOfBirth;
  final String relationship;

  String get fullName => '$firstName $lastName';
}

class MemberPolicyYear {
  const MemberPolicyYear({
    required this.policyYearId,
    required this.startDate,
    required this.endDate,
    required this.deductibleCents,
    required this.hasLaser,
    required this.spendCents,
    required this.pctUsed,
    required this.excessCents,
  });

  factory MemberPolicyYear.fromJson(Json json) => MemberPolicyYear(
    policyYearId: _id(json['policyYearId']),
    startDate: _date(json['startDate']),
    endDate: _date(json['endDate']),
    deductibleCents: _cents(json['deductible']),
    hasLaser: json['hasLaser'] as bool,
    spendCents: _cents(json['spend']),
    pctUsed: _pct(json['pctUsed']),
    excessCents: _cents(json['excess']),
  );

  final String policyYearId;
  final DateTime startDate;
  final DateTime endDate;
  final int deductibleCents;
  final bool hasLaser;
  final int spendCents;
  final double pctUsed;
  final int excessCents;
}

class ClaimVersion {
  const ClaimVersion({
    required this.version,
    required this.status,
    required this.paidAmountCents,
    required this.serviceDate,
    required this.paidDate,
    required this.diagnosisCode,
    required this.filename,
    required this.rowNumber,
    required this.loadedAt,
    required this.isCurrent,
  });

  factory ClaimVersion.fromJson(Json json) => ClaimVersion(
    version: json['version'] as int,
    status: _enum(ClaimStatus.values, json['status']),
    paidAmountCents: _cents(json['paidAmount']),
    serviceDate: _date(json['serviceDate']),
    paidDate: _dateOrNull(json['paidDate']),
    diagnosisCode: json['diagnosisCode'] as String?,
    filename: json['filename'] as String,
    rowNumber: json['rowNumber'] as int,
    loadedAt: _timestamp(json['loadedAt']),
    isCurrent: json['isCurrent'] as bool,
  );

  final int version;
  final ClaimStatus status;
  final int paidAmountCents;
  final DateTime serviceDate;
  final DateTime? paidDate;
  final String? diagnosisCode;
  final String filename;
  final int rowNumber;
  final DateTime loadedAt;
  final bool isCurrent;
}

class Claim {
  const Claim({
    required this.claimId,
    required this.policyYearId,
    required this.currentVersion,
    required this.status,
    required this.effectiveAmountCents,
    required this.serviceDate,
    required this.versions,
  });

  factory Claim.fromJson(Json json) => Claim(
    claimId: _id(json['claimId']),
    policyYearId: _idOrNull(json['policyYearId']),
    currentVersion: json['currentVersion'] as int,
    status: _enum(ClaimStatus.values, json['status']),
    effectiveAmountCents: _cents(json['effectiveAmount']),
    serviceDate: _date(json['serviceDate']),
    versions: _list(json['versions'], ClaimVersion.fromJson),
  );

  final String claimId;

  /// Null when the current version's service date is in no policy year.
  final String? policyYearId;
  final int currentVersion;
  final ClaimStatus status;

  /// What the claim counts toward spend: 0 when the current version is
  /// reversed.
  final int effectiveAmountCents;
  final DateTime serviceDate;
  final List<ClaimVersion> versions;
}

/// `GET /api/members/:memberId`.
class MemberDetail {
  const MemberDetail({
    required this.member,
    required this.policyYears,
    required this.claims,
  });

  factory MemberDetail.fromJson(Json json) => MemberDetail(
    member: MemberInfo.fromJson(json['member'] as Json),
    policyYears: _list(json['policyYears'], MemberPolicyYear.fromJson),
    claims: _list(json['claims'], Claim.fromJson),
  );

  final MemberInfo member;
  final List<MemberPolicyYear> policyYears;
  final List<Claim> claims;
}

/// An element of `GET /api/alerts`, and the body of the acknowledge response.
class Alert {
  const Alert({
    required this.id,
    required this.groupId,
    required this.groupName,
    required this.memberId,
    required this.memberName,
    required this.policyYearId,
    required this.policyYearStart,
    required this.policyYearEnd,
    required this.threshold,
    required this.spendAtTriggerCents,
    required this.deductibleAtTriggerCents,
    required this.currentSpendCents,
    required this.currentPctUsed,
    required this.status,
    required this.createdAt,
    required this.uploadFilename,
    required this.acknowledgedBy,
    required this.acknowledgedAt,
    required this.note,
  });

  factory Alert.fromJson(Json json) => Alert(
    id: _id(json['id']),
    groupId: _id(json['groupId']),
    groupName: json['groupName'] as String,
    memberId: _id(json['memberId']),
    memberName: json['memberName'] as String,
    policyYearId: _id(json['policyYearId']),
    policyYearStart: _date(json['policyYearStart']),
    policyYearEnd: _date(json['policyYearEnd']),
    threshold: (json['threshold'] as num).toInt(),
    spendAtTriggerCents: _cents(json['spendAtTrigger']),
    deductibleAtTriggerCents: _cents(json['deductibleAtTrigger']),
    currentSpendCents: _cents(json['currentSpend']),
    currentPctUsed: _pct(json['currentPctUsed']),
    status: _enum(AlertStatus.values, json['status']),
    createdAt: _timestamp(json['createdAt']),
    uploadFilename: json['uploadFilename'] as String?,
    acknowledgedBy: json['acknowledgedBy'] as String?,
    acknowledgedAt: _timestampOrNull(json['acknowledgedAt']),
    note: json['note'] as String?,
  );

  final String id;
  final String groupId;
  final String groupName;
  final String memberId;
  final String memberName;
  final String policyYearId;
  final DateTime policyYearStart;
  final DateTime policyYearEnd;
  final int threshold;
  final int spendAtTriggerCents;
  final int deductibleAtTriggerCents;
  final int currentSpendCents;
  final double currentPctUsed;
  final AlertStatus status;
  final DateTime createdAt;
  final String? uploadFilename;
  final String? acknowledgedBy;
  final DateTime? acknowledgedAt;
  final String? note;

  bool get isOpen => status == AlertStatus.open;
}

class UploadCounts {
  const UploadCounts({
    required this.rows,
    required this.applied,
    required this.unchanged,
    required this.olderIgnored,
    required this.summary,
    required this.errors,
  });

  factory UploadCounts.fromJson(Json json) => UploadCounts(
    rows: json['rows'] as int,
    applied: json['applied'] as int,
    unchanged: json['unchanged'] as int,
    olderIgnored: json['olderIgnored'] as int,
    summary: json['summary'] as int,
    errors: json['errors'] as int,
  );

  final int rows;
  final int applied;
  final int unchanged;
  final int olderIgnored;
  final int summary;
  final int errors;
}

class RowError {
  const RowError({
    required this.rowNumber,
    required this.reason,
    required this.raw,
  });

  factory RowError.fromJson(Json json) => RowError(
    rowNumber: json['rowNumber'] as int,
    reason: json['reason'] as String,
    raw: (json['raw'] as String?) ?? '',
  );

  /// Physical 1-based line number in the file, as seen in a text editor.
  final int rowNumber;
  final String reason;
  final String raw;
}

class RaisedAlert {
  const RaisedAlert({
    required this.id,
    required this.memberId,
    required this.threshold,
    required this.spendCents,
    required this.deductibleCents,
    required this.pctUsed,
  });

  factory RaisedAlert.fromJson(Json json) => RaisedAlert(
    id: _id(json['id']),
    memberId: _id(json['memberId']),
    threshold: (json['threshold'] as num).toInt(),
    spendCents: _cents(json['spend']),
    deductibleCents: _cents(json['deductible']),
    pctUsed: _pct(json['pctUsed']),
  );

  final String id;
  final String memberId;
  final int threshold;
  final int spendCents;
  final int deductibleCents;
  final double pctUsed;
}

/// `POST /api/uploads`, `GET /api/uploads/:id` and the elements of
/// `GET /api/uploads` (the list omits row errors, so [errors] is empty there).
class UploadResult {
  const UploadResult({
    required this.id,
    required this.filename,
    required this.groupId,
    required this.layout,
    required this.status,
    required this.rejectionReason,
    required this.uploadedBy,
    required this.uploadedAt,
    required this.sha256,
    required this.counts,
    required this.errors,
    required this.alertsRaised,
  });

  factory UploadResult.fromJson(Json json) => UploadResult(
    id: _id(json['id']),
    filename: json['filename'] as String,
    // A rejected file may have no known group or layout (that can be the very
    // reason it was rejected), so these two are tolerated as null.
    groupId: _idOrNull(json['groupId']),
    layout: json['layout'] as String?,
    status: _enum(UploadStatus.values, json['status']),
    rejectionReason: json['rejectionReason'] as String?,
    uploadedBy: json['uploadedBy'] as String,
    uploadedAt: _timestamp(json['uploadedAt']),
    sha256: json['sha256'] as String?,
    counts: UploadCounts.fromJson(json['counts'] as Json),
    errors: json['errors'] == null
        ? const []
        : _list(json['errors'], RowError.fromJson),
    alertsRaised: json['alertsRaised'] == null
        ? const []
        : _list(json['alertsRaised'], RaisedAlert.fromJson),
  );

  final String id;
  final String filename;
  final String? groupId;
  final String? layout;
  final UploadStatus status;
  final String? rejectionReason;
  final String uploadedBy;
  final DateTime uploadedAt;
  final String? sha256;
  final UploadCounts counts;
  final List<RowError> errors;
  final List<RaisedAlert> alertsRaised;

  bool get isRejected => status == UploadStatus.rejected;
}

// ---- parsing helpers -------------------------------------------------------

/// Converts a JSON number of dollars (≤ 2 dp) to integer cents.
int _cents(Object? value) => ((value as num) * 100).round();

double _pct(Object? value) => (value as num).toDouble();

/// Ids are opaque to the client; the API may send serial ints or strings.
String _id(Object? value) => switch (value) {
  final String s => s,
  final int i => '$i',
  _ => throw FormatException('Expected an id, got $value'),
};

String? _idOrNull(Object? value) => value == null ? null : _id(value);

/// `YYYY-MM-DD` as a local calendar date (no time-zone shift on display).
DateTime _date(Object? value) {
  final parts = (value as String).split('-');
  if (parts.length != 3) throw FormatException('Expected YYYY-MM-DD: $value');
  return DateTime(int.parse(parts[0]), int.parse(parts[1]), int.parse(parts[2]));
}

DateTime? _dateOrNull(Object? value) => value == null ? null : _date(value);

DateTime _timestamp(Object? value) => DateTime.parse(value as String).toLocal();

DateTime? _timestampOrNull(Object? value) =>
    value == null ? null : _timestamp(value);

T _enum<T extends Enum>(List<T> values, Object? name) => values.firstWhere(
  (v) => v.name == name,
  orElse: () => throw FormatException('Unexpected value "$name"'),
);

T? _opt<T>(Object? value, T Function(Json) fromJson) =>
    value == null ? null : fromJson(value as Json);

List<T> _list<T>(Object? value, T Function(Json) fromJson) => [
  for (final item in value as List) fromJson(item as Json),
];
