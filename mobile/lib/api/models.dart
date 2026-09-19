/// Dart mirrors of the API payloads.
///
/// Hand-written rather than generated. There are a dozen of them, they change with the backend
/// in the same commit, and a code-generation step is one more thing that can break at the wrong
/// moment in a five-day build.
///
/// Every `fromJson` tolerates a missing or null field where the API can legitimately omit one:
/// the service is configured to leave null properties out of its responses entirely, so
/// `json['language']` is absent rather than null when a contact has no override.
library;

DateTime? _parseDate(dynamic value) {
  if (value == null) return null;
  return DateTime.tryParse(value as String)?.toLocal();
}

double? _parseDouble(dynamic value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  return double.tryParse(value.toString());
}

int _parseInt(dynamic value, {int fallback = 0}) {
  if (value == null) return fallback;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString()) ?? fallback;
}

/// The signed-in account.
class AppUser {
  const AppUser({
    required this.id,
    required this.fullName,
    required this.phone,
    required this.role,
    required this.preferredLanguage,
    required this.safetyPinSet,
    required this.phoneVerified,
    this.email,
    this.emergencyNote,
    this.photoUrl,
  });

  final String id;
  final String fullName;
  final String phone;
  final String role;
  final String preferredLanguage;
  final bool safetyPinSet;
  final bool phoneVerified;
  final String? email;
  final String? emergencyNote;
  final String? photoUrl;

  bool get isResponder => role == 'RESPONDER';
  bool get isAdmin => role == 'ADMIN';

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
        id: json['id'] as String,
        fullName: json['fullName'] as String,
        phone: json['phone'] as String,
        role: json['role'] as String? ?? 'USER',
        preferredLanguage: json['preferredLanguage'] as String? ?? 'EN',
        safetyPinSet: json['safetyPinSet'] as bool? ?? false,
        phoneVerified: json['phoneVerified'] as bool? ?? false,
        email: json['email'] as String?,
        emergencyNote: json['emergencyNote'] as String?,
        photoUrl: json['photoUrl'] as String?,
      );
}

class AuthResult {
  const AuthResult({
    required this.user,
    required this.accessToken,
    required this.refreshToken,
    this.simulatedVerificationCode,
  });

  final AppUser user;
  final String accessToken;
  final String refreshToken;

  /// Only present while no SMS gateway is connected. Surfaced in the UI as such.
  final String? simulatedVerificationCode;

  factory AuthResult.fromJson(Map<String, dynamic> json) => AuthResult(
        user: AppUser.fromJson(json['user'] as Map<String, dynamic>),
        accessToken: json['accessToken'] as String,
        refreshToken: json['refreshToken'] as String,
        simulatedVerificationCode: json['simulatedVerificationCode'] as String?,
      );
}

class TrustedContact {
  const TrustedContact({
    required this.id,
    required this.name,
    required this.phone,
    required this.priority,
    required this.notificationEnabled,
    required this.hasAccount,
    this.relationship,
    this.language,
  });

  final String id;
  final String name;
  final String phone;
  final int priority;
  final bool notificationEnabled;

  /// True when the number belongs to an account, so they also get in-app alerts.
  final bool hasAccount;
  final String? relationship;

  /// Null means they are alerted in the language of whoever raised the alarm.
  final String? language;

  factory TrustedContact.fromJson(Map<String, dynamic> json) => TrustedContact(
        id: json['id'] as String,
        name: json['name'] as String,
        phone: json['phone'] as String,
        priority: _parseInt(json['priority'], fallback: 1),
        notificationEnabled: json['notificationEnabled'] as bool? ?? true,
        hasAccount: json['hasAccount'] as bool? ?? false,
        relationship: json['relationship'] as String?,
        language: json['language'] as String?,
      );
}

/// A position, carrying its own age so the UI never shows a stale pin as current.
class LocationPoint {
  const LocationPoint({
    required this.latitude,
    required this.longitude,
    required this.recordedAt,
    required this.ageSeconds,
    required this.stale,
    required this.mapsLink,
    this.accuracy,
  });

  final double latitude;
  final double longitude;
  final DateTime recordedAt;
  final int ageSeconds;

  /// Server's verdict on whether this should be shown as a last-known rather than live position.
  final bool stale;
  final String mapsLink;
  final double? accuracy;

  factory LocationPoint.fromJson(Map<String, dynamic> json) => LocationPoint(
        latitude: _parseDouble(json['latitude'])!,
        longitude: _parseDouble(json['longitude'])!,
        recordedAt: _parseDate(json['recordedAt']) ?? DateTime.now(),
        ageSeconds: _parseInt(json['ageSeconds']),
        stale: json['stale'] as bool? ?? false,
        mapsLink: json['mapsLink'] as String? ?? '',
        accuracy: _parseDouble(json['accuracy']),
      );
}

