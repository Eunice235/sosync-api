import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../config.dart';
import '../i18n/strings.dart';
import '../state/alarm_service.dart';
import '../theme.dart';
import '../widgets/alarm_banner.dart';
import '../widgets/common.dart';

/// The other side of an alert: emergencies raised by people who named you.
///
/// This is the screen Grace opens. It polls, because a contact who has been told somebody needs
/// help should not have to pull to refresh to find out whether help arrived.
class AlertsInboxScreen extends StatefulWidget {
  const AlertsInboxScreen({super.key});

  @override
  State<AlertsInboxScreen> createState() => _AlertsInboxScreenState();
}

class _AlertsInboxScreenState extends State<AlertsInboxScreen>
    with AlarmOnNewAlert<AlertsInboxScreen> {
  List<Incident>? _alerts;
  String? _error;
  Timer? _timer;
  bool _includeClosed = false;

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(AppConfig.incidentPollInterval, (_) => _load(quiet: true));
  }

  @override
  void dispose() {
    _timer?.cancel();
    stopAlarmForDispose();
    super.dispose();
  }

  Future<void> _load({bool quiet = false}) async {
    try {
      final alerts =
          await context.read<ApiClient>().watchedAlerts(includeClosed: _includeClosed);
      if (mounted) {
        setState(() {
          _alerts = alerts;
          _error = null;
        });
        // Somebody who named you has raised an alarm. This is the one notification in the
        // app that is allowed to be loud on arrival.
        handleIncomingAlerts(
          alerts.where((i) => i.isOpen).map((i) => i.id),
        );
      }
    } catch (error) {
      // A failed background poll keeps the last good list on screen: blanking it while
      // somebody is watching an emergency would be the wrong call.
      if (mounted && !quiet) setState(() => _error = error.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final alerts = _alerts;

    return Column(
      children: [
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
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 8, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  strings.peopleWatchingOver,
                  style: const TextStyle(fontSize: 13, color: AppTheme.textMuted),
                ),
              ),
              TextButton.icon(
                onPressed: () {
                  setState(() => _includeClosed = !_includeClosed);
                  _load();
                },
                icon: Icon(
                  _includeClosed ? Icons.history_toggle_off : Icons.history,
                  size: 16,
                ),
                label: Text(
                  _includeClosed ? strings.openOnly : strings.includeClosed,
                  style: const TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: _load,
            child: Builder(
              builder: (context) {
                if (alerts == null && _error != null) {
                  return EmptyState(
                    icon: Icons.cloud_off,
                    title: strings.somethingWentWrong,
                    message: _error,
                    action: FilledButton(
                      onPressed: () => _load(),
                      child: Text(strings.retry),
                    ),
                  );
                }
                if (alerts == null) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (alerts.isEmpty) {
                  return EmptyState(
                    icon: Icons.shield_outlined,
                    title: strings.noActiveAlerts,
                    message: strings.noActiveAlertsHint,
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
                  itemCount: alerts.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) => _WatchedIncidentCard(
                    incident: alerts[index],
                    onChanged: _load,
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

class _WatchedIncidentCard extends StatelessWidget {
  const _WatchedIncidentCard({required this.incident, required this.onChanged});

  final Incident incident;
  final Future<void> Function() onChanged;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final isOpen = incident.isOpen;

    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(
          color: isOpen ? AppTheme.danger : AppTheme.line,
          width: isOpen ? 1.6 : 1,
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
                    isOpen
                        ? '${incident.reporter.fullName} ${strings.needsHelp}'
                        : incident.reporter.fullName,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                ),
                StatusChip(status: incident.status, compact: true),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              '${strings.relationshipLabel(incident.viewerRelationship)} · '
              '${strings.duration(incident.elapsedSeconds)}',
              style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
            ),

            if (incident.note != null) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppTheme.background,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  incident.note!,
                  style: const TextStyle(fontSize: 13, height: 1.35),
                ),
              ),
            ],

            if (incident.reporter.emergencyNote != null) ...[
              const SizedBox(height: 8),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.medical_information_outlined,
                      size: 15, color: AppTheme.caution),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      incident.reporter.emergencyNote!,
                      style: const TextStyle(fontSize: 12, color: AppTheme.caution),
                    ),
                  ),
                ],
              ),
            ],

            // Present while the emergency is open; absent once it closes, because sharing has
            // stopped. The card still shows the outcome.
            if (incident.canViewLocation && incident.location != null) ...[
              const SizedBox(height: 12),
              IncidentMap(point: incident.location!, height: 150),
              const SizedBox(height: 4),
              LocationLine(point: incident.location!),
            ] else if (isOpen) ...[
              const SizedBox(height: 10),
              Text(
                strings.noLocationYet,
                style: const TextStyle(fontSize: 12, color: AppTheme.caution),
              ),
            ],

            if (incident.assignedResponder != null) ...[
              const SizedBox(height: 10),
              Row(
                children: [
                  const Icon(Icons.shield, size: 16, color: AppTheme.safe),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      '${strings.respondingNow}: '
                      '${incident.assignedResponder!.organization}',
                      style: const TextStyle(fontSize: 12, color: AppTheme.safe),
                    ),
                  ),
                ],
              ),
            ],

            if (incident.cancelReason != null) ...[
              const SizedBox(height: 10),
              Text(
                incident.cancelReason!,
                style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
              ),
            ],
            if (incident.resolutionNote != null) ...[
              const SizedBox(height: 10),
              Text(
                incident.resolutionNote!,
                style: const TextStyle(fontSize: 12, color: AppTheme.safe),
              ),
            ],

            const SizedBox(height: 14),
            Row(
              children: [
                if (incident.reporter.phone.isNotEmpty)
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => launchUrl(
                        Uri.parse('tel:${incident.reporter.phone}'),
                      ),
                      icon: const Icon(Icons.call, size: 18),
                      label: Text(strings.callThem),
                    ),
                  ),
                if (incident.canViewLocation && incident.location != null) ...[
                  const SizedBox(width: 10),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () => launchUrl(
                        Uri.parse(incident.location!.mapsLink),
                        mode: LaunchMode.externalApplication,
                      ),
                      icon: const Icon(Icons.map_outlined, size: 18),
                      label: Text(strings.openInMaps),
                    ),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}
