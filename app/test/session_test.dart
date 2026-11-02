import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:stoploss_monitor/models.dart';
import 'package:stoploss_monitor/services/api_client.dart';
import 'package:stoploss_monitor/services/auth_service.dart';
import 'package:stoploss_monitor/session.dart';

import 'fakes.dart';

const analyst = AuthUser(uid: 'u1', email: 'analyst@example.test');

void main() {
  late FakeAuth auth;
  late FakeApi api;
  late Session session;
  late List<SessionStatus> seen;

  void start({AuthUser? user, Profile? profile}) {
    auth = FakeAuth(user);
    api = FakeApi(profile: profile);
    seen = [];
    session = Session(auth: auth, api: api)
      ..addListener(() => seen.add(session.status))
      ..start();
  }

  tearDown(() => session.dispose());

  test('no Firebase user → signedOut, and /api/me is not called', () async {
    start();
    expect(session.status, SessionStatus.starting);
    await settle();
    expect(session.status, SessionStatus.signedOut);
    expect(api.calls, isEmpty);
  });

  test('signed in → role and brokerage come from /api/me', () async {
    start(user: analyst, profile: brokerProfile());
    await settle();
    expect(seen, [SessionStatus.loadingProfile, SessionStatus.ready]);
    expect(api.calls, ['me']);
    expect(session.profile!.role, Role.broker);
    expect(session.profile!.brokerage!.name, 'Brokerage A');
  });

  test(
    'signIn → ready; signOut → signedOut with the profile cleared',
    () async {
      start();
      await settle();
      await session.signIn('analyst@example.test', FakeAuth.password);
      await settle();
      expect(session.status, SessionStatus.ready);
      expect(session.profile!.isAnalyst, isTrue);

      await session.signOut();
      await settle();
      expect(session.status, SessionStatus.signedOut);
      expect(session.profile, isNull);
      expect(session.notice, isNull);
    },
  );

  test('a wrong password surfaces AuthFailure and stays signed out', () async {
    start();
    await settle();
    await expectLater(
      session.signIn('analyst@example.test', 'nope'),
      throwsA(isA<AuthFailure>()),
    );
    expect(session.status, SessionStatus.signedOut);
  });

  test(
    '403 from /api/me (no app_user row) → signed out with a notice',
    () async {
      start(user: analyst);
      api.onMe = () async => throw const ForbiddenException('Unknown user');
      await settle();
      expect(session.status, SessionStatus.signedOut);
      expect(auth.signOutCount, 1);
      expect(session.notice, contains('analyst@example.test'));
      expect(session.notice, contains('not set up'));
    },
  );

  test('other /api/me failures → profileError, and retry recovers', () async {
    start(user: analyst);
    api.onMe = () async =>
        throw const ServerException('Internal error', statusCode: 500);
    await settle();
    expect(session.status, SessionStatus.profileError);
    expect(session.profileError, 'Internal error');
    expect(auth.signOutCount, 0);

    api.onMe = null;
    await session.retryProfile();
    expect(session.status, SessionStatus.ready);
    expect(session.profileError, isNull);
  });

  test('expire() (any 401) signs out with an expiry notice', () async {
    start(user: analyst);
    await settle();
    session.expire();
    await settle();
    expect(session.status, SessionStatus.signedOut);
    expect(session.notice, contains('expired'));

    // Already signed out: a late 401 does not sign out again.
    session.expire();
    await settle();
    expect(auth.signOutCount, 1);
  });

  test('a slow /api/me that answers after sign-out is ignored', () async {
    final slow = Completer<Profile>();
    start(user: analyst);
    api.onMe = () => slow.future;
    await settle();
    expect(session.status, SessionStatus.loadingProfile);

    await session.signOut();
    await settle();
    slow.complete(analystProfile());
    await settle();
    expect(session.status, SessionStatus.signedOut);
    expect(session.profile, isNull);
  });
}
