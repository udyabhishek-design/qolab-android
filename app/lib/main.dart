import 'package:flutter/material.dart';

import 'api/qolab_api.dart';
import 'auth/auth_controller.dart';
import 'auth/session_store.dart';
import 'auth_gate.dart';

/// Qolab app bootstrap.
///
/// QOLAB_ORIGIN is baked at build time:
///   flutter build apk --debug \
///     --dart-define=QOLAB_ORIGIN=https://workpulse-gbpedt.drytis.dev
void main() {
  const origin = String.fromEnvironment(
    'QOLAB_ORIGIN',
    defaultValue: 'https://workpulse-gbpedt.drytis.dev',
  );
  final api = QolabApi(origin: origin);
  final auth = AuthController(api: api, store: SecureSessionStore());
  runApp(QolabApp(auth: auth));
}

class QolabApp extends StatelessWidget {
  const QolabApp({super.key, required this.auth});

  final AuthController auth;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Qolab',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF8B1E2D),
          surface: const Color(0xFFF7F4EC),
        ),
        scaffoldBackgroundColor: const Color(0xFFF7F4EC),
      ),
      home: _Bootstrap(auth: auth),
    );
  }
}

/// Runs the restore once, then gates on auth status.
class _Bootstrap extends StatefulWidget {
  const _Bootstrap({required this.auth});

  final AuthController auth;

  @override
  State<_Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<_Bootstrap> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      widget.auth.restore();
    });
  }

  @override
  Widget build(BuildContext context) => AuthGate(auth: widget.auth);
}
