import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../i18n/strings.dart';
import '../state/auth_store.dart';
import '../theme.dart';
import '../widgets/common.dart';

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  final _note = TextEditingController();
  bool _savingNote = false;

  @override
  void initState() {
    super.initState();
    _note.text = context.read<AuthStore>().user?.emergencyNote ?? '';
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _saveNote() async {
    setState(() => _savingNote = true);
    final api = context.read<ApiClient>();
    final auth = context.read<AuthStore>();
    final savedLabel = Strings.of(context).saved;

    try {
      final updated = await api.updateProfile(emergencyNote: _note.text.trim());
      auth.updateUser(updated);
      if (mounted) showToast(context, savedLabel);
    } catch (error) {
      if (mounted) showToast(context, error.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _savingNote = false);
    }
  }

  Future<void> _setPin() async {
    final result = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppTheme.surface,
      builder: (sheetContext) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(sheetContext).viewInsets.bottom),
        child: const _SetPinSheet(),
      ),
    );
    if (result == true && mounted) {
      await context.read<AuthStore>().refreshUser();
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final auth = context.watch<AuthStore>();
    final user = auth.user;

    if (user == null) return const SizedBox.shrink();

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
      children: [
        Row(
          children: [
            Container(
              width: 54,
              height: 54,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTheme.danger.withValues(alpha: 0.16),
              ),
              child: const Icon(Icons.person, color: AppTheme.danger),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    user.fullName,
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                  ),
                  Text(
                    '${user.phone} · ${user.role}',
                    style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                  ),
                ],
              ),
            ),
          ],
        ),

        const SizedBox(height: 20),

        // The PIN is the single control that decides who can call off a response, so it is the
        // first thing on this screen and it says why it matters.
        SectionCard(
          title: strings.safetyPin,
          trailing: Icon(
            user.safetyPinSet ? Icons.lock : Icons.lock_open,
            size: 16,
            color: user.safetyPinSet ? AppTheme.safe : AppTheme.caution,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                user.safetyPinSet ? strings.safetyPinSet : strings.safetyPinNotSet,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: user.safetyPinSet ? AppTheme.safe : AppTheme.caution,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                strings.safetyPinHint,
                style: const TextStyle(fontSize: 12, color: AppTheme.textMuted, height: 1.4),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: _setPin,
                icon: const Icon(Icons.pin_outlined, size: 18),
                label: Text(strings.setSafetyPin),
              ),
            ],
          ),
        ),

        const SizedBox(height: 14),
        SectionCard(
          title: strings.emergencyNote,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                strings.emergencyNoteHint,
                style: const TextStyle(fontSize: 12, color: AppTheme.textMuted, height: 1.4),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _note,
                maxLines: 3,
                maxLength: 300,
                decoration: const InputDecoration(counterText: ''),
              ),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: _savingNote ? null : _saveNote,
                child: Text(strings.save),
              ),
            ],
          ),
        ),

        const SizedBox(height: 20),
        WarningBanner(
          message: strings.prototypeNotice,
          icon: Icons.info_outline,
          color: AppTheme.textMuted,
        ),

        const SizedBox(height: 20),
        OutlinedButton.icon(
          onPressed: () => context.read<AuthStore>().signOut(),
          icon: const Icon(Icons.logout, size: 18),
          label: Text(strings.signOut),
        ),
      ],
    );
  }
}

class _SetPinSheet extends StatefulWidget {
  const _SetPinSheet();

  @override
  State<_SetPinSheet> createState() => _SetPinSheetState();
}

class _SetPinSheetState extends State<_SetPinSheet> {
  final _pin = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _pin.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await context.read<ApiClient>().setSafetyPin(_pin.text.trim(), _password.text);
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (error) {
      setState(() => _error = error.message);
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
          Text(
            strings.setSafetyPin,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 16),
          if (_error != null) ...[
            WarningBanner(
              message: _error!,
              icon: Icons.error_outline,
              color: AppTheme.danger,
            ),
            const SizedBox(height: 14),
          ],
          TextField(
            controller: _pin,
            autofocus: true,
            obscureText: true,
            keyboardType: TextInputType.number,
            maxLength: 8,
            decoration: InputDecoration(labelText: strings.newPin, counterText: ''),
          ),
          const SizedBox(height: 12),
          // The account password is required even to change the PIN: otherwise somebody holding
          // an unlocked phone could set a PIN they know, then use it to cancel an alert.
          TextField(
            controller: _password,
            obscureText: true,
            decoration: InputDecoration(labelText: strings.confirmWithPassword),
          ),
          const SizedBox(height: 18),
          FilledButton(
            onPressed: _busy ? null : _submit,
            child: _busy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : Text(strings.save),
          ),
        ],
      ),
    );
  }
}
