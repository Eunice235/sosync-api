import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';

/// Why a position is not available, so the UI can say something useful instead of failing
/// silently.
enum LocationAvailability {
  ok,

  /// The user has not been asked yet, or said no this once.
  denied,

  /// Said no permanently. Only the system settings can undo it, so the UI has to send them there.
  deniedForever,

  /// Location is switched off on the device entirely.
  serviceDisabled,

  /// Permission is fine but no fix arrived in time — indoors, in a lift, in a vehicle.
  unavailable,
}

class LocationResult {
  const LocationResult(this.availability, [this.position]);

  final LocationAvailability availability;
  final Position? position;

  bool get hasPosition => position != null;
}

/// GPS access.
///
/// One rule shapes this whole class: **a missing fix must never stop an alert.** Every method
/// resolves to a [LocationResult] rather than throwing, and the SOS path treats "no location"
/// as a degraded success. Reaching people late with a position beats reaching them never
/// because the phone was in a bag.
class LocationService {
  /// Asks for permission, and reports exactly why if it cannot be had.
  Future<LocationAvailability> ensurePermission() async {
    if (!await Geolocator.isLocationServiceEnabled()) {
      return LocationAvailability.serviceDisabled;
    }

    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }

    return switch (permission) {
      LocationPermission.always || LocationPermission.whileInUse => LocationAvailability.ok,
      LocationPermission.deniedForever => LocationAvailability.deniedForever,
      _ => LocationAvailability.denied,
    };
  }

  /// A fix for the moment the button is released.
  ///
  /// The timeout is short on purpose. Somebody holding an SOS button cannot wait thirty seconds
  /// for a precise position, so this takes the best answer available quickly and lets the
  /// incident fill in a better one from the background updates that follow.
  Future<LocationResult> currentPosition({
    Duration timeout = const Duration(seconds: 8),
    LocationAccuracy accuracy = LocationAccuracy.high,
  }) async {
    final availability = await ensurePermission();
    if (availability != LocationAvailability.ok) {
      return LocationResult(availability);
    }

    try {
      final position = await Geolocator.getCurrentPosition(
        locationSettings: LocationSettings(accuracy: accuracy, timeLimit: timeout),
      );
      return LocationResult(LocationAvailability.ok, position);
    } catch (error) {
      debugPrint('No fix within $timeout: $error');

      // Fall back to whatever the device last knew. An old position is better than none for
      // deciding which responders are nearby, and it is labelled with its age everywhere it
      // is shown.
      try {
        final last = await Geolocator.getLastKnownPosition();
        if (last != null) return LocationResult(LocationAvailability.ok, last);
      } catch (_) {
        // Not supported on web; ignore.
      }
      return const LocationResult(LocationAvailability.unavailable);
    }
  }

  /// Opens the system settings page, for a permission the app can no longer ask about.
  Future<void> openSettings(LocationAvailability reason) async {
    if (reason == LocationAvailability.serviceDisabled) {
      await Geolocator.openLocationSettings();
    } else {
      await Geolocator.openAppSettings();
    }
  }
}
