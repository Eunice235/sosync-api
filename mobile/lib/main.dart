import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'api/api_client.dart';
import 'i18n/strings.dart';
import 'screens/home_shell.dart';
import 'screens/login_screen.dart';
import 'state/alarm_service.dart';
import 'state/auth_store.dart';
import 'state/incident_store.dart';
import 'state/location_service.dart';
import 'theme.dart';

void main() {
  runApp(const SosyncApp());
}

class SosyncApp extends StatefulWidget {
  const SosyncApp({super.key});

  @override
  State<SosyncApp> createState() => _SosyncAppState();
}

class _SosyncAppState extends State<SosyncApp> {
  late final ApiClient _api = ApiClient();
  late final LocationService _location = LocationService();
  late final AlarmService _alarm = AlarmService();
  late final AuthStore _auth = AuthStore(_api);
  late final IncidentStore _incidents = IncidentStore(_api, _location, _alarm);

  @override
  void initState() {
    super.initState();
    // Restores the saved session and server address before the first frame settles, so a
    // returning user is not shown a login screen they do not need.
    _auth.restore();
  }

  @override
  void dispose() {
    _incidents.dispose();
    _auth.dispose();
    _alarm.dispose();
    _api.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        Provider<ApiClient>.value(value: _api),
        Provider<LocationService>.value(value: _location),
        Provider<AlarmService>.value(value: _alarm),
        ChangeNotifierProvider<AuthStore>.value(value: _auth),
        ChangeNotifierProvider<IncidentStore>.value(value: _incidents),
      ],
      child: Consumer<AuthStore>(
        builder: (context, auth, _) {
          // The whole UI follows the account's language, so a second language would change
          // the app and the alerts together rather than only the alerts.
          return LocalisedApp(
            strings: Strings.forCode(auth.languageCode),
            child: MaterialApp(
              title: 'SOSync',
              debugShowCheckedModeBanner: false,
              theme: AppTheme.build(),
              home: const _AppRoot(),
            ),
          );
        },
      ),
    );
  }
}

class _AppRoot extends StatelessWidget {
  const _AppRoot();

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthStore>();

    if (auth.isRestoring) return const SplashScreen();
    if (!auth.isSignedIn) return const LoginScreen();
    return const HomeShell();
  }
}

class SplashScreen extends StatelessWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _Mark(),
            SizedBox(height: 18),
            Text(
              'SOSync',
              style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, letterSpacing: 1),
            ),
            SizedBox(height: 6),
            Text(
              'One action. The right people know.',
              style: TextStyle(fontSize: 13, color: AppTheme.textMuted),
            ),
            SizedBox(height: 28),
            SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2.4),
            ),
          ],
        ),
      ),
    );
  }
}

class _Mark extends StatelessWidget {
  const _Mark();

  @override
  Widget build(BuildContext context) => Container(
        width: 84,
        height: 84,
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(colors: [AppTheme.danger, AppTheme.dangerDeep]),
        ),
        child: const Icon(Icons.sos_rounded, size: 42, color: Colors.white),
      );
}
