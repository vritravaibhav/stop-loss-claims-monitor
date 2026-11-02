import 'package:flutter/widgets.dart';

import 'models.dart';
import 'services/api_client.dart';
import 'session.dart';

/// Hands the API client and session to every screen without a DI package.
class AppScope extends InheritedWidget {
  const AppScope({
    super.key,
    required this.api,
    required this.session,
    required super.child,
  });

  final ApiClient api;
  final Session session;

  static AppScope of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'No AppScope above this widget');
    return scope!;
  }

  @override
  bool updateShouldNotify(AppScope oldWidget) =>
      api != oldWidget.api || session != oldWidget.session;
}

extension AppScopeContext on BuildContext {
  ApiClient get api => AppScope.of(this).api;

  /// The signed-in user's profile. Only valid below the home shell.
  Profile get profile => AppScope.of(this).session.profile!;

  bool get isAnalyst => profile.isAnalyst;
}
