import 'package:flutter/widgets.dart';

/// Every piece of UI wording, in one place.
///
/// English only. The same shape as `AlertStrings` on the server: an abstract class with one
/// implementation per language, so a second language is a new class that will not compile until
/// every string exists, rather than a hunt through the screens for hard-coded text. That is
/// worth keeping with a single entry in it — it is also what makes it possible to check, with a
/// grep, that no screen has quietly grown its own literal.
///
/// The division of labour with the server is deliberate. The server localises what it delivers
/// to people itself (SMS, push, in-app alert bodies), because it is the thing sending them.
/// Everything the app renders is worded here, from facts the API supplies: `stale` and
/// `ageSeconds` rather than a ready-made "last seen 4 minutes ago".
abstract class Strings {
  const Strings();

  String get languageCode;

  static const Map<String, Strings> _catalogue = {
    'EN': EnStrings(),
  };

  static Strings of(BuildContext context) => LocalisedApp.of(context);

  static Strings forCode(String? code) =>
      _catalogue[(code ?? 'EN').toUpperCase()] ?? const EnStrings();

  // ── Shell ──────────────────────────────────────────────────────────────────────
  String get appName;
  String get tabHome;
  String get tabContacts;
  String get tabAlerts;
  String get tabNotifications;
  String get tabProfile;
  String get tabResponderAlerts;

  // ── Auth ───────────────────────────────────────────────────────────────────────
  String get signIn;
  String get signOut;
  String get createAccount;
  String get phoneOrEmail;
  String get password;
  String get fullName;
  String get phoneNumber;
  String get emailOptional;
  String get registerAsResponder;
  String get organisation;
  String get haveAnAccount;
  String get needAnAccount;

  // ── SOS ────────────────────────────────────────────────────────────────────────
  String get holdToSendSos;
  String get holdingRelease;
  String get sendingAlert;
  String get sosHint;
  String get silentMode;
  String get silentModeHint;
  String get whatIsHappening;
  String get gettingLocation;
  String get noLocationYet;
  String get locationBlocked;
  String get locationBlockedHint;
  String get enableLocation;

  // ── Active incident ────────────────────────────────────────────────────────────
  String get emergencyActive;
  String get helpRequested;
  String get whoWasTold;
  String get noOneReached;
  String get lastKnownPosition;
  String get liveNow;
  String get openInMaps;
  String get standDown;
  String get standDownTitle;
  String get enterSafetyPin;
  String get safetyPinWrong;
  String get cancelReasonOptional;
  String get confirmStandDown;
  String get timeline;
  String get elapsed;
  String get noResponderYet;
  String get respondingNow;

  // ── Statuses ───────────────────────────────────────────────────────────────────
  String status(String code);
  String eventType(String code);
  String relationshipLabel(String code);

  // ── Contacts ───────────────────────────────────────────────────────────────────
  String get trustedContacts;
  String get addContact;
  String get noContactsYet;
  String get noContactsWarning;
  String get contactName;
  String get relationshipOptional;
  String get smsOnly;
  String get hasAppAccount;
  String get removeContact;

  // ── Alerts inbox ───────────────────────────────────────────────────────────────
  String get peopleWatchingOver;
  String get noActiveAlerts;
  String get noActiveAlertsHint;
  String get needsHelp;
  String get acknowledge;
  String get acknowledged;
  String get callThem;

  // ── Responder ──────────────────────────────────────────────────────────────────
  String get onShift;
  String get offShift;
  String get awaitingVerification;
  String get awaitingVerificationHint;
  String get noOpenAlerts;
  String get noOpenAlertsHint;
  String get waitingFor;
  String get takeThisCall;
  String get someoneElseTookIt;
  String get onMyWay;
  String get iHaveArrived;
  String get markResolved;
  String get resolutionNote;
  String get distanceAway;
  String get medicalNote;

  // ── Alarm ──────────────────────────────────────────────────────────────────────
  String get alarmSounding;
  String get silenceAlarm;
  String get alarmMuted;
  String get unmuteAlarm;
  String get alarmMutedHint;

  // ── Notifications ──────────────────────────────────────────────────────────────
  String get notifications;
  String get noNotifications;
  String get simulatedDelivery;
  String get simulatedDeliveryHint;

  // ── Profile ────────────────────────────────────────────────────────────────────
  String get profile;
  String get safetyPin;
  String get safetyPinSet;
  String get safetyPinNotSet;
  String get safetyPinHint;
  String get setSafetyPin;
  String get newPin;
  String get confirmWithPassword;
  String get emergencyNote;
  String get emergencyNoteHint;
  String get serverAddress;
  String get serverAddressHint;
  String get prototypeNotice;

  // ── Generic ────────────────────────────────────────────────────────────────────
  String get includeClosed;
  String get openOnly;
  String get priorityContact;
  String get priorityContactHint;
  String get priorityBadge;
  String get changeAction;
  String get languageChangeHint;
  String get passwordTooShort;
  String get save;
  String get saved;
  String get cancel;
  String get close;
  String get retry;
  String get loading;
  String get somethingWentWrong;
  String secondsAgo(int seconds);
  String minutesAgo(int minutes);
  String get justNow;

