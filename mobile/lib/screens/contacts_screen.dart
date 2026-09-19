import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../i18n/strings.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// The trusted-contact list.
///
/// The screen leans on one distinction throughout: a contact who has SOSync gets in-app alerts
/// and can follow the incident, and a contact who does not gets an SMS. Both work. Making that
/// visible matters because the SMS-only case is the one that actually carries the product in
/// the places it is meant for.
class ContactsScreen extends StatefulWidget {
  const ContactsScreen({super.key});

  @override
  State<ContactsScreen> createState() => _ContactsScreenState();
}

class _ContactsScreenState extends State<ContactsScreen> {
  List<TrustedContact>? _contacts;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final contacts = await context.read<ApiClient>().contacts();
      if (mounted) {
        setState(() {
          _contacts = contacts;
          _error = null;
        });
      }
    } on ApiException catch (error) {
      if (mounted) setState(() => _error = error.message);
    } on NetworkException catch (error) {
      if (mounted) setState(() => _error = error.message);
    }
  }

  Future<void> _add() async {
    final added = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppTheme.surface,
      builder: (sheetContext) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(sheetContext).viewInsets.bottom),
        child: const _AddContactSheet(),
      ),
    );
    if (added == true) _load();
  }

  Future<void> _remove(TrustedContact contact) async {
    final strings = Strings.of(context);
    // Both captured before the dialog: everything read off `context` has to happen on this
    // side of the await, because the tree may be gone by the time the dialog closes.
    final api = context.read<ApiClient>();

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(strings.removeContact),
        content: Text(contact.name),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(strings.cancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppTheme.danger),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(strings.removeContact),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      await api.deleteContact(contact.id);
      await _load();
    } catch (error) {
      if (mounted) showToast(context, error.toString(), isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = Strings.of(context);
    final contacts = _contacts;

    return Scaffold(
      appBar: AppBar(title: Text(strings.trustedContacts)),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _add,
        backgroundColor: AppTheme.danger,
        icon: const Icon(Icons.person_add_alt),
        label: Text(strings.addContact),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: Builder(
          builder: (context) {
            if (_error != null) {
              return EmptyState(
                icon: Icons.cloud_off,
                title: strings.somethingWentWrong,
                message: _error,
                action: FilledButton(onPressed: _load, child: Text(strings.retry)),
              );
            }
            if (contacts == null) {
              return const Center(child: CircularProgressIndicator());
            }
            if (contacts.isEmpty) {
              return EmptyState(
                icon: Icons.contacts_outlined,
                title: strings.noContactsYet,
                message: strings.noContactsWarning,
                action: FilledButton.icon(
                  onPressed: _add,
                  icon: const Icon(Icons.person_add_alt),
                  label: Text(strings.addContact),
                ),
              );
            }

            return ListView.separated(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
              itemCount: contacts.length,
              separatorBuilder: (_, __) => const SizedBox(height: 10),
              itemBuilder: (context, index) {
                final contact = contacts[index];
                return Card(
                  child: ListTile(
                    contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                    leading: Container(
                      width: 42,
                      height: 42,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: (contact.hasAccount ? AppTheme.safe : AppTheme.caution)
                            .withValues(alpha: 0.16),
                      ),
                      child: Icon(
                        contact.hasAccount ? Icons.verified_user : Icons.sms_outlined,
                        size: 20,
                        color: contact.hasAccount ? AppTheme.safe : AppTheme.caution,
                      ),
                    ),
                    title: Text(
                      contact.name,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 2),
                        Text(
                          contact.phone,
                          style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                        ),
                        const SizedBox(height: 4),
                        Wrap(
                          spacing: 6,
                          runSpacing: 4,
                          children: [
                            _Pill(
                              text: contact.hasAccount
                                  ? strings.hasAppAccount
                                  : strings.smsOnly,
                              color: contact.hasAccount ? AppTheme.safe : AppTheme.caution,
                            ),
                            if (contact.relationship != null)
                              _Pill(text: contact.relationship!, color: AppTheme.textMuted),
                            if (contact.priority == 1)
                              _Pill(text: strings.priorityBadge, color: AppTheme.danger),
                          ],
                        ),
                      ],
                    ),
                    trailing: IconButton(
                      tooltip: strings.removeContact,
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () => _remove(contact),
                    ),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
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

class _AddContactSheet extends StatefulWidget {
  const _AddContactSheet();

  @override
  State<_AddContactSheet> createState() => _AddContactSheetState();
}

class _AddContactSheetState extends State<_AddContactSheet> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _relationship = TextEditingController();

  int _priority = 1;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _relationship.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      await context.read<ApiClient>().addContact(
            name: _name.text.trim(),
            phone: _phone.text.trim(),
            relationship: _relationship.text.trim(),
            priority: _priority,
          );
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
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              strings.addContact,
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

            TextFormField(
              controller: _name,
              autofocus: true,
              textCapitalization: TextCapitalization.words,
              decoration: InputDecoration(labelText: strings.contactName),
              validator: (v) => (v == null || v.trim().isEmpty) ? strings.contactName : null,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _phone,
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(
                labelText: strings.phoneNumber,
                hintText: '0712345678',
              ),
              validator: (v) => (v == null || v.trim().isEmpty) ? strings.phoneNumber : null,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _relationship,
              decoration: InputDecoration(labelText: strings.relationshipOptional),
            ),

            const SizedBox(height: 14),
            SwitchListTile(
              value: _priority == 1,
              onChanged: (value) => setState(() => _priority = value ? 1 : 2),
              title: Text(strings.priorityContact),
              subtitle: Text(
                strings.priorityContactHint,
                style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
              ),
              contentPadding: EdgeInsets.zero,
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
      ),
    );
  }
}