class PersonSummary {
  const PersonSummary({
    required this.id,
    required this.fullName,
    required this.phone,
    this.emergencyNote,
    this.photoUrl,
  });

  final String id;
  final String fullName;
  final String phone;
  final String? emergencyNote;
  final String? photoUrl;

  factory PersonSummary.fromJson(Map<String, dynamic> json) => PersonSummary(
        id: json['id'] as String,
        fullName: json['fullName'] as String,
        phone: json['phone'] as String? ?? '',
        emergencyNote: json['emergencyNote'] as String?,
        photoUrl: json['photoUrl'] as String?,
      );
}

class ResponderSummary {
  const ResponderSummary({
    required this.id,
    required this.organization,
    required this.verificationStatus,
    required this.availabilityStatus,
    this.fullName,
    this.phone,
    this.distanceKm,
  });

  final String id;
  final String organization;
  final String verificationStatus;
  final String availabilityStatus;
  final String? fullName;
  final String? phone;
  final double? distanceKm;

  bool get isVerified => verificationStatus == 'VERIFIED';
  bool get isAvailable => availabilityStatus == 'AVAILABLE';

  factory ResponderSummary.fromJson(Map<String, dynamic> json) => ResponderSummary(
        id: json['id'] as String,
        organization: json['organization'] as String? ?? '',
        verificationStatus: json['verificationStatus'] as String? ?? 'PENDING',
        availabilityStatus: json['availabilityStatus'] as String? ?? 'OFFLINE',
        fullName: json['fullName'] as String?,
        phone: json['phone'] as String?,
        distanceKm: _parseDouble(json['distanceKm']),
      );
}

class TimelineEntry {
  const TimelineEntry({
    required this.id,
    required this.eventType,
    required this.occurredAt,
    this.actorLabel,
    this.notes,
  });

  final String id;
  final String eventType;
  final DateTime occurredAt;
  final String? actorLabel;
  final String? notes;

  factory TimelineEntry.fromJson(Map<String, dynamic> json) => TimelineEntry(
        id: json['id'] as String,
        eventType: json['eventType'] as String,
        occurredAt: _parseDate(json['occurredAt']) ?? DateTime.now(),
        actorLabel: json['actorLabel'] as String?,
        notes: json['notes'] as String?,
      );
}

class AlertDelivery {
  const AlertDelivery({
    required this.id,
    required this.type,
    required this.channel,
    required this.deliveryStatus,
    required this.title,
    required this.body,
    required this.language,
    required this.createdAt,
    required this.simulated,
    this.incidentId,
    this.recipientLabel,
    this.recipientPhone,
    this.failureReason,
    this.acknowledgedAt,
  });

  final String id;
  final String type;
  final String channel;
  final String deliveryStatus;
  final String title;
  final String body;
  final String language;
  final DateTime createdAt;

  /// True when nothing actually left the building. Shown rather than hidden.
  final bool simulated;
  final String? incidentId;
  final String? recipientLabel;
  final String? recipientPhone;
  final String? failureReason;
  final DateTime? acknowledgedAt;

  bool get acknowledged => acknowledgedAt != null;

  factory AlertDelivery.fromJson(Map<String, dynamic> json) => AlertDelivery(
        id: json['id'] as String,
        type: json['type'] as String,
        channel: json['channel'] as String,
        deliveryStatus: json['deliveryStatus'] as String,
        title: json['title'] as String? ?? '',
        body: json['body'] as String? ?? '',
        language: json['language'] as String? ?? 'EN',
        createdAt: _parseDate(json['createdAt']) ?? DateTime.now(),
        simulated: json['simulated'] as bool? ?? false,
        incidentId: json['incidentId'] as String?,
        recipientLabel: json['recipientLabel'] as String?,
        recipientPhone: json['recipientPhone'] as String?,
        failureReason: json['failureReason'] as String?,
        acknowledgedAt: _parseDate(json['acknowledgedAt']),
      );
}

class Incident {
  const Incident({
    required this.id,
    required this.status,
    required this.silent,
    required this.reporter,
    required this.triggeredAt,
    required this.elapsedSeconds,
    required this.viewerRelationship,
    required this.canViewLocation,
    required this.locationPointCount,
    this.note,
    this.assignedResponder,
    this.location,
    this.locationTrail,
    this.acceptedAt,
    this.arrivedAt,
    this.closedAt,
    this.cancelReason,
    this.resolutionNote,
    this.timeline,
    this.notifications,
  });

  final String id;
  final String status;
  final bool silent;
  final PersonSummary reporter;
  final DateTime triggeredAt;
  final int elapsedSeconds;
  final String viewerRelationship;

