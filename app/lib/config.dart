/// Build-time configuration from `--dart-define` / `--dart-define-from-file`.
///
/// Defaults target local development: the Firebase Auth emulator on
/// localhost:9099 and the API on the same origin as the web app (nginx proxies
/// `/api`). Production values live in `config/prod.json`.
class AppConfig {
  const AppConfig._();

  /// Base URL of the API. Empty means "same origin as the page".
  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL');

  static const firebaseApiKey = String.fromEnvironment(
    'FIREBASE_API_KEY',
    defaultValue: 'demo-api-key',
  );
  static const firebaseAppId = String.fromEnvironment(
    'FIREBASE_APP_ID',
    defaultValue: '1:000000000000:web:0000000000000000',
  );
  static const firebaseProjectId = String.fromEnvironment(
    'FIREBASE_PROJECT_ID',
    defaultValue: 'demo-stoploss',
  );
  static const firebaseAuthDomain = String.fromEnvironment(
    'FIREBASE_AUTH_DOMAIN',
    defaultValue: 'demo-stoploss.firebaseapp.com',
  );
  static const firebaseMessagingSenderId = String.fromEnvironment(
    'FIREBASE_MESSAGING_SENDER_ID',
    defaultValue: '000000000000',
  );

  static const useAuthEmulator = bool.fromEnvironment(
    'USE_AUTH_EMULATOR',
    defaultValue: true,
  );
  static const authEmulatorHost = String.fromEnvironment(
    'AUTH_EMULATOR_HOST',
    defaultValue: 'localhost',
  );
  static const authEmulatorPort = int.fromEnvironment(
    'AUTH_EMULATOR_PORT',
    defaultValue: 9099,
  );

  /// Values still set to the `config/prod.json` placeholder. A build with any
  /// of these cannot sign in, so the app shows a configuration error instead.
  static List<String> get unfilledPlaceholders => [
    if (firebaseApiKey == _placeholder) 'FIREBASE_API_KEY',
    if (firebaseAppId == _placeholder) 'FIREBASE_APP_ID',
    if (firebaseProjectId == _placeholder) 'FIREBASE_PROJECT_ID',
    if (firebaseAuthDomain == _placeholder) 'FIREBASE_AUTH_DOMAIN',
    if (firebaseMessagingSenderId == _placeholder)
      'FIREBASE_MESSAGING_SENDER_ID',
  ];

  static const _placeholder = 'REPLACE_ME';
}
