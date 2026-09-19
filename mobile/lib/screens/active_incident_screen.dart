import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../i18n/strings.dart';
import '../state/incident_store.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// The live emergency.
///
/// Everything on this screen answers one of three questions, in this order: is help coming, does
/// it know where I am, and who else knows. Anything that does not answer one of those is not
/// here.
class ActiveIncidentScreen extends StatelessWidget {
  const ActiveIncidentScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final store = context.watch<IncidentStore>();
    final incident = store.active;

    if (incident == null) {
      return Scaffold(
        appBar: AppBar(title: Text(strings.appName)),
        body: EmptyState(
          icon: Icons.check_circle_outline,
          title: strings.noActiveAlerts,
          action: FilledButton(
            onPressed: () => Navigator.of(context).maybePop(),
            child: Text(strings.close),
          ),
        ),
      );
    }

    final isOpen = incident.isOpen;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: isOpen ? AppTheme.dangerDeep : AppTheme.background,
        title: Text(isOpen ? strings.emergencyActive : strings.status(incident.status)),
        actions: [
          IconButton(
            tooltip: strings.retry,
            icon: const Icon(Icons.refresh),
            onPressed: () => context.read<IncidentStore>().loadActive(),
          ),
        ],
      ),
      body: ListView(
        // This screen rebuilds every few seconds as the incident is re-polled. Without a
        // storage key the scroll offset resets on each rebuild, which makes the timeline
        // unreadable at exactly the moment somebody is trying to read it.
        key: const PageStorageKey('active-incident'),
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          // The server's own line about who was reached, in the reporter's language. Shown once.
          if (store.lastSummary != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: WarningBanner(
                message: store.lastSummary!,
                icon: Icons.check_circle_outline,
                color: AppTheme.safe,
                actionLabel: strings.close,
                onAction: () => context.read<IncidentStore>().dismissSummary(),
              ),
            ),

          _StatusHeader(incident: incident),
          const SizedBox(height: 14),

          if (incident.canViewLocation && incident.location != null) ...[
            IncidentMap(
              point: incident.location!,
              trail: incident.locationTrail ?? const [],
            ),
            const SizedBox(height: 10),
            Card(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                child: LocationLine(point: incident.location!),
              ),
            ),
          ] else if (isOpen) ...[
            WarningBanner(
              message: strings.noLocationYet,
              icon: Icons.location_searching,
              color: AppTheme.caution,
            ),
          ],

          const SizedBox(height: 14),
          _ResponderCard(incident: incident),

          const SizedBox(height: 14),
          if (incident.notifications != null && incident.notifications!.isNotEmpty)
            SectionCard(
              title: strings.whoWasTold,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final delivery in incident.notifications!)
                    DeliveryTile(delivery: delivery, dense: true),
                  const SizedBox(height: 6),
                  Text(
                    strings.simulatedDeliveryHint,
                    style: const TextStyle(fontSize: 11, color: AppTheme.textMuted, height: 1.4),
                  ),
                ],
              ),
            ),

          const SizedBox(height: 14),
          if (incident.timeline != null && incident.timeline!.isNotEmpty)
            SectionCard(
              title: strings.timeline,
              child: TimelineList(entries: incident.timeline!),
            ),

          if (incident.cancelReason != null) ...[
            const SizedBox(height: 14),
            WarningBanner(
              message: incident.cancelReason!,
              icon: Icons.cancel_outlined,
              color: AppTheme.textMuted,
            ),
          ],
          if (incident.resolutionNote != null) ...[
            const SizedBox(height: 14),
            WarningBanner(
              message: incident.resolutionNote!,
              icon: Icons.verified_outlined,
              color: AppTheme.safe,
            ),
          ],

          const SizedBox(height: 24),
          if (isOpen)
            FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: AppTheme.safe),
              onPressed: store.isCancelling
                  ? null
                  : () => _confirmStandDown(context, incident),
              icon: const Icon(Icons.shield_outlined),
              label: Text(strings.standDown),
            ),
        ],
      ),
    );
  }

  Future<void> _confirmStandDown(BuildContext context, Incident incident) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppTheme.surface,
      builder: (_) => Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).viewInsets.bottom,
        ),
        child: const _StandDownSheet(),
      ),
    );
  }
}

class _StatusHeader extends StatelessWidget {
  const _StatusHeader({required this.incident});

