import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/models.dart';
import '../i18n/strings.dart';
import '../theme.dart';

/// A coloured status chip. Used everywhere an incident status appears, so the same state always
/// looks the same.
class StatusChip extends StatelessWidget {
  const StatusChip({super.key, required this.status, this.compact = false});

  final String status;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final color = AppTheme.statusColor(status);

    return Container(
      padding: EdgeInsets.symmetric(horizontal: compact ? 8 : 10, vertical: compact ? 3 : 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Text(
        strings.status(status),
        style: TextStyle(
          color: color,
          fontSize: compact ? 11 : 12,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

/// A position with its age attached.
///
/// The age is never optional here. A map pin on its own reads as "where they are", when it may
/// be where they were twenty minutes ago; for someone deciding whether to drive across town
/// that difference matters more than the coordinates do.
class LocationLine extends StatelessWidget {
  const LocationLine({super.key, required this.point, this.showOpenButton = true});

  final LocationPoint point;
  final bool showOpenButton;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final isStale = point.stale;

    return Row(
      children: [
        Icon(
          isStale ? Icons.location_history : Icons.my_location,
          size: 18,
          color: isStale ? AppTheme.caution : AppTheme.safe,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                isStale ? strings.lastKnownPosition : strings.liveNow,
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: isStale ? AppTheme.caution : AppTheme.safe,
                ),
              ),
              Text(
                '${point.latitude.toStringAsFixed(5)}, '
                '${point.longitude.toStringAsFixed(5)} · ${strings.age(point.ageSeconds)}',
                style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
              ),
            ],
          ),
        ),
        if (showOpenButton && point.mapsLink.isNotEmpty)
          IconButton(
            tooltip: strings.openInMaps,
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => launchUrl(
              Uri.parse(point.mapsLink),
              mode: LaunchMode.externalApplication,
            ),
          ),
      ],
    );
  }
}

/// Map of an incident, drawn from OpenStreetMap tiles.
///
/// OSM rather than Google Maps specifically because it needs no API key. A demo that dies
/// because a key was not provisioned, or a billing account lapsed, is a bad trade for slightly
/// prettier tiles — and a deployment in a low-connectivity region benefits from a tile source
/// that can be self-hosted or cached.
class IncidentMap extends StatelessWidget {
  const IncidentMap({
    super.key,
    required this.point,
    this.trail = const [],
    this.height = 200,
  });

  final LocationPoint point;
  final List<LocationPoint> trail;
  final double height;

