import 'package:flutter/material.dart';

import 'format.dart';

/// A muted steel blue: calm, readable, nothing alarming until a band says so.
const _seed = Color(0xFF2F5D7C);

ThemeData buildTheme(Brightness brightness) {
  final scheme = ColorScheme.fromSeed(seedColor: _seed, brightness: brightness);
  return ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surfaceContainerLowest,
    appBarTheme: AppBarTheme(
      centerTitle: false,
      backgroundColor: scheme.surfaceContainerLowest,
      scrolledUnderElevation: 1,
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: scheme.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      border: OutlineInputBorder(),
    ),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
    dividerTheme: DividerThemeData(color: scheme.outlineVariant, space: 1),
  );
}

/// Colour for a spend band: neutral below 50%, then amber, orange, red.
Color bandColor(SpendBand band, ColorScheme scheme) {
  final dark = scheme.brightness == Brightness.dark;
  return switch (band) {
    SpendBand.normal => scheme.primary,
    SpendBand.watch => dark ? const Color(0xFFFFCA28) : const Color(0xFFB98500),
    SpendBand.high => dark ? const Color(0xFFFF8A3D) : const Color(0xFFD9480F),
    SpendBand.over => scheme.error,
  };
}

String bandLabel(SpendBand band) => switch (band) {
  SpendBand.normal => 'Under 50%',
  SpendBand.watch => '50% or more',
  SpendBand.high => '75% or more',
  SpendBand.over => 'At or over deductible',
};
