import 'dart:async';

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'services/api_client.dart';
import 'services/auth_service.dart';

enum SessionStatus {
  /// Waiting for Firebase to report whether someone is signed in.
  starting,
  signedOut,

  /// Signed in to Firebase; fetching role and brokerage from `GET /api/me`.
  loadingProfile,
  ready,

  /// Signed in, but `/api/me` failed for a reason other than 401/403.
  profileError,
}

/// Who is signed in and what the API says they may do. The role comes from
/// the API (the `app_user` table), never from the Firebase token.
class Session extends ChangeNotifier {
  Session({required this.auth, required this.api});

  final AuthService auth;
  final ApiClient api;

  SessionStatus _status = SessionStatus.starting;
  AuthUser? _user;
  Profile? _profile;
  String? _profileError;
  String? _notice;
  StreamSubscription<AuthUser?>? _subscription;
  int _generation = 0;

  SessionStatus get status => _status;
  AuthUser? get user => _user;

  /// Non-null when [status] is [SessionStatus.ready].
  Profile? get profile => _profile;
  String? get profileError => _profileError;

  /// Message for the login screen, e.g. why the user was signed out.
  String? get notice => _notice;

  void start() {
    _subscription ??= auth.authStateChanges().listen(_onUser);
  }

  Future<void> _onUser(AuthUser? user) async {
    final generation = ++_generation;
    _user = user;
    _profile = null;
    _profileError = null;
    if (user == null) {
      _status = SessionStatus.signedOut;
      notifyListeners();
      return;
    }
    _status = SessionStatus.loadingProfile;
    notifyListeners();

    try {
      final profile = await api.me();
      if (generation != _generation) return;
      _profile = profile;
      _notice = null;
      _status = SessionStatus.ready;
    } on UnauthorizedException {
      return; // The API client already called expire().
    } on ForbiddenException catch (e) {
      if (generation != _generation) return;
      await signOut(
        notice:
            '${user.email ?? 'This account'} is not set up for this app '
            '(${e.message}).',
      );
      return;
    } catch (e) {
      if (generation != _generation) return;
      _profileError = e.toString();
      _status = SessionStatus.profileError;
    }
    notifyListeners();
  }

  Future<void> retryProfile() => _onUser(_user);

  /// Throws [AuthFailure] with a user-facing message.
  Future<void> signIn(String email, String password) async {
    _notice = null;
    await auth.signIn(email, password);
  }

  Future<void> signOut({String? notice}) async {
    _notice = notice;
    await auth.signOut();
  }

  /// Called by the API client on any 401.
  void expire() {
    if (_status == SessionStatus.signedOut) return;
    unawaited(
      signOut(notice: 'Your session has expired. Please sign in again.'),
    );
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}
