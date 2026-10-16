import 'package:flutter/material.dart';

import '../api/api_errors.dart';
import '../auth/auth_controller.dart';
import '../theme.dart';

/// Native Qolab login screen (warm-light design).
///
/// States: form validation, in-flight disable, credential errors, 419/422/
/// 429/5xx/network messaging — no raw exceptions, no auto-retry of failed
/// password submissions.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.auth});

  final AuthController auth;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _obscure = true;
  String? _banner;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    // Prevent duplicate submissions while a request is in flight.
    if (widget.auth.busy) return;
    final form = _formKey.currentState;
    if (form == null || !form.validate()) return;
    setState(() => _banner = null);
    try {
      await widget.auth.login(_email.text.trim(), _password.text);
      if (mounted) {
        // AuthController drives navigation via the gate; nothing to do here.
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _banner = e.message);
    } catch (_) {
      if (mounted) {
        setState(() => _banner =
            'Something went wrong. Please check your connection and try again.');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = widget.auth;
    return AnimatedBuilder(
      animation: auth,
      builder: (context, _) {
        // In flight either when the controller is busy or already signing in
        // (status flips before the first notifyListeners in subclasses).
        final inFlight =
            auth.busy || auth.status == AuthStatus.signingIn;
        return Scaffold(
          backgroundColor: QolabTheme.canvas,
          body: SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding:
                    const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 400),
                  child: Form(
                    key: _formKey,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const SizedBox(height: 32),
                        _brand(),
                        const SizedBox(height: 40),
                        if (_banner != null) _errorBanner(_banner!),
                        if (_banner != null) const SizedBox(height: 16),
                        TextFormField(
                          controller: _email,
                          enabled: !inFlight,
                          keyboardType: TextInputType.emailAddress,
                          autofillHints: const [AutofillHints.email],
                          decoration: QolabTheme.field(
                              label: 'Email', hint: 'you@company.com'),
                          validator: _validateEmail,
                          onFieldSubmitted: (_) => _submit(),
                        ),
                        const SizedBox(height: 16),
                        TextFormField(
                          controller: _password,
                          enabled: !inFlight,
                          obscureText: _obscure,
                          autofillHints: const [AutofillHints.password],
                          decoration: QolabTheme.field(
                            label: 'Password',
                            hint: 'Your password',
                            suffix: IconButton(
                              icon: Icon(
                                _obscure
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                                color: QolabTheme.textSoft,
                              ),
                              onPressed: inFlight
                                  ? null
                                  : () => setState(
                                      () => _obscure = !_obscure),
                            ),
                          ),
                          validator: _validatePassword,
                          onFieldSubmitted: (_) => _submit(),
                        ),
                        const SizedBox(height: 28),
                        _loginButton(inFlight),
                        const SizedBox(height: 24),
                        const Text(
                          'Use your existing Qolab account.',
                          textAlign: TextAlign.center,
                          style: QolabTheme.caption,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _brand() => Column(
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              color: QolabTheme.wine,
              borderRadius: BorderRadius.circular(18),
            ),
            child: const Center(
              child: Text('Q',
                  style: TextStyle(
                      color: Colors.white,
                      fontSize: 32,
                      fontWeight: FontWeight.w700)),
            ),
          ),
          const SizedBox(height: 16),
          const Text('Qolab', style: QolabTheme.headline),
          const SizedBox(height: 6),
          const Text('Sign in to your workspace',
              style: QolabTheme.caption),
        ],
      );

  Widget _errorBanner(String message) => Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFFFDECEC),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0xFFF2C4C4)),
        ),
        child: Row(
          children: [
            const Icon(Icons.error_outline,
                color: Color(0xFF9B1C1C), size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Text(message,
                  style: const TextStyle(color: Color(0xFF9B1C1C))),
            ),
          ],
        ),
      );

  Widget _loginButton(bool inFlight) => SizedBox(
        height: 52,
        child: ElevatedButton(
          onPressed: inFlight ? null : _submit,
          style: ElevatedButton.styleFrom(
            backgroundColor: QolabTheme.wine,
            disabledBackgroundColor: QolabTheme.wineFaded,
            foregroundColor: Colors.white,
            disabledForegroundColor: Colors.white70,
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14)),
            elevation: 0,
          ),
          child: inFlight
              ? const SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(
                      strokeWidth: 2.4, color: Colors.white))
              : const Text('Sign in',
                  style:
                      TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
        ),
      );

  String? _validateEmail(String? v) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return 'Email is required.';
    if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(t)) {
      return 'Enter a valid email address.';
    }
    return null;
  }

  String? _validatePassword(String? v) =>
      (v == null || v.isEmpty) ? 'Password is required.' : null;
}
