import 'package:flutter/material.dart';

import 'app.dart';
import 'config.dart';
import 'services/auth_service.dart';
import 'services/http_api_client.dart';
import 'session.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final missing = AppConfig.unfilledPlaceholders;
  if (missing.isNotEmpty) {
    runApp(
      ConfigErrorApp(
        message:
            'This build is missing its Firebase configuration: '
            '${missing.join(', ')} still set to REPLACE_ME. Fill in '
            'config/prod.json and rebuild with --dart-define-from-file.',
      ),
    );
    return;
  }

  final AuthService auth;
  try {
    auth = await FirebaseAuthService.initialize();
  } catch (e) {
    runApp(ConfigErrorApp(message: 'Could not start Firebase Auth: $e'));
    return;
  }

  // The client signs the user out on any 401, so it needs the session, and
  // the session needs the client for GET /api/me.
  late final Session session;
  final api = HttpApiClient(
    baseUrl: AppConfig.apiBaseUrl,
    tokenProvider: auth.idToken,
    onUnauthorized: () => session.expire(),
  );
  session = Session(auth: auth, api: api)..start();

  runApp(StoplossApp(session: session, api: api));
}
