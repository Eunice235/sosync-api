import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:vibration/vibration.dart';

/// Sound and vibration.
///
/// The asymmetry here is the point of the product, so it is enforced in one place rather than
/// left to each screen to remember:
///
/// - **The reporter's device stays silent.** It vibrates to confirm the alert went, and makes
///   no sound on a silent incident. An alarm going off in the reporter's own pocket would
///   announce to the person frightening them that they have just called for help.
/// - **The receiver's device is loud.** A trusted contact or a responder needs to be woken by
///   this, from a pocket, across a room, over traffic. It loops until somebody silences it.
///
/// **Nothing here is allowed to break the app.** Audio and vibration are the first things to be
/// unavailable on a given device, browser or emulator, and an emergency tool that will not
/// start because a speaker is missing is worse than one that runs quietly. So the players are
/// created lazily inside a guard rather than in a field initializer — an exception while
/// building an `AudioPlayer` at construction time would take the whole widget tree with it, and
/// the failure looks like a blank screen with nothing in the console.
class AlarmService {
  static const _alarmAsset = 'audio/emergency_alarm.wav';
  static const _sentAsset = 'audio/alert_sent.wav';

  AudioPlayer? _alarmPlayer;
  AudioPlayer? _cuePlayer;
  bool _audioBroken = false;

  bool _alarmPlaying = false;
  bool _muted = false;
  bool? _canVibrate;

  /// Emergencies the alarm has already sounded for.
  ///
  /// Held here, not on the screen, because the screens are rebuilt every time their tab is
  /// opened. Kept on a screen, this set started empty on each visit and the alarm rang again
  /// for an emergency the person had already silenced.
  final Set<String> announcedIncidents = <String>{};

  bool get isAlarmPlaying => _alarmPlaying;

  /// Muted for this session. Silencing one alarm does not silence the next one: a responder
  /// who quiets a call they are already handling still needs to hear the next emergency.
  bool get isMuted => _muted;

  /// True once audio has failed, so the UI can stop promising a sound it cannot make.
  bool get isAudioAvailable => !_audioBroken;

  AudioPlayer? _player({required bool forAlarm}) {
    if (_audioBroken) return null;
    try {
      if (forAlarm) return _alarmPlayer ??= AudioPlayer();
      return _cuePlayer ??= AudioPlayer();
    } catch (error) {
      debugPrint('Audio unavailable on this platform: $error');
      _audioBroken = true;
      return null;
    }
  }

  Future<bool> _hasVibrator() async {
    if (kIsWeb) return false;
    try {
      _canVibrate ??= await Vibration.hasVibrator();
    } catch (error) {
      debugPrint('Vibration unavailable: $error');
      _canVibrate = false;
    }
    return _canVibrate ?? false;
  }

  // ── Reporter side ────────────────────────────────────────────────────────────────

  /// A light tick as the hold begins, so the press is felt as registered.
  Future<void> holdStarted() async {
    try {
      await HapticFeedback.selectionClick();
    } catch (_) {
      // Not worth reporting.
    }
  }

  /// Marks the moment the ring closes, a beat before the alert actually goes.
  Future<void> holdCompleted() async {
    try {
      if (await _hasVibrator()) {
        await Vibration.vibrate(duration: 120, amplitude: 200);
      } else {
        await HapticFeedback.mediumImpact();
      }
    } catch (_) {
      // Not worth reporting.
    }
  }

  /// The confirmation the reporter feels once the alert is away.
  ///
  /// Three firm pulses. Long enough to be unmistakable through a coat pocket, short enough not
  /// to draw attention, and distinct from any notification buzz the phone makes normally so it
  /// cannot be mistaken for an ordinary message arriving.
  ///
  /// [silent] is the incident's silent flag. When false the reporter has explicitly said noise
  /// is safe, so a short confirmation chirp plays too — never the alarm, which is for people
  /// who are somewhere else.
  Future<void> confirmAlertSent({required bool silent}) async {
    try {
      if (await _hasVibrator()) {
        await Vibration.vibrate(
          pattern: const [0, 260, 120, 260, 120, 420],
          intensities: const [0, 255, 0, 255, 0, 255],
        );
      } else {
        // No vibration motor, or a browser. Fall back to platform haptics, which at least
        // produce something on iOS and on desktop trackpads.
        await HapticFeedback.heavyImpact();
      }
    } catch (error) {
      debugPrint('Confirmation vibration unavailable: $error');
    }

    if (silent || _muted) return;
    try {
      await _player(forAlarm: false)?.play(AssetSource(_sentAsset), volume: 0.6);
    } catch (error) {
      debugPrint('Confirmation sound unavailable: $error');
    }
  }

  // ── Receiver side ────────────────────────────────────────────────────────────────

  /// Start the alarm for an incoming emergency. Loops until [stopAlarm].
  ///
  /// Also vibrates in a long, insistent pattern, because the phone this needs to reach may be
  /// face-down on a table or in a bag with the ringer off.
  Future<void> startAlarm() async {
    if (_alarmPlaying || _muted) return;
    _alarmPlaying = true;

    final player = _player(forAlarm: true);
    if (player != null) {
      try {
        // Declared as an alarm rather than media so the platform treats it accordingly: it
        // ducks other audio and, on Android, is routed like an alarm instead of a notification
        // blip. Without this the most important sound the app makes is the easiest to miss.
        await player.setAudioContext(
          AudioContext(
            android: const AudioContextAndroid(
              isSpeakerphoneOn: true,
              stayAwake: true,
              contentType: AndroidContentType.sonification,
              usageType: AndroidUsageType.alarm,
              audioFocus: AndroidAudioFocus.gainTransientMayDuck,
            ),
            iOS: AudioContextIOS(
              category: AVAudioSessionCategory.playback,
              options: const {AVAudioSessionOptions.duckOthers},
            ),
          ),
        );
        await player.setReleaseMode(ReleaseMode.loop);
        await player.play(AssetSource(_alarmAsset), volume: 1.0);
      } catch (error) {
        // A browser refuses to play until the page has been interacted with, and some
        // emulators have no audio at all. The visual alert is the real one; sound is an
        // escalation on top of it, so this must not take the screen down with it.
        debugPrint('Alarm sound unavailable: $error');
      }
    }

    try {
      if (await _hasVibrator()) {
        await Vibration.vibrate(
          pattern: const [0, 700, 250, 700, 250, 700, 250, 700],
          intensities: const [0, 255, 0, 255, 0, 255, 0, 255],
          repeat: 0,
        );
      }
    } catch (error) {
      debugPrint('Alarm vibration unavailable: $error');
    }
  }

  /// Silence the current alarm. The next new emergency starts it again.
  Future<void> stopAlarm() async {
    _alarmPlaying = false;
    try {
      await _alarmPlayer?.stop();
    } catch (_) {
      // Already stopped, or never started.
    }
    try {
      if (await _hasVibrator()) await Vibration.cancel();
    } catch (_) {
      // Already cancelled.
    }
  }

  /// Mute for the rest of the session, for somebody who cannot have their phone making noise.
  Future<void> setMuted(bool muted) async {
    _muted = muted;
    if (muted) await stopAlarm();
  }

  Future<void> dispose() async {
    await stopAlarm();
    try {
      await _alarmPlayer?.dispose();
      await _cuePlayer?.dispose();
    } catch (_) {
      // Nothing useful to do while tearing down.
    }
  }
}
