import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../config.dart';
import 'alarm_service.dart';
import 'location_service.dart';

/// Drives the reporter's side of an emergency: raising one, keeping it fed with positions, and
/// standing it down.
///
/// Two timers run while an incident is open. They are separate because they answer different
/// questions at different rates: one asks the server what has changed (has a responder taken
/// it?), the other tells the server where the reporter is now. Merging them would tie the
/// refresh rate of the screen to the battery cost of GPS.
class IncidentStore extends ChangeNotifier {
  IncidentStore(this._api, this._location, this._alarm);

  final ApiClient _api;
  final LocationService _location;
  final AlarmService _alarm;

  Incident? _active;
  Timer? _pollTimer;
  Timer? _locationTimer;

  bool _triggering = false;
  bool _cancelling = false;
  String? _lastSummary;
  List<AlertDelivery> _lastDeliveries = const [];
  LocationAvailability _locationAvailability = LocationAvailability.ok;
  String? _error;

  Incident? get active => _active;
  bool get hasActive => _active != null && _active!.isOpen;
  bool get isTriggering => _triggering;
  bool get isCancelling => _cancelling;

  /// The server's plain-language line about who was reached, in the reporter's language.
  String? get lastSummary => _lastSummary;
  List<AlertDelivery> get lastDeliveries => _lastDeliveries;
  LocationAvailability get locationAvailability => _locationAvailability;
  String? get error => _error;

  /// Called on launch and when returning to the SOS screen, so an emergency raised on another
  /// device — or before the app was killed — is picked up rather than lost.
  Future<void> loadActive() async {
    try {
      _active = await _api.activeIncident();
      _error = null;
      if (hasActive) {
        _startTimers();
      } else {
        _stopTimers();
      }
    } on ApiException catch (error) {
      _error = error.message;
    } on NetworkException catch (error) {
      _error = error.message;
    }
    notifyListeners();
  }

  Future<void> checkLocationPermission() async {
    _locationAvailability = await _location.ensurePermission();
    notifyListeners();
  }

  /// Raise an emergency.
  ///
  /// Takes a position first, but never waits indefinitely for one and never fails because of
  /// one. If [LocationService] cannot produce a fix, the alert goes out without coordinates and
  /// the recipients are told the location is not yet known.
  Future<TriggerOutcome?> trigger({bool silent = true, String? note}) async {
    if (_triggering) return null;
    _triggering = true;
    _error = null;
    notifyListeners();

    try {
      final fix = await _location.currentPosition();
      _locationAvailability = fix.availability;

      final outcome = await _api.triggerSos(
        latitude: fix.position?.latitude,
        longitude: fix.position?.longitude,
        accuracy: fix.position?.accuracy,
        silent: silent,
        note: note,
      );

      _active = outcome.incident;
      _lastSummary = outcome.summary;
      _lastDeliveries = outcome.notifications;

      // Confirm by feel. This fires only after the server has answered, so the buzz means
      // "it is away", not "the button worked" - which is the only version worth having when
      // the reporter cannot look at the screen.
      await _alarm.confirmAlertSent(silent: outcome.incident.silent);

      _startTimers();
      return outcome;
    } on ApiException catch (error) {
      _error = error.message;
      return null;
    } on NetworkException catch (error) {
      _error = error.message;
      return null;
    } finally {
      _triggering = false;
      notifyListeners();
    }
  }

  /// Stand down the emergency.
  ///
  /// Rethrows an [ApiException] carrying `invalid_safety_pin` so the screen can keep the
  /// incident visibly running. A wrong PIN is not an error state for the emergency — help is
  /// still coming, and the person typing may not be the person who raised it.
  Future<bool> cancel({required String pin, String? reason}) async {
    final incident = _active;
    if (incident == null) return false;

    _cancelling = true;
    notifyListeners();
    try {
      _active = await _api.cancelIncident(
        incidentId: incident.id,
        pin: pin,
        reason: reason,
      );
      _stopTimers();
      return true;
    } finally {
      _cancelling = false;
      notifyListeners();
    }
  }

  void _startTimers() {
    _pollTimer ??= Timer.periodic(AppConfig.incidentPollInterval, (_) => _refresh());
    _locationTimer ??= Timer.periodic(AppConfig.locationReportInterval, (_) => _reportLocation());
    // Send one immediately rather than waiting a full interval: the first minutes matter most.
    _reportLocation();
  }

  void _stopTimers() {
    _pollTimer?.cancel();
    _pollTimer = null;
    _locationTimer?.cancel();
    _locationTimer = null;
  }

  Future<void> _refresh() async {
    final incident = _active;
    if (incident == null) return;

    try {
      final updated = await _api.incident(incident.id);
      _active = updated;
      _error = null;
      if (!updated.isOpen) _stopTimers();
      notifyListeners();
    } on ApiException catch (error) {
      // 404 means it is genuinely gone; anything else is probably transient and not worth
      // tearing the screen down for.
      if (error.isNotFound) {
        _active = null;
        _stopTimers();
        notifyListeners();
      }
    } on NetworkException {
      // Coverage comes and goes. Keep the last known state on screen and try again on the
      // next tick rather than blanking it.
    }
  }

  Future<void> _reportLocation() async {
    final incident = _active;
    if (incident == null || !incident.isOpen) return;

    final fix = await _location.currentPosition(
      timeout: const Duration(seconds: 12),
    );
    _locationAvailability = fix.availability;
    final position = fix.position;
    if (position == null) return;

    try {
      _active = await _api.reportLocation(
        incidentId: incident.id,
        latitude: position.latitude,
        longitude: position.longitude,
        accuracy: position.accuracy,
        // The device timestamp, so a fix taken in a dead spot keeps its real time.
        recordedAt: position.timestamp,
      );
      notifyListeners();
    } on ApiException catch (error) {
      // The incident closed while this was in flight. Expected, not an error.
      if (error.isConflict) {
        await loadActive();
      }
    } on NetworkException {
      // Dropped. The next tick will carry a newer position anyway.
    }
  }

  /// Clears the post-trigger banner once the reporter has seen it.
  void dismissSummary() {
    _lastSummary = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _stopTimers();
    super.dispose();
  }
}
