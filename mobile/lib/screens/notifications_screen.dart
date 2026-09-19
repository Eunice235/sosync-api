import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../i18n/strings.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// The in-app tray.
///
/// Shows the same rows the SMS and push gateways were handed, including the language each went
/// out in and whether it actually left the building. That last part is deliberately prominent:
/// this prototype has no real gateway, and a safety tool that lets someone believe a text was
/// sent when it was not is worse than one that says so.
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  List<AlertDelivery>? _items;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final items = await context.read<ApiClient>().notifications();
      if (mounted) {
        setState(() {
          _items = items;
          _error = null;
        });
      }
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _acknowledge(AlertDelivery delivery) async {
    try {
      await context.read<ApiClient>().acknowledge(delivery.id);
      await _load();
    } catch (error) {
      if (mounted) showToast(context, error.toString(), isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final items = _items;

    return RefreshIndicator(
      onRefresh: _load,
      child: Builder(
        builder: (context) {
          if (items == null && _error != null) {
            return EmptyState(
              icon: Icons.cloud_off,
              title: strings.somethingWentWrong,
              message: _error,
              action: FilledButton(onPressed: _load, child: Text(strings.retry)),
            );
          }
          if (items == null) {
            return const Center(child: CircularProgressIndicator());
          }
          if (items.isEmpty) {
            return EmptyState(
              icon: Icons.notifications_none,
              title: strings.noNotifications,
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
            itemCount: items.length,
            separatorBuilder: (_, __) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              final item = items[index];
              return Card(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Text(
                              item.title,
                              style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          if (!item.acknowledged)
                            Container(
                              width: 9,
                              height: 9,
                              margin: const EdgeInsets.only(top: 5, left: 6),
                              decoration: const BoxDecoration(
                                shape: BoxShape.circle,
                                color: AppTheme.danger,
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        item.body,
                        style: const TextStyle(fontSize: 13, height: 1.4),
                      ),
                      const SizedBox(height: 10),
                      DeliveryTile(delivery: item, dense: true),
                      if (!item.acknowledged)
                        Align(
                          alignment: Alignment.centerRight,
                          child: TextButton.icon(
                            onPressed: () => _acknowledge(item),
                            icon: const Icon(Icons.done, size: 16),
                            label: Text(strings.acknowledge),
                          ),
                        )
                      else
                        Align(
                          alignment: Alignment.centerRight,
                          child: Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text(
                              strings.acknowledged,
                              style: const TextStyle(
                                fontSize: 11,
                                color: AppTheme.safe,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
