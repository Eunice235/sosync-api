import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../config.dart';
import '../i18n/strings.dart';
import '../state/alarm_service.dart';
import '../state/location_service.dart';
import '../theme.dart';
import '../widgets/alarm_banner.dart';
import '../widgets/common.dart';

/// The guard's screen: who needs help, how far away, and how long they have been waiting.
///
/// The list is ordered by waiting time rather than distance, matching the server. The nearest
/// call is not the most urgent one; the one nobody has taken for four minutes is.
class ResponderScreen extends StatefulWidget {
  const ResponderScreen({super.key});

  @override
  State<ResponderScreen> createState() => _ResponderScreenState();
}

class _ResponderScreenState extends State<ResponderScreen>
    with AlarmOnNewAlert<ResponderScreen> {
  ResponderSummary? _profile;
  List<ResponderAlert>? _alerts;
  String? _error;
  Timer? _timer;
  bool _updatingShift = false;

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(AppConfig.incidentPollInterval, (_) => _loadAlerts(quiet: true));
  }

  @override
  void dispose() {
    _timer?.cancel();
    stopAlarmForDispose();
    super.dispose();
  }

  Future<void> _load() async {
    final api = context.read<ApiClient>();
    try {
      final profile = await api.responderProfile();
      if (mounted) setState(() => _profile = profile);
      await _loadAlerts();
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _loadAlerts({bool quiet = false}) async {
    try {
      final alerts = await context.read<ApiClient>().responderAlerts();
      if (mounted) {
        setState(() {
          _alerts = alerts;
          _error = null;
        });
        // Only unclaimed calls raise the alarm. An incident this responder already accepted
        // is one they are actively working; re-sounding it would punish them for helping.
        handleIncomingAlerts(
          alerts.where((a) => !a.isMine).map((a) => a.incidentId),
        );
      }
    } catch (error) {
      if (mounted && !quiet) setState(() => _error = error.toString());
    }
  }

  /// Going on shift sends a position first.
  ///
  /// Without one the server cannot match this responder to anything by distance, so an
  /// available responder with no known position would sit there receiving nothing and have no
  /// way to tell why.
  Future<void> _toggleShift(bool goOnShift) async {
    setState(() => _updatingShift = true);
    final api = context.read<ApiClient>();

    try {
      if (goOnShift) {
        final fix = await context.read<LocationService>().currentPosition();
        final position = fix.position;
        if (position != null) {
          await api.updateResponderLocation(position.latitude, position.longitude);
        } else if (mounted) {
          showToast(context, Strings.of(context).locationBlockedHint, isError: true);
        }
      }

      final updated = await api.setAvailability(goOnShift ? 'AVAILABLE' : 'OFFLINE');
      if (mounted) setState(() => _profile = updated);
      await _loadAlerts();
    } catch (error) {
      if (mounted) showToast(context, error.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _updatingShift = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final profile = _profile;
    final alerts = _alerts;

    return Column(
      children: [
        if (profile != null) _ShiftHeader(
          profile: profile,
          busy: _updatingShift,
          onToggle: _toggleShift,
        ),
        AlarmBanner(
          sounding: isAlarmActive,
          muted: context.read<AlarmService>().isMuted,
          onSilence: silenceAlarm,
          onToggleMute: () async {
            final alarm = context.read<AlarmService>();
            await alarm.setMuted(!alarm.isMuted);
            if (mounted) setState(() {});
          },
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: _load,
            child: Builder(
              builder: (context) {
                if (profile != null && !profile.isVerified) {
                  return EmptyState(
                    icon: Icons.hourglass_empty,
                    title: strings.awaitingVerification,
                    message: strings.awaitingVerificationHint,
                  );
                }
                if (alerts == null && _error != null) {
                  return EmptyState(
                    icon: Icons.cloud_off,
                    title: strings.somethingWentWrong,
                    message: _error,
                    action: FilledButton(onPressed: _load, child: Text(strings.retry)),
                  );
                }
                if (alerts == null) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (alerts.isEmpty) {
                  return EmptyState(
                    icon: Icons.notifications_off_outlined,
                    title: strings.noOpenAlerts,
                    message: profile?.isAvailable == true ? null : strings.noOpenAlertsHint,
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
                  itemCount: alerts.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) => _AlertCard(
                    alert: alerts[index],
                    onChanged: _loadAlerts,
                  ),
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}

class _ShiftHeader extends StatelessWidget {
  const _ShiftHeader({
    required this.profile,
    required this.busy,
    required this.onToggle,
  });

  final ResponderSummary profile;
  final bool busy;
  final Future<void> Function(bool) onToggle;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final onShift = profile.isAvailable;

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: onShift ? AppTheme.safe.withValues(alpha: 0.6) : AppTheme.line,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: (onShift ? AppTheme.safe : AppTheme.textMuted).withValues(alpha: 0.16),
            ),
            child: Icon(
              Icons.shield,
              size: 20,
              color: onShift ? AppTheme.safe : AppTheme.textMuted,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  profile.organization,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                ),
                Text(
                  profile.isVerified
                      ? (onShift ? strings.onShift : strings.offShift)
                      : strings.awaitingVerification,
                  style: TextStyle(
                    fontSize: 12,
                    color: profile.isVerified
                        ? (onShift ? AppTheme.safe : AppTheme.textMuted)
                        : AppTheme.caution,
                  ),
                ),
              ],
            ),
          ),
          if (busy)
            const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else
            Switch(
              value: onShift,
              // An unverified responder cannot go on shift: it would receive nothing anyway,
              // and letting the switch move would imply otherwise.
              onChanged: profile.isVerified ? (value) => onToggle(value) : null,
            ),
        ],
      ),
    );
  }
}

class _AlertCard extends StatefulWidget {
  const _AlertCard({required this.alert, required this.onChanged});

  final ResponderAlert alert;
  final Future<void> Function() onChanged;

  @override
  State<_AlertCard> createState() => _AlertCardState();
}

class _AlertCardState extends State<_AlertCard> {
  bool _busy = false;

  Future<void> _accept() async {
    setState(() => _busy = true);
    final strings = Strings.of(context);
    try {
      await context.read<ApiClient>().acceptIncident(widget.alert.incidentId);
      await widget.onChanged();
    } on ApiException catch (error) {
      if (mounted) {
        // 409 means somebody else got there first. That is normal operation, not a fault.
        showToast(
          context,
          error.isConflict ? strings.someoneElseTookIt : error.message,
          isError: !error.isConflict,
        );
      }
      await widget.onChanged();
    } catch (error) {
      if (mounted) showToast(context, error.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _advance(String status, {String? note}) async {
    setState(() => _busy = true);
    try {
      await context.read<ApiClient>().advanceIncident(
            widget.alert.incidentId,
            status,
            note: note,
          );
      await widget.onChanged();
    } catch (error) {
      if (mounted) showToast(context, error.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resolve() async {
    final controller = TextEditingController();
    final strings = Strings.of(context);

    final note = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(strings.markResolved),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLines: 3,
          decoration: InputDecoration(labelText: strings.resolutionNote),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: Text(strings.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppTheme.safe),
            onPressed: () => Navigator.of(dialogContext).pop(controller.text),
            child: Text(strings.markResolved),
          ),
        ],
      ),
    );

    if (note != null) await _advance('RESOLVED', note: note);
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final alert = widget.alert;

    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(
          color: alert.isMine ? AppTheme.safe : AppTheme.danger,
          width: 1.6,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    alert.reporter.fullName,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                ),
                StatusChip(status: alert.status, compact: true),
              ],
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                const Icon(Icons.timer_outlined, size: 14, color: AppTheme.caution),
                const SizedBox(width: 4),
                Text(
                  '${strings.waitingFor} ${strings.duration(alert.waitingSeconds)}',
                  style: const TextStyle(fontSize: 12, color: AppTheme.caution),
                ),
                if (alert.distanceKm != null) ...[
                  const SizedBox(width: 12),
                  const Icon(Icons.near_me_outlined, size: 14, color: AppTheme.textMuted),
                  const SizedBox(width: 4),
                  Text(
                    '${alert.distanceKm} km ${strings.distanceAway}',
                    style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                  ),
                ],
              ],
            ),

            if (alert.note != null) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppTheme.background,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(alert.note!, style: const TextStyle(fontSize: 13, height: 1.35)),
              ),
            ],

            // What a responder needs on arrival, and nothing more of the profile.
            if (alert.reporter.emergencyNote != null) ...[
              const SizedBox(height: 8),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.medical_information_outlined,
                      size: 15, color: AppTheme.caution),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      '${strings.medicalNote}: ${alert.reporter.emergencyNote!}',
                      style: const TextStyle(fontSize: 12, color: AppTheme.caution),
                    ),
                  ),
                ],
              ),
            ],

            if (alert.location != null) ...[
              const SizedBox(height: 12),
              IncidentMap(point: alert.location!, height: 150),
              const SizedBox(height: 4),
              LocationLine(point: alert.location!),
            ] else ...[
              const SizedBox(height: 10),
              Text(
                strings.noLocationYet,
                style: const TextStyle(fontSize: 12, color: AppTheme.caution),
              ),
            ],

            const SizedBox(height: 12),
            if (_busy)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              )
            else
              _Actions(
                alert: alert,
                onAccept: _accept,
                onResponding: () => _advance('RESPONDING'),
                onArrived: () => _advance('ARRIVED'),
                onResolve: _resolve,
              ),
          ],
        ),
      ),
    );
  }
}

