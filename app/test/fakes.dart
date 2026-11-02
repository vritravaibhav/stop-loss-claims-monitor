import 'dart:async';
import 'dart:typed_data';

import 'package:stoploss_monitor/models.dart';
import 'package:stoploss_monitor/services/api_client.dart';
import 'package:stoploss_monitor/services/auth_service.dart';

import 'fixtures.dart';

Profile analystProfile() => Profile.fromJson(fixtureObject('me_analyst'));

Profile brokerProfile() => Profile.fromJson(fixtureObject('me_broker'));

/// A processed result (from the fixture) for [filename].
UploadResult processedResult(String filename) => UploadResult.fromJson({
  ...fixtureObject('upload_result'),
  'filename': filename,
});

/// A rejected result (from the fixture) for [filename].
UploadResult rejectedResult(
  String filename, {
  String reason = 'Unknown group',
}) => UploadResult.fromJson({
  ...fixtureObject('upload_rejected'),
  'filename': filename,
  'rejectionReason': reason,
});

/// In-memory [ApiClient] serving the JSON fixtures. Override the `on...`
/// hooks to control a single call; every call is recorded in [calls].
class FakeApi implements ApiClient {
  FakeApi({Profile? profile}) : profile = profile ?? analystProfile();

  Profile profile;

  /// Replaces [me]; e.g. to throw a 403 or hold the response open.
  Future<Profile> Function()? onMe;

  Future<UploadResult> Function(String filename, Uint8List bytes) onUpload = (
    filename,
    _,
  ) async => processedResult(filename);

  Future<Alert> Function(String alertId, String note)? onAcknowledge;

  /// Thrown by every read call (groups, spend, member, alerts, uploads).
  Object? readError;

  List<Alert> alertList = [
    for (final j in fixtureList('alerts')) Alert.fromJson(j),
  ];

  final calls = <String>[];
  final uploadedNames = <String>[];
  final acknowledged = <({String alertId, String note})>[];

  Future<T> _read<T>(String call, T Function() value) async {
    calls.add(call);
    if (readError != null) throw readError!;
    return value();
  }

  @override
  Future<Profile> me() async {
    calls.add('me');
    return onMe != null ? onMe!() : profile;
  }

  @override
  Future<List<Group>> groups() => _read('groups', () {
    final all = [for (final j in fixtureList('groups')) Group.fromJson(j)];
    // Mirror the API's broker scoping so screens see what a broker would.
    final brokerage = profile.brokerage;
    return profile.isAnalyst || brokerage == null
        ? all
        : all.where((g) => g.brokerage?.id == brokerage.id).toList();
  });

  @override
  Future<GroupSpend> groupSpend(String groupId, {String? policyYearId}) =>
      _read(
        'groupSpend $groupId ${policyYearId ?? '-'}',
        () => GroupSpend.fromJson(fixtureObject('group_spend')),
      );

  @override
  Future<MemberDetail> member(String memberId) => _read(
    'member $memberId',
    () => MemberDetail.fromJson(fixtureObject('member')),
  );

  @override
  Future<List<Alert>> alerts({
    AlertFilter status = AlertFilter.open,
    String? groupId,
  }) => _read(
    'alerts ${status.name} ${groupId ?? '-'}',
    () => [
      for (final a in alertList)
        if ((status == AlertFilter.all ||
                (status == AlertFilter.open) == a.isOpen) &&
            (groupId == null || a.groupId == groupId))
          a,
    ],
  );

  @override
  Future<Alert> acknowledgeAlert(String alertId, String note) async {
    calls.add('acknowledge $alertId');
    acknowledged.add((alertId: alertId, note: note));
    if (onAcknowledge != null) return onAcknowledge!(alertId, note);
    return alertList.firstWhere((a) => a.id == alertId);
  }

  @override
  Future<UploadResult> uploadFile(String filename, Uint8List bytes) {
    calls.add('upload $filename');
    uploadedNames.add(filename);
    return onUpload(filename, bytes);
  }

  @override
  Future<List<UploadResult>> uploads() => _read(
    'uploads',
    () => [for (final j in fixtureList('uploads')) UploadResult.fromJson(j)],
  );

  @override
  Future<UploadResult> upload(String uploadId) =>
      _read('upload $uploadId', () => processedResult('GA_2026-04.csv'));
}

/// [AuthService] without Firebase. Accepts any seeded email with
/// [password]; emits auth changes like Firebase (current user first).
class FakeAuth implements AuthService {
  FakeAuth([this._user]);

  static const password = 'Password123!';

  AuthUser? _user;
  final _listeners = <StreamController<AuthUser?>>[];
  int signOutCount = 0;

  AuthUser? get user => _user;

  void _emit(AuthUser? user) {
    _user = user;
    for (final c in [..._listeners]) {
      c.add(user);
    }
  }

  @override
  Stream<AuthUser?> authStateChanges() {
    late final StreamController<AuthUser?> controller;
    controller = StreamController<AuthUser?>(
      onListen: () {
        _listeners.add(controller);
        controller.add(_user);
      },
      onCancel: () => _listeners.remove(controller),
    );
    return controller.stream;
  }

  @override
  Future<void> signIn(String email, String password) async {
    if (password != FakeAuth.password) {
      throw const AuthFailure('Incorrect email or password.');
    }
    _emit(AuthUser(uid: email, email: email));
  }

  @override
  Future<void> signOut() async {
    signOutCount++;
    _emit(null);
  }

  @override
  Future<String?> idToken() async =>
      _user == null ? null : 'token-${_user!.uid}';
}

/// Lets pending futures and stream events run (outside of `testWidgets`).
Future<void> settle() async {
  for (var i = 0; i < 10; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}
