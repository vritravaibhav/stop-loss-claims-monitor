import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';

import '../config.dart';

/// The signed-in Firebase user, reduced to what the app needs.
class AuthUser {
  const AuthUser({required this.uid, required this.email});

  final String uid;
  final String? email;
}

/// Thrown by [AuthService.signIn] with a message fit to show the user.
class AuthFailure implements Exception {
  const AuthFailure(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Firebase Auth behind an interface so widget tests can run without Firebase.
abstract interface class AuthService {
  /// Emits the current user (or null) immediately, then on every change.
  Stream<AuthUser?> authStateChanges();

  Future<void> signIn(String email, String password);

  Future<void> signOut();

  /// A fresh-enough Firebase ID token for the API, or null when signed out.
  Future<String?> idToken();
}

class FirebaseAuthService implements AuthService {
  FirebaseAuthService._(this._auth);

  /// Initialises Firebase from [AppConfig] and, by default, points Auth at the
  /// local emulator.
  static Future<FirebaseAuthService> initialize() async {
    await Firebase.initializeApp(
      options: const FirebaseOptions(
        apiKey: AppConfig.firebaseApiKey,
        appId: AppConfig.firebaseAppId,
        messagingSenderId: AppConfig.firebaseMessagingSenderId,
        projectId: AppConfig.firebaseProjectId,
        authDomain: AppConfig.firebaseAuthDomain,
      ),
    );
    final auth = FirebaseAuth.instance;
    if (AppConfig.useAuthEmulator) {
      await auth.useAuthEmulator(
        AppConfig.authEmulatorHost,
        AppConfig.authEmulatorPort,
      );
    }
    return FirebaseAuthService._(auth);
  }

  final FirebaseAuth _auth;

  @override
  Stream<AuthUser?> authStateChanges() => _auth.authStateChanges().map(
    (u) => u == null ? null : AuthUser(uid: u.uid, email: u.email),
  );

  @override
  Future<void> signIn(String email, String password) async {
    try {
      await _auth.signInWithEmailAndPassword(email: email, password: password);
    } on FirebaseAuthException catch (e) {
      throw AuthFailure(_describe(e));
    }
  }

  @override
  Future<void> signOut() => _auth.signOut();

  @override
  Future<String?> idToken() async => _auth.currentUser?.getIdToken();

  static String _describe(FirebaseAuthException e) => switch (e.code) {
    'invalid-credential' ||
    'wrong-password' ||
    'user-not-found' ||
    'INVALID_LOGIN_CREDENTIALS' => 'Incorrect email or password.',
    'invalid-email' => 'That email address is not valid.',
    'user-disabled' => 'This account has been disabled.',
    'too-many-requests' => 'Too many attempts. Wait a moment and try again.',
    'network-request-failed' =>
      'Could not reach the sign-in service. Check your connection'
          '${AppConfig.useAuthEmulator ? ' and that the Auth emulator is running' : ''}.',
    _ => e.message ?? 'Sign-in failed (${e.code}).',
  };
}
