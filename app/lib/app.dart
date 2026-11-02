import 'package:flutter/material.dart';

import 'app_scope.dart';
import 'screens/home_shell.dart';
import 'screens/login_screen.dart';
import 'services/api_client.dart';
import 'session.dart';
import 'theme.dart';
import 'widgets/common.dart';

class StoplossApp extends StatelessWidget {
  const StoplossApp({super.key, required this.session, required this.api});

  final Session session;
  final ApiClient api;

  @override
  Widget build(BuildContext context) => AppScope(
    api: api,
    session: session,
    child: MaterialApp(
      title: 'Stop-loss monitor',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      home: const AuthGate(),
    ),
  );
}

/// Shows login, a loading state, or the home shell depending on the session.
class AuthGate extends StatefulWidget {
  const AuthGate({super.key});

  @override
  State<AuthGate> createState() => _AuthGateState();
}

class _AuthGateState extends State<AuthGate> {
  Session? _session;
  SessionStatus? _lastStatus;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final session = AppScope.of(context).session;
    if (session != _session) {
      _session?.removeListener(_onSession);
      _session = session..addListener(_onSession);
      _lastStatus = session.status;
    }
  }

  @override
  void dispose() {
    _session?.removeListener(_onSession);
    super.dispose();
  }

  void _onSession() {
    final status = _session!.status;
    // Leaving the signed-in state: close any dialog still open over the shell.
    if (_lastStatus == SessionStatus.ready && status != SessionStatus.ready) {
      Navigator.of(context).popUntil((route) => route.isFirst);
    }
    _lastStatus = status;
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final session = _session!;
    return switch (session.status) {
      SessionStatus.starting => const _Splash(message: 'Starting…'),
      SessionStatus.signedOut => const LoginScreen(),
      SessionStatus.loadingProfile => const _Splash(
        message: 'Loading your profile…',
      ),
      SessionStatus.profileError => Scaffold(
        body: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ErrorPanel(
              error: session.profileError ?? 'Unknown error',
              onRetry: session.retryProfile,
            ),
            TextButton(
              onPressed: session.signOut,
              child: const Text('Sign out'),
            ),
          ],
        ),
      ),
      SessionStatus.ready => HomeShell(key: ValueKey(session.profile!.email)),
    };
  }
}

class _Splash extends StatelessWidget {
  const _Splash({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: 16),
          Text(message),
        ],
      ),
    ),
  );
}

/// Shown instead of the app when the build is misconfigured.
class ConfigErrorApp extends StatelessWidget {
  const ConfigErrorApp({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Stop-loss monitor',
    debugShowCheckedModeBanner: false,
    theme: buildTheme(Brightness.light),
    darkTheme: buildTheme(Brightness.dark),
    home: Scaffold(body: ErrorPanel(error: message)),
  );
}
