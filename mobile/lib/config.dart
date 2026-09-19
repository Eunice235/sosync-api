import 'package:flutter/foundation.dart';

/// Where the app looks for the API.
class AppConfig {
  AppConfig._();

  /// **The laptop running the backend, as the phone sees it on the wifi.**
  ///
  /// This is the one line to change when the laptop's address changes — on a different wifi
  /// network, or when the laptop joins the phone's hotspot. Find the value with `ipconfig`,
  /// under *Wireless LAN adapter Wi-Fi → IPv4 Address*, then rebuild and reinstall the APK.
  ///
  /// The Change link on the sign-in screen still works as a same-day override without a
  /// rebuild, for when the IP changes an hour before a demo.
  static const String serverAddress = 'http://192.168.0.103:8090';

  static const String _compileTimeOverride =
      String.fromEnvironment('SOSYNC_API', defaultValue: '');

  static String? _runtimeOverride;

  /// Set from saved preferences at startup, and when the user edits it.
  static void setRuntimeBaseUrl(String? url) {
    final trimmed = url?.trim();
    _runtimeOverride =
        (trimmed == null || trimmed.isEmpty) ? null : trimmed.replaceAll(RegExp(r'/+$'), '');
  }

  static String get baseUrl {
    if (_runtimeOverride != null) return _runtimeOverride!;
    if (_compileTimeOverride.isNotEmpty) return _compileTimeOverride;
    return defaultBaseUrl;
  }

  static String get defaultBaseUrl {
    // The web build runs in a browser on the laptop itself, so it reaches the backend locally.
    if (kIsWeb) return 'http://localhost:8090';
    // Phones and the Android emulator both reach the laptop by its wifi address.
    return serverAddress;
  }

  static bool get isCustomBaseUrl => _runtimeOverride != null;

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
