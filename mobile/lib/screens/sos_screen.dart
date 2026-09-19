import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../i18n/strings.dart';
import '../state/alarm_service.dart';
import '../state/auth_store.dart';
import '../state/incident_store.dart';
import '../state/location_service.dart';
import '../theme.dart';
import '../widgets/common.dart';
import '../widgets/hold_button.dart';
import 'active_incident_screen.dart';
import 'contacts_screen.dart';

/// The first screen a reporter sees: one button, and whatever they need to know before using it.
class SosScreen extends StatefulWidget {
  const SosScreen({super.key});

  @override
  State<SosScreen> createState() => _SosScreenState();
}

class _SosScreenState extends State<SosScreen> {
  bool _silent = true;
  final _note = TextEditingController();
  List<TrustedContact> _contacts = const [];
  bool _loadingContacts = true;

  @override
  void initState() {
    super.initState();
    // Picks up an emergency that was already running — raised on another device, or before the
    // app was last closed.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final store = context.read<IncidentStore>();
      store.loadActive();
      store.checkLocationPermission();
      _loadContacts();
    });
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _loadContacts() async {
    try {
      final contacts = await context.read<ApiClient>().contacts();
      if (mounted) {
        setState(() {
          _contacts = contacts;
          _loadingContacts = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loadingContacts = false);
    }
  }

  Future<void> _fire() async {
    final store = context.read<IncidentStore>();
    final outcome = await store.trigger(silent: _silent, note: _note.text.trim());

    if (!mounted) return;

    if (outcome == null) {
      showToast(context, store.error ?? Strings.of(context).somethingWentWrong, isError: true);
      return;
    }

    _note.clear();
    // Straight to the live screen: after the hold completes, the only thing that matters is
    // what is happening now.
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const ActiveIncidentScreen()),
    );
    if (mounted) context.read<IncidentStore>().loadActive();
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final store = context.watch<IncidentStore>();
    final user = context.watch<AuthStore>().user;

    // An emergency is already running: the button is not the point any more.
    if (store.hasActive) {
      return _ActiveBanner(incident: store.active!);
    }

    final hasNoContacts = !_loadingContacts && _contacts.isEmpty;

    return RefreshIndicator(
      onRefresh: () async {
        await context.read<IncidentStore>().loadActive();
        await _loadContacts();
      },
      child: ListView(
        padding: const EdgeInsets.fromLTRB(18, 8, 18, 28),
        children: [
          if (user != null)
            Text(
              user.fullName,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            ),

          const SizedBox(height: 14),

          // The most consequential warning in the app: an SOS with no contacts reaches nobody.
          if (hasNoContacts)
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: WarningBanner(
                message: strings.noContactsWarning,
                icon: Icons.person_add_alt,
                color: AppTheme.danger,
                actionLabel: strings.addContact,
                onAction: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const ContactsScreen()),
                ),
              ),
            ),

          _LocationNotice(availability: store.locationAvailability),

          const SizedBox(height: 10),
          Center(
            child: HoldButton(
              onFired: _fire,
              // A tick as the press registers, and a bump the moment the ring closes. The
              // firm triple pulse that means "it is away" comes later, from IncidentStore,
              // once the server has actually answered.
              onHoldStarted: () => context.read<AlarmService>().holdStarted(),
              onRingClosed: () => context.read<AlarmService>().holdCompleted(),
              idleLabel: strings.holdToSendSos,
              holdingLabel: strings.holdingRelease,
              busyLabel: strings.sendingAlert,
              busy: store.isTriggering,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            strings.sosHint,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13, color: AppTheme.textMuted, height: 1.4),
          ),

          const SizedBox(height: 24),

          Card(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
              child: SwitchListTile(
                value: _silent,
                onChanged: (value) => setState(() => _silent = value),
                title: Text(
                  strings.silentMode,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                subtitle: Text(
                  strings.silentModeHint,
                  style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                ),
                secondary: Icon(
                  _silent ? Icons.volume_off : Icons.volume_up,
                  color: _silent ? AppTheme.safe : AppTheme.caution,
                ),
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ),

          const SizedBox(height: 12),
          TextField(
            controller: _note,
            maxLines: 2,
            maxLength: 200,
            decoration: InputDecoration(
              labelText: strings.whatIsHappening,
              alignLabelWithHint: true,
            ),
          ),

          const SizedBox(height: 6),
          if (!_loadingContacts && _contacts.isNotEmpty)
            SectionCard(
              title: strings.trustedContacts,
              trailing: IconButton(
                tooltip: strings.trustedContacts,
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.chevron_right, size: 20),
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const ContactsScreen()),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final contact in _contacts)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        children: [
                          Icon(
                            contact.hasAccount ? Icons.verified_user : Icons.sms_outlined,
                            size: 16,
                            color: contact.hasAccount ? AppTheme.safe : AppTheme.textMuted,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              contact.name,
                              style: const TextStyle(fontSize: 13),
                            ),
                          ),
                          Text(
                            contact.hasAccount ? strings.hasAppAccount : strings.smsOnly,
                            style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// Explains a location problem in terms of what it costs, and offers the fix.
class _LocationNotice extends StatelessWidget {
  const _LocationNotice({required this.availability});

  final LocationAvailability availability;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    if (availability == LocationAvailability.ok) return const SizedBox.shrink();

    final blocked = availability == LocationAvailability.denied ||
        availability == LocationAvailability.deniedForever ||
        availability == LocationAvailability.serviceDisabled;

    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: WarningBanner(
        message: blocked
            ? '${strings.locationBlocked}. ${strings.locationBlockedHint}'
            : strings.noLocationYet,
        icon: Icons.location_off_outlined,
        color: AppTheme.caution,
        actionLabel: blocked ? strings.enableLocation : null,
        onAction: blocked
            ? () => context.read<LocationService>().openSettings(availability)
            : null,
      ),
    );
  }
}

/// Stands in for the SOS button while an emergency is open, so the button cannot be pressed
/// again by reflex and the way back to the live screen is obvious.
class _ActiveBanner extends StatelessWidget {
  const _ActiveBanner({required this.incident});

  final Incident incident;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.all(22),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTheme.danger.withValues(alpha: 0.16),
                border: Border.all(color: AppTheme.danger, width: 2),
              ),
              child: const Icon(Icons.crisis_alert, size: 52, color: AppTheme.danger),
            ),
            const SizedBox(height: 20),
            Text(
              strings.emergencyActive,
              style: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.w800,
                letterSpacing: 1,
                color: AppTheme.danger,
              ),
            ),
            const SizedBox(height: 8),
            StatusChip(status: incident.status),
            const SizedBox(height: 10),
            Text(
              incident.isClaimed ? strings.respondingNow : strings.noResponderYet,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 14, color: AppTheme.textMuted),
            ),
            const SizedBox(height: 26),
            FilledButton.icon(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const ActiveIncidentScreen()),
              ),
              icon: const Icon(Icons.open_in_full),
              label: Text(strings.emergencyActive),
            ),
          ],
        ),
      ),
    );
  }
}
