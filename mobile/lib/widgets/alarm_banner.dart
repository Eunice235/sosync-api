import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../i18n/strings.dart';
import '../state/alarm_service.dart';
import '../theme.dart';

/// Decides when a receiver's phone should be making a noise.
///
/// Mixed into the two screens that receive emergencies — the trusted-contact inbox and the
/// responder call list — so both behave identically. Getting this subtly different in two
/// places is how you end up with an alarm that works for a guard and not for a sister.
///
/// The rule is: **sound once per emergency, on arrival.** Specifically, the alarm starts when
/// an incident id appears that was not in the previous poll, and stops when no unseen
/// emergency is left. It does not restart on every four-second refresh, because an alarm that
/// cannot be silenced is one people learn to leave the app closed to avoid — and then it
/// protects nobody.
mixin AlarmOnNewAlert<T extends StatefulWidget> on State<T> {
  AlarmService get _alarm => context.read<AlarmService>();

  /// Shared across screens and tab switches; see [AlarmService.announcedIncidents].
  Set<String> get _announced => _alarm.announcedIncidents;

  /// Read from the service rather than tracked here, so reopening the tab while the alarm is
  /// still ringing shows the Silence button instead of hiding it.
  bool get isAlarmActive => _alarm.isAlarmPlaying;

  /// Call after every refresh with the ids of the emergencies currently open and unclaimed by
  /// this user. Returns true when something new arrived.
  bool handleIncomingAlerts(Iterable<String> openIncidentIds) {
    final ids = openIncidentIds.toSet();

    // Forget incidents that have closed, so the same person raising a second emergency later
    // does sound the alarm again.
    _announced.removeWhere((id) => !ids.contains(id));

    final fresh = ids.difference(_announced);
    if (fresh.isEmpty) return false;

    _announced.addAll(fresh);
    _alarm.startAlarm();
    if (mounted) setState(() {});
    return true;
  }

  /// Silence the current alarm without muting future ones.
  Future<void> silenceAlarm() async {
    await _alarm.stopAlarm();
    if (mounted) setState(() {});
  }

  /// Called when the screen goes away, so the alarm does not keep ringing behind it.
  void stopAlarmForDispose() {
    _alarm.stopAlarm();
  }
}

/// The bar that appears while the alarm is sounding, and the mute control when it is not.
///
/// The silence button is deliberately large and the first thing reachable: somebody woken by
/// this needs to stop the noise before they can read anything, and hunting for a small icon
/// while a 2.5 kHz warble plays is its own small emergency.
class AlarmBanner extends StatelessWidget {
  const AlarmBanner({
    super.key,
    required this.sounding,
    required this.onSilence,
    required this.onToggleMute,
    required this.muted,
  });

  final bool sounding;
  final VoidCallback onSilence;
  final VoidCallback onToggleMute;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);

    if (sounding) {
      return Container(
        margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
        padding: const EdgeInsets.fromLTRB(14, 10, 10, 10),
        decoration: BoxDecoration(
          color: AppTheme.danger.withValues(alpha: 0.22),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppTheme.danger, width: 1.5),
        ),
        child: Row(
          children: [
            const _PulsingBell(),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                strings.alarmSounding,
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
              ),
            ),
            FilledButton.icon(
              style: FilledButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: AppTheme.dangerDeep,
                minimumSize: const Size(0, 44),
                padding: const EdgeInsets.symmetric(horizontal: 16),
              ),
              onPressed: onSilence,
              icon: const Icon(Icons.volume_off, size: 18),
              label: Text(strings.silenceAlarm),
            ),
          ],
        ),
      );
    }

    if (muted) {
      return Container(
        margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppTheme.caution.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppTheme.caution.withValues(alpha: 0.4)),
        ),
        child: Row(
          children: [
            const Icon(Icons.notifications_off_outlined, size: 18, color: AppTheme.caution),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    strings.alarmMuted,
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                  ),
                  Text(
                    strings.alarmMutedHint,
                    style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                  ),
                ],
              ),
            ),
            TextButton(onPressed: onToggleMute, child: Text(strings.unmuteAlarm)),
          ],
        ),
      );
    }

    return const SizedBox.shrink();
  }
}

class _PulsingBell extends StatefulWidget {
  const _PulsingBell();

  @override
  State<_PulsingBell> createState() => _PulsingBellState();
}

class _PulsingBellState extends State<_PulsingBell>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 520),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => Transform.scale(
          // Visually in time with the alarm's own throb, so the screen and the sound read as
          // one event rather than two things happening at once.
          scale: 1.0 + (0.18 * _controller.value),
          child: const Icon(Icons.notifications_active, color: AppTheme.danger, size: 24),
        ),
      );
}
