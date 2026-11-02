import 'dart:convert';
import 'dart:io';

import 'package:stoploss_monitor/models.dart';

/// Reads `test/fixtures/<name>.json` (flutter test runs from the package root).
dynamic fixture(String name) =>
    jsonDecode(File('test/fixtures/$name.json').readAsStringSync());

String fixtureText(String name) =>
    File('test/fixtures/$name.json').readAsStringSync();

Json fixtureObject(String name) => fixture(name) as Json;

List<Json> fixtureList(String name) => [
  for (final item in fixture(name) as List) item as Json,
];