  /// False once a closed incident stops sharing position with this viewer.
  final bool canViewLocation;
  final int locationPointCount;
  final String? note;
  final ResponderSummary? assignedResponder;
  final LocationPoint? location;
  final List<LocationPoint>? locationTrail;
  final DateTime? acceptedAt;
  final DateTime? arrivedAt;
  final DateTime? closedAt;
  final String? cancelReason;
  final String? resolutionNote;
  final List<TimelineEntry>? timeline;
  final List<AlertDelivery>? notifications;

  static const openStatuses = {'TRIGGERED', 'ACCEPTED', 'RESPONDING', 'ARRIVED'};

  bool get isOpen => openStatuses.contains(status);
  bool get isClaimed => assignedResponder != null;

  factory Incident.fromJson(Map<String, dynamic> json) => Incident(
        id: json['id'] as String,
        status: json['status'] as String,
        silent: json['silent'] as bool? ?? true,
        reporter: PersonSummary.fromJson(json['reporter'] as Map<String, dynamic>),
        triggeredAt: _parseDate(json['triggeredAt']) ?? DateTime.now(),
        elapsedSeconds: _parseInt(json['elapsedSeconds']),
        viewerRelationship: json['viewerRelationship'] as String? ?? 'REPORTER',
        canViewLocation: json['canViewLocation'] as bool? ?? false,
        locationPointCount: _parseInt(json['locationPointCount']),
        note: json['note'] as String?,
        assignedResponder: json['assignedResponder'] == null
            ? null
            : ResponderSummary.fromJson(json['assignedResponder'] as Map<String, dynamic>),
        location: json['location'] == null
            ? null
            : LocationPoint.fromJson(json['location'] as Map<String, dynamic>),
        locationTrail: (json['locationTrail'] as List<dynamic>?)
            ?.map((e) => LocationPoint.fromJson(e as Map<String, dynamic>))
            .toList(),
        acceptedAt: _parseDate(json['acceptedAt']),
        arrivedAt: _parseDate(json['arrivedAt']),
        closedAt: _parseDate(json['closedAt']),
        cancelReason: json['cancelReason'] as String?,
        resolutionNote: json['resolutionNote'] as String?,
        timeline: (json['timeline'] as List<dynamic>?)
            ?.map((e) => TimelineEntry.fromJson(e as Map<String, dynamic>))
            .toList(),
        notifications: (json['notifications'] as List<dynamic>?)
            ?.map((e) => AlertDelivery.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// What comes back from holding the button.
class TriggerOutcome {
  const TriggerOutcome({
    required this.incident,
    required this.alreadyActive,
    required this.notifications,
    required this.summary,
  });

  final Incident incident;

  /// True when an emergency was already running and this press joined it.
  final bool alreadyActive;
  final List<AlertDelivery> notifications;

  /// Plain-language line from the server, in the reporter's own language.
  final String summary;

  factory TriggerOutcome.fromJson(Map<String, dynamic> json) => TriggerOutcome(
        incident: Incident.fromJson(json['incident'] as Map<String, dynamic>),
        alreadyActive: json['alreadyActive'] as bool? ?? false,
        notifications: (json['notifications'] as List<dynamic>? ?? [])
            .map((e) => AlertDelivery.fromJson(e as Map<String, dynamic>))
            .toList(),
        summary: json['summary'] as String? ?? '',
      );
}

/// An alert as it appears in a responder's list.
class ResponderAlert {
  const ResponderAlert({
    required this.incidentId,
    required this.status,
    required this.reporter,
    required this.triggeredAt,
    required this.waitingSeconds,
    required this.isMine,
    this.location,
    this.distanceKm,
    this.note,
  });

  final String incidentId;
  final String status;
  final PersonSummary reporter;
  final DateTime triggeredAt;

  /// How long nobody has taken it. The list is sorted by this, not by distance.
  final int waitingSeconds;
  final bool isMine;
  final LocationPoint? location;
  final double? distanceKm;
  final String? note;

  factory ResponderAlert.fromJson(Map<String, dynamic> json) => ResponderAlert(
        incidentId: json['incidentId'] as String,
        status: json['status'] as String,
        reporter: PersonSummary.fromJson(json['reporter'] as Map<String, dynamic>),
        triggeredAt: _parseDate(json['triggeredAt']) ?? DateTime.now(),
        waitingSeconds: _parseInt(json['waitingSeconds']),
        isMine: json['isMine'] as bool? ?? false,
        location: json['location'] == null
            ? null
            : LocationPoint.fromJson(json['location'] as Map<String, dynamic>),
        distanceKm: _parseDouble(json['distanceKm']),
        note: json['note'] as String?,
      );
}

class LanguageOption {
  const LanguageOption({required this.code, required this.name, required this.nativeName});

  final String code;
  final String name;
  final String nativeName;

  factory LanguageOption.fromJson(Map<String, dynamic> json) => LanguageOption(
        code: json['code'] as String,
        name: json['name'] as String,
        nativeName: json['nativeName'] as String,
      );
}