  /// "4 min 12 s", for the elapsed clock on an open emergency.
  String duration(int totalSeconds) {
    final minutes = totalSeconds ~/ 60;
    final seconds = totalSeconds % 60;
    return minutes > 0 ? '$minutes min ${seconds}s' : '${seconds}s';
  }

  /// Turns an age in seconds into "just now" / "12s ago" / "4 min ago".
  String age(int seconds) {
    if (seconds < 5) return justNow;
    if (seconds < 60) return secondsAgo(seconds);
    return minutesAgo(seconds ~/ 60);
  }
}

class EnStrings extends Strings {
  const EnStrings();

  @override
  String get languageCode => 'EN';

  @override
  String get appName => 'SOSync';
  @override
  String get tabHome => 'SOS';
  @override
  String get tabContacts => 'Contacts';
  @override
  String get tabAlerts => 'Watching';
  @override
  String get tabNotifications => 'Alerts';
  @override
  String get tabProfile => 'Profile';
  @override
  String get tabResponderAlerts => 'Calls';

  @override
  String get signIn => 'Sign in';
  @override
  String get signOut => 'Sign out';
  @override
  String get createAccount => 'Create account';
  @override
  String get phoneOrEmail => 'Phone number or email';
  @override
  String get password => 'Password';
  @override
  String get fullName => 'Full name';
  @override
  String get phoneNumber => 'Phone number';
  @override
  String get emailOptional => 'Email (optional)';
  @override
  String get registerAsResponder => 'I am a security responder';
  @override
  String get organisation => 'Organisation';
  @override
  String get haveAnAccount => 'Already have an account? Sign in';
  @override
  String get needAnAccount => 'New here? Create an account';

  @override
  String get holdToSendSos => 'HOLD FOR SOS';
  @override
  String get holdingRelease => 'Keep holding…';
  @override
  String get sendingAlert => 'Sending alert…';
  @override
  String get sosHint => 'Hold the button for 3 seconds. Your phone stays silent.';
  @override
  String get silentMode => 'Silent';
  @override
  String get silentModeHint => 'No sound or visible alarm on this phone.';
  @override
  String get whatIsHappening => 'What is happening? (optional)';
  @override
  String get gettingLocation => 'Finding your location…';
  @override
  String get noLocationYet => 'No location yet — the alert will still be sent';
  @override
  String get locationBlocked => 'Location is turned off';
  @override
  String get locationBlockedHint =>
      'The alert still works without it, but nobody will know where to come.';
  @override
  String get enableLocation => 'Turn on location';

  @override
  String get emergencyActive => 'EMERGENCY ACTIVE';
  @override
  String get helpRequested => 'Your people have been told';
  @override
  String get whoWasTold => 'Who was told';
  @override
  String get noOneReached => 'Nobody could be reached';
  @override
  String get lastKnownPosition => 'Last known position';
  @override
  String get liveNow => 'Live';
  @override
  String get openInMaps => 'Open in Maps';
  @override
  String get standDown => 'I am safe';
  @override
  String get standDownTitle => 'Stand down the alert';
  @override
  String get enterSafetyPin => 'Enter your safety PIN';
  @override
  String get safetyPinWrong => 'That PIN is not right. Help is still on the way.';
  @override
  String get cancelReasonOptional => 'Reason (optional)';
  @override
  String get confirmStandDown => 'Stand down';
  @override
  String get timeline => 'What happened';
  @override
  String get elapsed => 'Open for';
  @override
  String get noResponderYet => 'Waiting for a responder to take this';
  @override
  String get respondingNow => 'Help is on the way';

  @override
  String status(String code) => switch (code) {
        'TRIGGERED' => 'Alert raised',
        'ACCEPTED' => 'Responder assigned',
        'RESPONDING' => 'On the way',
        'ARRIVED' => 'Arrived',
        'RESOLVED' => 'Resolved',
        'CANCELLED' => 'Stood down',
        _ => code,
      };

  @override
  String eventType(String code) => switch (code) {
        'TRIGGERED' => 'SOS raised',
        'NOTIFIED' => 'People alerted',
        'ACKNOWLEDGED' => 'Alert seen',
        'LOCATION_UPDATED' => 'Location updated',
        'ACCEPTED' => 'Responder accepted',
        'RESPONDING' => 'Responder on the way',
        'ARRIVED' => 'Responder arrived',
        'RESOLVED' => 'Resolved',
        'CANCELLED' => 'Stood down',
        _ => code,
      };

  @override
  String relationshipLabel(String code) => switch (code) {
        'REPORTER' => 'You raised this',
        'TRUSTED_CONTACT' => 'You are a trusted contact',
        'FAMILY_MEMBER' => 'Family member',
        'ASSIGNED_RESPONDER' => 'You are responding',
        'AVAILABLE_RESPONDER' => 'Unclaimed call',
        'ADMIN' => 'Administrator',
        _ => code,
      };

