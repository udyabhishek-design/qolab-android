import 'package:flutter/material.dart';

import 'api/qolab_api.dart';
import 'auth/auth_controller.dart';
import 'auth/session_store.dart';
import 'auth_gate.dart';
import 'home/session_socket.dart';

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
  final socket = QolabChatSocket(api: api, origin: origin);
  runApp(QolabApp(auth: auth, socket: socket));
}

class QolabApp extends StatelessWidget {
  const QolabApp({super.key, required this.auth, required this.socket});

  final AuthController auth;
  final QolabChatSocket socket;

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
      home: _Bootstrap(auth: auth, socket: socket),
    );
  }
}

/// Runs the restore once, then gates on auth status.
class _Bootstrap extends StatefulWidget {
  const _Bootstrap({required this.auth, required this.socket});

  final AuthController auth;
  final QolabChatSocket socket;

  @override
  State<_Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<_Bootstrap> {
  @override
  void initState() {
    super.initState();
    // Socket lifecycle strictly follows auth state: connect on signedIn,
    // stop on signedOut/expired. Never before login, never after logout.
    widget.auth.addListener(_onAuthChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      widget.auth.restore();
    });
  }

  void _onAuthChanged() {
    final status = widget.auth.status;
    if (status == AuthStatus.signedIn) {
      widget.socket.start();
    } else if (status == AuthStatus.signedOut) {
      widget.socket.stop();
    }
    // offline/unknown/restoring/signingIn: leave the socket as-is; a later
    // state change resolves it (retry keeps the session, signout stops it).
  }

  @override
  void dispose() {
    widget.auth.removeListener(_onAuthChanged);
    widget.socket.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AuthGate(auth: widget.auth, socket: widget.socket);
}
