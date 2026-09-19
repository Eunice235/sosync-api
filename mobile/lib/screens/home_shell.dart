import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../config.dart';
import '../i18n/strings.dart';
import '../state/auth_store.dart';
import '../theme.dart';
import 'alerts_inbox_screen.dart';
import 'contacts_screen.dart';
import 'notifications_screen.dart';
import 'profile_screen.dart';
import 'responder_screen.dart';
import 'sos_screen.dart';

/// The signed-in shell.
///
/// One app, two shapes. A reporter gets the SOS button first; a responder gets the call list
/// first and no SOS button at all, because a guard on shift pressing their own panic button is
/// not the flow being designed for. The project template allowed either two apps or one
/// role-aware app, and one app means one build to install on the demo phone.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  int _unread = 0;
  Timer? _unreadTimer;

  @override
  void initState() {
    super.initState();
    _refreshUnread();
    _unreadTimer = Timer.periodic(
      AppConfig.incidentPollInterval,
      (_) => _refreshUnread(),
    );
  }

  @override
  void dispose() {
    _unreadTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshUnread() async {
    try {
      final count = await context.read<ApiClient>().unreadCount();
      if (mounted && count != _unread) setState(() => _unread = count);
    } catch (_) {
      // A badge is not worth surfacing an error for.
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final user = context.watch<AuthStore>().user;
    if (user == null) return const SizedBox.shrink();

    final tabs = user.isResponder
        ? <_Tab>[
            _Tab(
              label: strings.tabResponderAlerts,
              icon: Icons.campaign_outlined,
              activeIcon: Icons.campaign,
              screen: const ResponderScreen(),
            ),
            _Tab(
              label: strings.tabNotifications,
              icon: Icons.notifications_none,
              activeIcon: Icons.notifications,
              screen: const NotificationsScreen(),
              badge: _unread,
            ),
            _Tab(
              label: strings.tabProfile,
              icon: Icons.person_outline,
              activeIcon: Icons.person,
              screen: const ProfileScreen(),
            ),
          ]
        : <_Tab>[
            _Tab(
              label: strings.tabHome,
              icon: Icons.sos_outlined,
              activeIcon: Icons.sos_rounded,
              screen: const SosScreen(),
            ),
            _Tab(
              label: strings.tabAlerts,
              icon: Icons.shield_outlined,
              activeIcon: Icons.shield,
              screen: const AlertsInboxScreen(),
            ),
            _Tab(
              label: strings.tabContacts,
              icon: Icons.contacts_outlined,
              activeIcon: Icons.contacts,
              screen: const ContactsScreen(),
              // Pushed as its own route so it keeps its own app bar and add button.
              isRoute: true,
            ),
            _Tab(
              label: strings.tabNotifications,
              icon: Icons.notifications_none,
              activeIcon: Icons.notifications,
              screen: const NotificationsScreen(),
              badge: _unread,
            ),
            _Tab(
              label: strings.tabProfile,
              icon: Icons.person_outline,
              activeIcon: Icons.person,
              screen: const ProfileScreen(),
            ),
          ];

    final safeIndex = _index.clamp(0, tabs.length - 1);
    final current = tabs[safeIndex];

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Icon(Icons.sos_rounded, size: 20, color: AppTheme.danger),
            const SizedBox(width: 8),
            Text(strings.appName),
            const Spacer(),
            if (user.isResponder)
              const Text(
                'RESPONDER',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: AppTheme.textMuted,
                  letterSpacing: 1,
                ),
              ),
          ],
        ),
      ),
      body: SafeArea(child: current.screen),
      bottomNavigationBar: NavigationBar(
        selectedIndex: safeIndex,
        onDestinationSelected: (index) {
          final tab = tabs[index];
          if (tab.isRoute) {
            Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => tab.screen),
            );
            return;
          }
          setState(() => _index = index);
          if (tab.badge != null) _refreshUnread();
        },
        destinations: [
          for (final tab in tabs)
            NavigationDestination(
              icon: tab.badge != null && tab.badge! > 0
                  ? Badge(label: Text('${tab.badge}'), child: Icon(tab.icon))
                  : Icon(tab.icon),
              selectedIcon: Icon(tab.activeIcon),
              label: tab.label,
            ),
        ],
      ),
    );
  }
}

class _Tab {
  const _Tab({
    required this.label,
    required this.icon,
    required this.activeIcon,
    required this.screen,
    this.badge,
    this.isRoute = false,
  });

  final String label;
  final IconData icon;
  final IconData activeIcon;
  final Widget screen;
  final int? badge;

  /// Pushed as a full route rather than swapped into the body.
  final bool isRoute;
}
