import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'api/qolab_api.dart';
import 'auth/auth_controller.dart';
import 'auth/session_store.dart';
import 'auth_gate.dart';
import 'home/session_socket.dart';
import 'push/fcm_bridge.dart';

/// Qolab app bootstrap.
///
/// QOLAB_ORIGIN is baked at build time:
///   flutter build apk --debug \
///     --dart-define=QOLAB_ORIGIN=https://workpulse-gbpedt.drytis.dev
///
/// v1.5.61 boot policy — UI FIRST:
///   The first frame must never wait on Firebase. On runtimes with broken
///   or absent Google Play services (some emulators, sideloaded images),
///   `Firebase.initializeApp()` can hang for minutes instead of throwing.
///   A v1.5.60 boot blocked on that await never called runApp(), leaving
///   the plain-white LaunchTheme on screen forever.
///
///   So: runApp() immediately, initialize FCM concurrently under a hard
///   timeout, and surface anything the platform still throws via
///   runZonedGuarded + FlutterError.onError (logged, never fatal to boot).
Future<void> main() {
  final boot = runZonedGuarded(() async {
    WidgetsFlutterBinding.ensureInitialized();

    // Uncaught framework errors: log loudly but never crash the shell.
    final defaultError = FlutterError.onError;
    FlutterError.onError = (details) {
      debugPrint('QOLAB FlutterError: ${details.exception}');
      defaultError?.call(details);
    };

    const origin = String.fromEnvironment(
      'QOLAB_ORIGIN',
      defaultValue: 'https://workpulse-gbpedt.drytis.dev',
    );
    final api = QolabApi(origin: origin);
    final auth = AuthController(api: api, store: SecureSessionStore());
    final socket = QolabChatSocket(api: api, origin: origin);

    // FCM races with the UI. Fully guarded: a missing google-services
    // config, no Play services, OR a hung init simply degrades calling
    // push to Reverb foreground delivery — the login screen always shows.
    unawaited(FcmBridge.initializeGuarded());

    runApp(QolabApp(auth: auth, socket: socket));
  }, (error, stack) {
    debugPrint('QOLAB uncaught zone error: $error\n$stack');
  });
  return boot ?? Future<void>.value();
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
      // Phase C3A — push registration follows the session. register()
      // internally waits (bounded) for any still-running Firebase init,
      // so a slow init never silently drops the device token.
      FcmBridge.attachApi(widget.auth.api);
      FcmBridge.register(widget.auth.api);
      FcmBridge.requestPermission();
    } else if (status == AuthStatus.signedOut) {
      widget.socket.stop();
      // Logout cleanup — unregister this device's push token.
      FcmBridge.unregister(widget.auth.api);
      FcmBridge.attachApi(null);
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