  @override
  Widget build(BuildContext context) {
    final here = LatLng(point.latitude, point.longitude);
    final path = trail.map((p) => LatLng(p.latitude, p.longitude)).toList();

    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: SizedBox(
        height: height,
        child: FlutterMap(
          options: MapOptions(
            initialCenter: here,
            initialZoom: 15.5,
            // The map is a glance, not a tool: no interaction, so a scroll gesture meant for
            // the page never gets swallowed by it.
            interactionOptions: const InteractionOptions(flags: InteractiveFlag.none),
          ),
          children: [
            TileLayer(
              urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
              userAgentPackageName: 'com.sosync.sosync_app',
            ),
            if (path.length > 1)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: path,
                    strokeWidth: 4,
                    color: AppTheme.danger.withValues(alpha: 0.7),
                  ),
                ],
              ),
            MarkerLayer(
              markers: [
                Marker(
                  point: here,
                  width: 40,
                  height: 40,
                  child: Container(
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: AppTheme.danger,
                      border: Border.all(color: Colors.white, width: 3),
                      boxShadow: [
                        BoxShadow(
                          color: AppTheme.danger.withValues(alpha: 0.5),
                          blurRadius: 12,
                          spreadRadius: 2,
                        ),
                      ],
                    ),
                    child: const Icon(Icons.person, size: 18, color: Colors.white),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// The incident timeline: what happened, in order.
class TimelineList extends StatelessWidget {
  const TimelineList({super.key, required this.entries});

  final List<TimelineEntry> entries;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    if (entries.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < entries.length; i++)
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Column(
                children: [
                  Container(
                    width: 10,
                    height: 10,
                    margin: const EdgeInsets.only(top: 5),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: i == entries.length - 1 ? AppTheme.danger : AppTheme.textMuted,
                    ),
                  ),
                  if (i != entries.length - 1)
                    Container(width: 2, height: 32, color: AppTheme.line),
                ],
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        strings.eventType(entries[i].eventType),
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                      ),
                      Text(
                        [
                          _clock(entries[i].occurredAt),
                          if (entries[i].actorLabel != null) entries[i].actorLabel!,
                        ].join(' · '),
                        style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                      ),
                      if (entries[i].notes != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            entries[i].notes!,
                            style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
      ],
    );
  }

  static String _clock(DateTime time) =>
      '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

/// One delivery in the "who was told" list.
///
/// Shows the channel, whether it arrived, and the language it went out in. The simulated flag is
/// prominent on purpose: a safety tool that lets someone believe a text was sent when it was not
/// is worse than one that admits it.
class DeliveryTile extends StatelessWidget {
  const DeliveryTile({super.key, required this.delivery, this.dense = false});

  final AlertDelivery delivery;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);

    final (icon, channelColor) = switch (delivery.channel) {
      'SMS' => (Icons.sms_outlined, AppTheme.safe),
      'PUSH' => (Icons.notifications_active_outlined, AppTheme.caution),
      _ => (Icons.inbox_outlined, AppTheme.textMuted),
    };

    return Padding(
      padding: EdgeInsets.symmetric(vertical: dense ? 4 : 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: channelColor),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        delivery.recipientLabel ?? delivery.recipientPhone ?? '—',
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                      ),
                    ),
                    _Tag(text: delivery.channel, color: channelColor),
                  ],
                ),
                if (!dense)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      delivery.body,
                      style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                    ),
                  ),
                if (delivery.simulated)
                  Padding(
                    padding: const EdgeInsets.only(top: 3),
                    child: Text(
                      strings.simulatedDelivery,
                      style: const TextStyle(
                        fontSize: 11,
                        color: AppTheme.caution,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                if (delivery.failureReason != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 3),
                    child: Text(
                      delivery.failureReason!,
                      style: const TextStyle(fontSize: 11, color: AppTheme.danger),
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

class _Tag extends StatelessWidget {
  const _Tag({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text(
          text,
          style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: color),
        ),
      );
}

/// A titled block, used to keep the long screens scannable.
class SectionCard extends StatelessWidget {
  const SectionCard({super.key, required this.title, required this.child, this.trailing});

  final String title;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      title.toUpperCase(),
                      style: const TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1,
                        color: AppTheme.textMuted,
                      ),
                    ),
                  ),
                  if (trailing != null) trailing!,
                ],
              ),
              const SizedBox(height: 10),
              child,
            ],
          ),
        ),
      );
}

/// Empty state with an explanation rather than a blank screen.
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.message,
    this.action,
  });

  final IconData icon;
  final String title;
  final String? message;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 52, color: AppTheme.textMuted),
              const SizedBox(height: 14),
              Text(
                title,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
              ),
              if (message != null) ...[
                const SizedBox(height: 8),
                Text(
                  message!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, color: AppTheme.textMuted, height: 1.4),
                ),
              ],
              if (action != null) ...[const SizedBox(height: 20), action!],
            ],
          ),
        ),
      );
}

/// A banner for a problem the user can act on.
class WarningBanner extends StatelessWidget {
  const WarningBanner({
    super.key,
    required this.message,
    this.actionLabel,
    this.onAction,
    this.color = AppTheme.caution,
    this.icon = Icons.warning_amber_rounded,
  });

  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withValues(alpha: 0.4)),
        ),
        child: Row(
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                style: const TextStyle(fontSize: 13, height: 1.35),
              ),
            ),
            if (actionLabel != null && onAction != null)
              TextButton(onPressed: onAction, child: Text(actionLabel!)),
          ],
        ),
      );
}

/// Shows a message without being mistaken for a system alert.
void showToast(BuildContext context, String message, {bool isError = false}) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(message),
      backgroundColor: isError ? AppTheme.dangerDeep : AppTheme.surfaceRaised,
      duration: Duration(seconds: isError ? 5 : 3),
    ),
  );
}
