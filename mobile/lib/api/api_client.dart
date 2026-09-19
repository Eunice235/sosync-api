import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config.dart';
import 'models.dart';

/// An error the API reported deliberately, carrying the code the UI branches on.
class ApiException implements Exception {
  ApiException(this.statusCode, this.code, this.message, {this.fieldErrors});

  final int statusCode;

  /// The machine-readable code, e.g. `invalid_safety_pin`, `conflict`, `not_found`.
  final String code;
  final String message;
  final Map<String, String>? fieldErrors;

  /// The one error the UI must treat specially: a wrong PIN leaves the emergency running, so
  /// the screen stays outwardly calm rather than showing a failure.
  bool get isInvalidSafetyPin => code == 'invalid_safety_pin';

  bool get isUnauthorized => statusCode == 401;
  bool get isConflict => statusCode == 409;
  bool get isNotFound => statusCode == 404;

  @override
  String toString() => message;
}

/// Could not reach the server at all. Distinct from [ApiException] because the advice to the
/// user is completely different: check the connection, not the input.
class NetworkException implements Exception {
  NetworkException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Thin HTTP layer over the SOSync API.
///
/// Deliberately not a generated client. It attaches the bearer token, turns the service's error
/// bodies into [ApiException] with their code intact, and nothing else.
class ApiClient {
  ApiClient({http.Client? httpClient}) : _http = httpClient ?? http.Client();

  final http.Client _http;

  String? _accessToken;

  /// Called by the auth store whenever the token changes.
  void setAccessToken(String? token) => _accessToken = token;

  bool get hasToken => _accessToken != null;

  // ── Authentication ───────────────────────────────────────────────────────────────

  Future<AuthResult> login(String identifier, String password) async {
    final json = await _post('/api/auth/login', {
      'identifier': identifier,
      'password': password,
    }, authenticated: false);
    return AuthResult.fromJson(json as Map<String, dynamic>);
  }

  Future<AuthResult> register({
    required String fullName,
    required String phone,
    required String password,
    String? email,
    String? role,
    String? organization,
    String? preferredLanguage,
  }) async {
    final json = await _post('/api/auth/register', {
      'fullName': fullName,
      'phone': phone,
      'password': password,
      if (email != null && email.isNotEmpty) 'email': email,
      if (role != null) 'role': role,
      if (organization != null && organization.isNotEmpty) 'organization': organization,
      if (preferredLanguage != null) 'preferredLanguage': preferredLanguage,
    }, authenticated: false);
    return AuthResult.fromJson(json as Map<String, dynamic>);
  }

  /// Exchanges a refresh token for a fresh pair.
  ///
  /// Sent unauthenticated on purpose: the whole point is that the access token is no longer
  /// usable, so attaching it would be pointless and, once expired, actively confusing.
  Future<AuthResult> refreshSession(String refreshToken) async {
    final json = await _post(
      '/api/auth/refresh',
      {'refreshToken': refreshToken},
      authenticated: false,
    );
    return AuthResult.fromJson(json as Map<String, dynamic>);
  }

  Future<AppUser> me() async {
    final json = await _get('/api/auth/me');
    return AppUser.fromJson(json as Map<String, dynamic>);
  }

  Future<AppUser> updateProfile({
    String? fullName,
    String? email,
    String? emergencyNote,
    String? preferredLanguage,
  }) async {
    final json = await _put('/api/auth/me', {
      if (fullName != null) 'fullName': fullName,
      if (email != null) 'email': email,
      if (emergencyNote != null) 'emergencyNote': emergencyNote,
      if (preferredLanguage != null) 'preferredLanguage': preferredLanguage,
    });
    return AppUser.fromJson(json as Map<String, dynamic>);
  }

  Future<AppUser> setSafetyPin(String pin, String password) async {
    final json = await _put('/api/auth/me/safety-pin', {
      'pin': pin,
      'password': password,
    });
    return AppUser.fromJson(json as Map<String, dynamic>);
  }

  // ── Trusted contacts ─────────────────────────────────────────────────────────────

