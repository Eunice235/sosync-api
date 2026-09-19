import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../i18n/strings.dart';
import '../state/auth_store.dart';
import '../theme.dart';
import '../widgets/common.dart';

class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _organisation = TextEditingController();

  bool _asResponder = false;
  bool _busy = false;
  String? _error;
  Map<String, String>? _fieldErrors;

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _email.dispose();
    _password.dispose();
    _organisation.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    setState(() {
      _busy = true;
      _error = null;
      _fieldErrors = null;
    });

    try {
      final result = await context.read<AuthStore>().register(
            fullName: _name.text.trim(),
            phone: _phone.text.trim(),
            password: _password.text,
            email: _email.text.trim(),
            asResponder: _asResponder,
            organization: _organisation.text.trim(),
          );

      if (!mounted) return;

      // The code is only returned because no SMS gateway is connected. Saying so is the honest
      // version of showing it at all.
      final code = result.simulatedVerificationCode;
      if (code != null) {
        showToast(context, 'Simulated verification code: $code');
      }
      // The root widget switches to the home shell; this screen just gets out of the way.
      Navigator.of(context).popUntil((route) => route.isFirst);
    } on ApiException catch (error) {
      setState(() {
        _error = error.message;
        _fieldErrors = error.fieldErrors;
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

    return Scaffold(
      appBar: AppBar(title: Text(strings.createAccount)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (_error != null) ...[
                      WarningBanner(
                        message: _error!,
                        icon: Icons.error_outline,
                        color: AppTheme.danger,
                      ),
                      const SizedBox(height: 16),
                    ],

                    TextFormField(
                      controller: _name,
                      textCapitalization: TextCapitalization.words,
                      decoration: InputDecoration(
                        labelText: strings.fullName,
                        prefixIcon: const Icon(Icons.person_outline),
                        errorText: _fieldErrors?['fullName'],
                      ),
                      validator: (v) =>
                          (v == null || v.trim().isEmpty) ? strings.fullName : null,
                    ),
                    const SizedBox(height: 14),
                    TextFormField(
                      controller: _phone,
                      keyboardType: TextInputType.phone,
                      decoration: InputDecoration(
                        labelText: strings.phoneNumber,
                        prefixIcon: const Icon(Icons.phone_outlined),
                        // Any format is accepted; the server normalises to E.164.
                        hintText: '0712345678',
                        errorText: _fieldErrors?['phone'],
                      ),
                      validator: (v) =>
                          (v == null || v.trim().isEmpty) ? strings.phoneNumber : null,
                    ),
                    const SizedBox(height: 14),
                    TextFormField(
                      controller: _email,
                      keyboardType: TextInputType.emailAddress,
                      decoration: InputDecoration(
                        labelText: strings.emailOptional,
                        prefixIcon: const Icon(Icons.mail_outline),
                        errorText: _fieldErrors?['email'],
                      ),
                    ),
                    const SizedBox(height: 14),
                    TextFormField(
                      controller: _password,
                      obscureText: true,
                      decoration: InputDecoration(
                        labelText: strings.password,
                        prefixIcon: const Icon(Icons.lock_outline),
                        errorText: _fieldErrors?['password'],
                      ),
                      validator: (v) =>
                          (v == null || v.length < 8) ? strings.passwordTooShort : null,
                    ),

                    const SizedBox(height: 10),
                    SwitchListTile(
                      value: _asResponder,
                      onChanged: (value) => setState(() => _asResponder = value),
                      title: Text(strings.registerAsResponder),
                      subtitle: Text(
                        strings.awaitingVerificationHint,
                        style: const TextStyle(fontSize: 12, color: AppTheme.textMuted),
                      ),
                      contentPadding: EdgeInsets.zero,
                    ),
                    if (_asResponder) ...[
                      const SizedBox(height: 6),
                      TextFormField(
                        controller: _organisation,
                        decoration: InputDecoration(
                          labelText: strings.organisation,
                          prefixIcon: const Icon(Icons.shield_outlined),
                          errorText: _fieldErrors?['organization'],
                        ),
                        validator: (v) => (_asResponder && (v == null || v.trim().isEmpty))
                            ? strings.organisation
                            : null,
                      ),
                    ],

                    const SizedBox(height: 22),
                    FilledButton(
                      onPressed: _busy ? null : _submit,
                      child: _busy
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2.2,
                                color: Colors.white,
                              ),
                            )
                          : Text(strings.createAccount),
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: _busy ? null : () => Navigator.of(context).pop(),
                      child: Text(strings.haveAnAccount),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
