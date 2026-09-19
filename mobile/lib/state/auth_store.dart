import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../config.dart';

/// Session state: who is signed in, and the token that proves it.
///
/// Tokens go in [SharedPreferences] rather than secure storage. That is a deliberate prototype
/// compromise, noted here so it is not mistaken for an oversight: on a rooted or compromised
/// device these are readable. A production build wants `flutter_secure_storage`, which is a
/// drop-in change at the three call sites below.
class AuthStore extends ChangeNotifier {
  AuthStore(this._api);

  final ApiClient _api;

  static const _keyAccessToken = 'sosync.accessToken';
  static const _keyRefreshToken = 'sosync.refreshToken';
  static const _keyBaseUrl = 'sosync.baseUrl';

  AppUser? _user;
  String? _refreshToken;
  bool _restoring = true;

  AppUser? get user => _user;
  bool get isSignedIn => _user != null;
  bool get isRestoring => _restoring;

  /// The language everything in the app is rendered in. Read from the account rather than
  /// hard-coded, so a second language would light up the whole UI by changing one field.
  String get languageCode => _user?.preferredLanguage ?? 'EN';

  /// Reads the saved session on launch, so a reporter is never met by a login screen in an
  /// emergency.
  Future<void> restore() async {
    try {
      final prefs = await SharedPreferences.getInstance();

      // The server address is restored first: without it, the token check below would be
      // pointed at the wrong host and fail for the wrong reason.
      AppConfig.setRuntimeBaseUrl(prefs.getString(_keyBaseUrl));

      final token = prefs.getString(_keyAccessToken);
      _refreshToken = prefs.getString(_keyRefreshToken);

      if (token != null) {
        _api.setAccessToken(token);
        try {
          _user = await _api.me();
        } on ApiException catch (error) {
          if (error.isUnauthorized) {
            await _tryRefresh(prefs);
          } else {
            await _clear(prefs);
          }
        } on NetworkException {
          // Server unreachable at launch. Keep the token: the session is probably still valid
          // and signing the user out because the wifi is down would be the wrong call.
        }
      }
    } finally {
      _restoring = false;
      notifyListeners();
    }
  }

  Future<void> _tryRefresh(SharedPreferences prefs) async {
    final refresh = _refreshToken;
    if (refresh == null) {
      await _clear(prefs);
      return;
    }
    try {
      final result = await _api.refreshSession(refresh);
      await _persist(prefs, result);
      _user = result.user;
    } catch (_) {
      await _clear(prefs);
    }
  }

  Future<AuthResult> signIn(String identifier, String password) async {
    final result = await _api.login(identifier, password);
    await _apply(result);
    return result;
  }

  Future<AuthResult> register({
    required String fullName,
    required String phone,
    required String password,
    String? email,
    bool asResponder = false,
    String? organization,
    String? preferredLanguage,
  }) async {
    final result = await _api.register(
      fullName: fullName,
      phone: phone,
      password: password,
      email: email,
      role: asResponder ? 'RESPONDER' : null,
      organization: organization,
      preferredLanguage: preferredLanguage,
    );
    await _apply(result);
    return result;
  }

  Future<void> signOut() async {
    final prefs = await SharedPreferences.getInstance();
    await _clear(prefs);
    notifyListeners();
  }

  /// Replaces the cached user after a profile change, so the UI language follows immediately.
  void updateUser(AppUser user) {
    _user = user;
    notifyListeners();
  }

  Future<void> refreshUser() async {
    try {
      _user = await _api.me();
      notifyListeners();
    } catch (_) {
      // A failed refresh of the profile is not worth disturbing the user over.
    }
  }

  /// Points the app at a different server and remembers it. Used to demo on a real phone.
  Future<void> setBaseUrl(String? url) async {
    final prefs = await SharedPreferences.getInstance();
    if (url == null || url.trim().isEmpty) {
      await prefs.remove(_keyBaseUrl);
      AppConfig.setRuntimeBaseUrl(null);
    } else {
      await prefs.setString(_keyBaseUrl, url.trim());
      AppConfig.setRuntimeBaseUrl(url);
    }
    notifyListeners();
  }

  Future<void> _apply(AuthResult result) async {
    final prefs = await SharedPreferences.getInstance();
    await _persist(prefs, result);
    _user = result.user;
    notifyListeners();
  }

  Future<void> _persist(SharedPreferences prefs, AuthResult result) async {
    await prefs.setString(_keyAccessToken, result.accessToken);
    await prefs.setString(_keyRefreshToken, result.refreshToken);
    _refreshToken = result.refreshToken;
    _api.setAccessToken(result.accessToken);
  }

  Future<void> _clear(SharedPreferences prefs) async {
    await prefs.remove(_keyAccessToken);
    await prefs.remove(_keyRefreshToken);
    _refreshToken = null;
    _user = null;
    _api.setAccessToken(null);
  }
}