/// The action row. Only the transitions that are legal from the current status are offered, so
/// the app never invites a tap the server will refuse.
class _Actions extends StatelessWidget {
  const _Actions({
    required this.alert,
    required this.onAccept,
    required this.onResponding,
    required this.onArrived,
    required this.onResolve,
  });

  final ResponderAlert alert;
  final VoidCallback onAccept;
  final VoidCallback onResponding;
  final VoidCallback onArrived;
  final VoidCallback onResolve;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);

    final callButton = alert.reporter.phone.isEmpty
        ? null
        : OutlinedButton.icon(
            onPressed: () => launchUrl(Uri.parse('tel:${alert.reporter.phone}')),
            icon: const Icon(Icons.call, size: 18),
            label: Text(strings.callThem),
          );

    if (!alert.isMine) {
      return Column(
        children: [
          FilledButton.icon(
            onPressed: onAccept,
            icon: const Icon(Icons.pan_tool_alt_outlined),
            label: Text(strings.takeThisCall),
          ),
          if (callButton != null) ...[const SizedBox(height: 8), callButton],
        ],
      );
    }

    return Column(
      children: [
        if (alert.status == 'ACCEPTED')
          FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: AppTheme.caution),
            onPressed: onResponding,
            icon: const Icon(Icons.directions_run),
            label: Text(strings.onMyWay),
          ),
        if (alert.status == 'ACCEPTED' || alert.status == 'RESPONDING') ...[
          const SizedBox(height: 8),
          FilledButton.icon(
            onPressed: onArrived,
            icon: const Icon(Icons.location_on),
            label: Text(strings.iHaveArrived),
          ),
        ],
        const SizedBox(height: 8),
        FilledButton.icon(
          style: FilledButton.styleFrom(backgroundColor: AppTheme.safe),
          onPressed: onResolve,
          icon: const Icon(Icons.check_circle_outline),
          label: Text(strings.markResolved),
        ),
        if (callButton != null) ...[const SizedBox(height: 8), callButton],
        if (alert.location != null) ...[
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () => launchUrl(
              Uri.parse(alert.location!.mapsLink),
              mode: LaunchMode.externalApplication,
            ),
            icon: const Icon(Icons.navigation_outlined, size: 18),
            label: Text(strings.openInMaps),
          ),
        ],
      ],
    );
  }
}
