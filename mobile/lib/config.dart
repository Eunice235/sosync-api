import 'package:flutter/foundation.dart';

/// Where the app looks for the API.
class AppConfig {
  AppConfig._();

  /// **The hosted API (Render — see deploy/RENDER.md).**
  ///
  /// Every installed APK talks to this address, from any network. There is deliberately no
  /// way to change it from inside the app: a tester who mistypes it gets an app that silently
  /// reaches nobody, which on an SOS button is the worst possible failure.
  static const String serverAddress = 'https://sosync-api-8gt4.onrender.com';

  /// For development against a backend on your own laptop, without touching this file:
  /// `flutter run --dart-define=SOSYNC_API=http://localhost:8090` (with `adb reverse tcp:8090
  /// tcp:8090` for a phone on USB).
  static const String _compileTimeOverride =
      String.fromEnvironment('SOSYNC_API', defaultValue: '');

  static String get baseUrl {
    if (_compileTimeOverride.isNotEmpty) return _compileTimeOverride;
    // The web build runs in a browser on the laptop itself, so it reaches the backend locally.
    if (kIsWeb) return 'http://localhost:8090';
    return serverAddress;
  }

  /// How often the app re-reads an open incident.
  ///
  /// Polling rather than a socket or push. It survives a connection that drops and comes back,
  /// needs nothing running on the server between requests, and cannot silently stop delivering
  /// the way a stale websocket can. Four seconds is frequent enough to feel live and light
  /// enough not to drain a phone that may need to last the rest of the evening.
  static const Duration incidentPollInterval = Duration(seconds: 4);

  /// How often the reporter's position is sent while an incident is open.
  static const Duration locationReportInterval = Duration(seconds: 10);

  /// How long the SOS button must be held. The project brief specifies three seconds.
  static const Duration sosHoldDuration = Duration(seconds: 3);
}