  final Incident incident;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                StatusChip(status: incident.status),
                const Spacer(),
                if (incident.silent)
                  Row(
                    children: [
                      const Icon(Icons.volume_off, size: 14, color: AppTheme.textMuted),
                      const SizedBox(width: 4),
                      Text(
                        strings.silentMode,
                        style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                      ),
                    ],
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              // A closed incident is not waiting for anybody. Saying otherwise on a stood-down
              // alert reads as though help is still coming.
              !incident.isOpen
                  ? strings.status(incident.status)
                  : (incident.isClaimed ? strings.respondingNow : strings.noResponderYet),
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text(
              '${strings.elapsed} ${strings.duration(incident.elapsedSeconds)}',
              style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
            ),
            if (incident.note != null) ...[
              const SizedBox(height: 10),
              Text(
                incident.note!,
                style: const TextStyle(fontSize: 13, height: 1.35),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ResponderCard extends StatelessWidget {
  const _ResponderCard({required this.incident});

  final Incident incident;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final responder = incident.assignedResponder;

    // Nobody took it and it is now closed: there is nothing to wait for, so the spinner card
    // would be actively misleading.
    if (responder == null && !incident.isOpen) return const SizedBox.shrink();

    if (responder == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  strings.noResponderYet,
                  style: const TextStyle(fontSize: 13, color: AppTheme.textMuted),
                ),
              ),
            ],
          ),
        ),
      );
    }

    return SectionCard(
      title: strings.respondingNow,
      child: Row(
        children: [
          Container(
            width: 42,
            height: 42,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppTheme.safe.withValues(alpha: 0.18),
            ),
            child: const Icon(Icons.shield, color: AppTheme.safe),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  responder.organization,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                ),
                if (responder.fullName != null)
                  Text(
                    responder.fullName!,
                    style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                  ),
                if (responder.distanceKm != null)
                  Text(
                    '${responder.distanceKm} km ${strings.distanceAway}',
                    style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                  ),
              ],
            ),
          ),
          if (responder.isVerified)
            const Icon(Icons.verified, size: 18, color: AppTheme.safe),
        ],
      ),
    );
  }
}

/// The stand-down sheet.
///
/// The one place in the app where a wrong input deliberately does not look like a failure. A
/// wrong PIN keeps the emergency running and says so calmly, because the person typing may be
/// the reason the alarm was raised.
class _StandDownSheet extends StatefulWidget {
  const _StandDownSheet();

  @override
  State<_StandDownSheet> createState() => _StandDownSheetState();
}

class _StandDownSheetState extends State<_StandDownSheet> {
  final _pin = TextEditingController();
  final _reason = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _pin.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });

    final strings = Strings.of(context);
    try {
      await context.read<IncidentStore>().cancel(
            pin: _pin.text.trim(),
            reason: _reason.text.trim(),
          );
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (error) {
      setState(() {
        // The distinct code exists exactly so this case reads differently: the alert is still
        // live, and the wording says so.
        _error = error.isInvalidSafetyPin ? strings.safetyPinWrong : error.message;
        _pin.clear();
      });
    } on NetworkException catch (error) {
      setState(() => _error = error.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: AppTheme.line,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            strings.standDownTitle,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 6),
          Text(
            strings.safetyPinHint,
            style: const TextStyle(fontSize: 12, color: AppTheme.textMuted, height: 1.4),
          ),
          const SizedBox(height: 18),

          if (_error != null) ...[
            WarningBanner(
              message: _error!,
              icon: Icons.info_outline,
              // Caution, not danger: help is still coming, and this is not a disaster.
              color: AppTheme.caution,
            ),
            const SizedBox(height: 14),
          ],

          TextField(
            controller: _pin,
            autofocus: true,
            obscureText: true,
            keyboardType: TextInputType.number,
            maxLength: 8,
            // Rebuilds so the confirm button enables as soon as something is typed.
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) => _pin.text.isEmpty ? null : _submit(),
            decoration: InputDecoration(
              labelText: strings.enterSafetyPin,
              prefixIcon: const Icon(Icons.pin_outlined),
              counterText: '',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _reason,
            maxLength: 120,
            decoration: InputDecoration(
              labelText: strings.cancelReasonOptional,
              counterText: '',
            ),
          ),
          const SizedBox(height: 18),
          FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: AppTheme.safe),
            onPressed: _busy || _pin.text.isEmpty ? null : _submit,
            icon: _busy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : const Icon(Icons.check),
            label: Text(strings.confirmStandDown),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: Text(strings.cancel),
          ),
        ],
      ),
    );
  }
}