  @override
  String get trustedContacts => 'Trusted contacts';
  @override
  String get addContact => 'Add contact';
  @override
  String get noContactsYet => 'No trusted contacts yet';
  @override
  String get noContactsWarning =>
      'Add at least one. Without a contact, an SOS reaches nobody.';
  @override
  String get contactName => 'Name';
  @override
  String get relationshipOptional => 'Relationship (optional)';
  @override
  String get smsOnly => 'SMS only';
  @override
  String get hasAppAccount => 'Has SOSync';
  @override
  String get removeContact => 'Remove';

  @override
  String get peopleWatchingOver => 'People who named you';
  @override
  String get noActiveAlerts => 'Nobody needs help';
  @override
  String get noActiveAlertsHint =>
      'If someone who named you as a trusted contact raises an alert, it appears here.';
  @override
  String get needsHelp => 'needs help';
  @override
  String get acknowledge => 'I have seen this';
  @override
  String get acknowledged => 'Seen';
  @override
  String get callThem => 'Call';

  @override
  String get onShift => 'On shift';
  @override
  String get offShift => 'Off shift';
  @override
  String get awaitingVerification => 'Awaiting verification';
  @override
  String get awaitingVerificationHint =>
      'An administrator has to verify your account before you receive calls.';
  @override
  String get noOpenAlerts => 'No open calls';
  @override
  String get noOpenAlertsHint => 'Go on shift to start receiving nearby emergencies.';
  @override
  String get waitingFor => 'Waiting';
  @override
  String get takeThisCall => 'Take this call';
  @override
  String get someoneElseTookIt => 'Another responder already took it';
  @override
  String get onMyWay => 'On my way';
  @override
  String get iHaveArrived => 'I have arrived';
  @override
  String get markResolved => 'Mark resolved';
  @override
  String get resolutionNote => 'What happened? (optional)';
  @override
  String get distanceAway => 'away';
  @override
  String get medicalNote => 'Note';

  @override
  String get alarmSounding => 'Alarm sounding';
  @override
  String get silenceAlarm => 'Silence';
  @override
  String get alarmMuted => 'Alarm muted';
  @override
  String get unmuteAlarm => 'Turn alarm on';
  @override
  String get alarmMutedHint =>
      'You will still see alerts on screen, but this phone will not make a sound.';

  @override
  String get notifications => 'Alerts';
  @override
  String get noNotifications => 'Nothing yet';
  @override
  String get simulatedDelivery => 'Simulated';
  @override
  String get simulatedDeliveryHint =>
      'No SMS or push provider is connected in this prototype, so nothing was actually sent. '
      'This is what the recipient would have received.';

  @override
  String get profile => 'Profile';
  @override
  String get safetyPin => 'Safety PIN';
  @override
  String get safetyPinSet => 'PIN set';
  @override
  String get safetyPinNotSet => 'No PIN set';
  @override
  String get safetyPinHint =>
      'Needed to stand down an emergency. Without it, anyone holding your phone could '
      'call off the response.';
  @override
  String get setSafetyPin => 'Set PIN';
  @override
  String get newPin => 'New PIN (4–8 digits)';
  @override
  String get confirmWithPassword => 'Your account password';
  @override
  String get emergencyNote => 'Note for responders';
  @override
  String get emergencyNoteHint =>
      'Medical conditions, what you look like, who to expect with you.';
  @override
  String get serverAddress => 'Server address';
  @override
  String get serverAddressHint =>
      'Change this to demo on a real phone: use your computer address on the same network.';
  @override
  String get prototypeNotice =>
      'Prototype. Not connected to any emergency service. Responders are verified community '
      'or private security, not police.';

  @override
  String get save => 'Save';
  @override
  String get saved => 'Saved';
  @override
  String get cancel => 'Cancel';
  @override
  String get close => 'Close';
  @override
  String get retry => 'Try again';
  @override
  String get loading => 'Loading…';
  @override
  String get somethingWentWrong => 'Something went wrong';
  @override
  String get includeClosed => 'Include closed';
  @override
  String get openOnly => 'Open only';
  @override
  String get priorityContact => 'Priority contact';
  @override
  String get priorityContactHint => 'Alerted first, and always by SMS.';
  @override
  String get priorityBadge => 'Priority 1';
  @override
  String get changeAction => 'Change';
  @override
  String get languageChangeHint => 'Changes the app and the alerts you receive.';
  @override
  String get passwordTooShort => 'At least 8 characters';
  @override
  String secondsAgo(int seconds) => '${seconds}s ago';
  @override
  String minutesAgo(int minutes) => '$minutes min ago';
  @override
  String get justNow => 'just now';
}

/// Makes the active [Strings] available to the widget tree.
class LocalisedApp extends InheritedWidget {
  const LocalisedApp({super.key, required this.strings, required super.child});

  final Strings strings;

  static Strings of(BuildContext context) {
    final widget = context.dependOnInheritedWidgetOfExactType<LocalisedApp>();
    return widget?.strings ?? const EnStrings();
  }

  @override
  bool updateShouldNotify(LocalisedApp oldWidget) =>
      oldWidget.strings.languageCode != strings.languageCode;
}
