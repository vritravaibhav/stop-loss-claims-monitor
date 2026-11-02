import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stoploss_monitor/theme.dart';

void main() {
  test('pushed pages zoom on every platform; none slide in', () {
    // Flutter web on a Mac or iPhone reports macOS/iOS, whose default is the
    // Cupertino slide-in from the right.
    for (final brightness in Brightness.values) {
      final builders = buildTheme(brightness).pageTransitionsTheme.builders;
      for (final platform in TargetPlatform.values) {
        expect(
          builders[platform],
          isA<ZoomPageTransitionsBuilder>(),
          reason: '${brightness.name} ${platform.name}',
        );
      }
    }
  });
}