  Future<List<TrustedContact>> contacts() async {
    final json = await _get('/api/me/contacts');
    return (json as List<dynamic>)
        .map((e) => TrustedContact.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<TrustedContact> addContact({
    required String name,
    required String phone,
    String? relationship,
    int priority = 1,
    String? language,
  }) async {
    final json = await _post('/api/me/contacts', {
      'name': name,
      'phone': phone,
      if (relationship != null && relationship.isNotEmpty) 'relationship': relationship,
      'priority': priority,
      'notificationEnabled': true,
      if (language != null) 'language': language,
    });
    return TrustedContact.fromJson(json as Map<String, dynamic>);
  }

  Future<void> deleteContact(String contactId) => _delete('/api/me/contacts/$contactId');

  // ── Emergencies ──────────────────────────────────────────────────────────────────

  Future<TriggerOutcome> triggerSos({
    double? latitude,
    double? longitude,
    double? accuracy,
    bool silent = true,
    String? note,
  }) async {
    final json = await _post('/api/incidents', {
      if (latitude != null) 'latitude': latitude,
      if (longitude != null) 'longitude': longitude,
      if (accuracy != null) 'accuracy': accuracy,
      'silent': silent,
      if (note != null && note.isNotEmpty) 'note': note,
    });
    return TriggerOutcome.fromJson(json as Map<String, dynamic>);
  }

  /// The caller's open emergency, or null. The API answers 204 when there is none.
  Future<Incident?> activeIncident() async {
    final json = await _get('/api/incidents/active', allowEmpty: true);
    if (json == null) return null;
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  Future<Incident> incident(String incidentId, {bool includeTrail = false}) async {
    final json = await _get('/api/incidents/$incidentId?includeTrail=$includeTrail');
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  Future<List<Incident>> incidentHistory() async {
    final json = await _get('/api/incidents?size=50');
    return ((json as Map<String, dynamic>)['items'] as List<dynamic>)
        .map((e) => Incident.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<Incident> reportLocation({
    required String incidentId,
    required double latitude,
    required double longitude,
    double? accuracy,
    DateTime? recordedAt,
  }) async {
    final json = await _post('/api/incidents/$incidentId/locations', {
      'latitude': latitude,
      'longitude': longitude,
      if (accuracy != null) 'accuracy': accuracy,
      if (recordedAt != null) 'recordedAt': recordedAt.toUtc().toIso8601String(),
    });
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  Future<List<LocationPoint>> locationTrail(String incidentId) async {
    final json = await _get('/api/incidents/$incidentId/locations');
    return (json as List<dynamic>)
        .map((e) => LocationPoint.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Throws [ApiException] with `invalid_safety_pin` when the PIN is wrong. The incident keeps
  /// running in that case.
  Future<Incident> cancelIncident({
    required String incidentId,
    required String pin,
    String? reason,
  }) async {
    final json = await _post('/api/incidents/$incidentId/cancel', {
      'pin': pin,
      if (reason != null && reason.isNotEmpty) 'reason': reason,
    });
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  // ── Trusted contact inbox ────────────────────────────────────────────────────────

  Future<List<Incident>> watchedAlerts({bool includeClosed = false}) async {
    final json = await _get('/api/alerts?includeClosed=$includeClosed');
    return (json as List<dynamic>)
        .map((e) => Incident.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  // ── Responder ────────────────────────────────────────────────────────────────────

  Future<ResponderSummary> responderProfile() async {
    final json = await _get('/api/responder/me');
    return ResponderSummary.fromJson(json as Map<String, dynamic>);
  }

  Future<ResponderSummary> setAvailability(String availability) async {
    final json = await _put('/api/responder/availability', {'availability': availability});
    return ResponderSummary.fromJson(json as Map<String, dynamic>);
  }

  Future<ResponderSummary> updateResponderLocation(double latitude, double longitude) async {
    final json = await _put('/api/responder/location', {
      'latitude': latitude,
      'longitude': longitude,
    });
    return ResponderSummary.fromJson(json as Map<String, dynamic>);
  }

  Future<List<ResponderAlert>> responderAlerts() async {
    final json = await _get('/api/responder/alerts');
    return (json as List<dynamic>)
        .map((e) => ResponderAlert.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<Incident> responderIncident(String incidentId) async {
    final json = await _get('/api/responder/incidents/$incidentId');
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  /// Throws [ApiException] with a 409 when another responder got there first.
  Future<Incident> acceptIncident(String incidentId) async {
    final json = await _post('/api/responder/incidents/$incidentId/accept', {});
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  Future<Incident> advanceIncident(String incidentId, String status, {String? note}) async {
    final json = await _post('/api/responder/incidents/$incidentId/status', {
      'status': status,
      if (note != null && note.isNotEmpty) 'note': note,
    });
    return Incident.fromJson(json as Map<String, dynamic>);
  }

  // ── Notifications ────────────────────────────────────────────────────────────────

  Future<List<AlertDelivery>> notifications() async {
    final json = await _get('/api/notifications?size=50');
    return ((json as Map<String, dynamic>)['items'] as List<dynamic>)
        .map((e) => AlertDelivery.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<int> unreadCount() async {
    final json = await _get('/api/notifications/unread-count');
    return ((json as Map<String, dynamic>)['count'] as num).toInt();
  }

  Future<AlertDelivery> acknowledge(String notificationId) async {
    final json = await _post('/api/notifications/$notificationId/acknowledge', {});
    return AlertDelivery.fromJson(json as Map<String, dynamic>);
  }

  // ── Metadata ─────────────────────────────────────────────────────────────────────

  Future<List<LanguageOption>> languages() async {
    final json = await _get('/api/meta/languages', authenticated: false);
    return (json as List<dynamic>)
        .map((e) => LanguageOption.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  // ── Plumbing ─────────────────────────────────────────────────────────────────────

  Map<String, String> _headers({bool authenticated = true, bool json = false}) => {
        if (json) 'Content-Type': 'application/json',
        if (authenticated && _accessToken != null) 'Authorization': 'Bearer $_accessToken',
      };

  Uri _uri(String path) => Uri.parse('${AppConfig.baseUrl}$path');

  Future<dynamic> _get(
    String path, {
    bool authenticated = true,
    bool allowEmpty = false,
  }) async {
    final response = await _send(
      () => _http.get(_uri(path), headers: _headers(authenticated: authenticated)),
    );
    return _decode(response, allowEmpty: allowEmpty);
  }

  Future<dynamic> _post(
    String path,
    Map<String, dynamic> body, {
    bool authenticated = true,
  }) async {
    final response = await _send(
      () => _http.post(
        _uri(path),
        headers: _headers(authenticated: authenticated, json: true),
        body: jsonEncode(body),
      ),
    );
    return _decode(response);
  }

  Future<dynamic> _put(String path, Map<String, dynamic> body) async {
    final response = await _send(
      () => _http.put(_uri(path), headers: _headers(json: true), body: jsonEncode(body)),
    );
    return _decode(response);
  }

  Future<void> _delete(String path) async {
    final response = await _send(() => _http.delete(_uri(path), headers: _headers()));
    _decode(response, allowEmpty: true);
  }

  Future<http.Response> _send(Future<http.Response> Function() request) async {
    try {
      return await request().timeout(const Duration(seconds: 20));
    } on TimeoutException {
      throw NetworkException('The server did not answer in time.');
    } catch (error) {
      // Anything below HTTP — DNS, refused connection, no route — lands here.
      throw NetworkException(
        'Cannot reach the server at ${AppConfig.baseUrl}. '
        'Check it is running and that the address is right for this device.',
      );
    }
  }

  dynamic _decode(http.Response response, {bool allowEmpty = false}) {
    final status = response.statusCode;

    if (status == 204 || response.body.isEmpty) {
      if (allowEmpty || (status >= 200 && status < 300)) return null;
      throw ApiException(status, 'unknown', 'The server returned an empty response.');
    }

    dynamic decoded;
    try {
      decoded = jsonDecode(response.body);
    } catch (_) {
      if (status >= 200 && status < 300) return null;
      throw ApiException(status, 'unreadable', 'The server response could not be read.');
    }

    if (status >= 200 && status < 300) return decoded;

    // Error bodies from the service look like
    // {"error":"invalid_safety_pin","message":"...","fields":{...}}
    if (decoded is Map<String, dynamic>) {
      final fields = decoded['fields'];
      throw ApiException(
        status,
        decoded['error'] as String? ?? 'unknown',
        decoded['message'] as String? ?? 'Something went wrong.',
        fieldErrors: fields is Map
            ? fields.map((k, v) => MapEntry(k.toString(), v.toString()))
            : null,
      );
    }

    throw ApiException(status, 'unknown', 'Request failed with status $status.');
  }

  void close() => _http.close();
}
