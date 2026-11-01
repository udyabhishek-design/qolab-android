import 'dart:async';
import 'dart:ui';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/services.dart';

import '../api/qolab_api.dart';

/// Phase C3A — Firebase Cloud Messaging bridge for incoming calls.
///
/// Responsibilities:
///  - initialize Firebase (must be the FIRST thing at app start, before
///    any other Firebase call);
///  - request POST_NOTIFICATIONS permission (Android 13+);
///  - register/refresh the FCM device token with the backend after login
///    (idempotent upsert; backend supports multiple devices per Member);
///  - unregister on logout;
///  - route incoming `call.incoming` / `call.update` data messages to the
///    [DirectCallCoordinator] via [onPush] — foreground messages here,
///    background/terminated via the top-level background handler writing
///    to a static stream the coordinator drains on app resume;
///  - deliver native notification-button actions (accept/decline) that
///    arrived while the engine was cold or live.
///
/// NEVER handles LiveKit tokens — all call state is fetched authenticated
/// from GET /chat/calls/{id}; the server is the source of truth.
class FcmBridge {
  static const MethodChannel _actionsChannel = MethodChannel('qolab/call_actions');

  /// Callback invoked for every call-related push event, in-app or
  /// recovered after process death. `payload` = FCM data map.
  static void Function(Map<String, dynamic> payload)? onPush;

  /// Native accept/decline actions (notification buttons). The
  /// coordinator authenticates and performs the action.
  static void Function(String action, Map<String, dynamic> data)? onAction;

  static String? _token;
  static bool _initialized = false;
  static bool _permissionGranted = false;

  // ── lifecycle ───────────────────────────────────────────────

  static Future<void> initialize() async {
    if (_initialized) return;
    await Firebase.initializeApp();
    _initialized = true;

    // Native → Dart action sink.
    _actionsChannel.setMethodCallHandler((call) async {
      if (call.method == 'callAction' && call.arguments is Map) {
        final args = Map<String, dynamic>.from(call.arguments as Map);
        _handleAction(args);
      }
      return null;
    });
    // Drain any action captured while the engine was cold.
    final pending = await _actionsChannel.invokeMethod<Object?>('takePending');
    if (pending is Map) {
      _handleAction(Map<String, dynamic>.from(pending));
    }

    // Foreground messages (app open, screen on).
    FirebaseMessaging.onMessage.listen((RemoteMessage message) {
      _dispatch(message.data);
    });

    // Message opened the app (user tapped notification) — recover state.
    FirebaseMessaging.onMessageOpenedApp.listen((RemoteMessage message) {
      _dispatch(message.data);
    });

    // Token rotation while running.
    FirebaseMessaging.instance.onTokenRefresh.listen((token) {
      _token = token;
      // Re-register next time register() is called; also fire now if a
      // registration loop is active (api available).
      _registerQuiet();
    });

    // Message delivered while app was terminated: cleared on read.
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) {
      _dispatch(initial.data);
    }
  }

  /// Ask for Android 13+ POST_NOTIFICATIONS. Returns true when
  /// notifications can be shown.
  static Future<bool> requestPermission() async {
    if (!_initialized) return false;
    final settings = await FirebaseMessaging.instance.requestPermission(
      alert: true,
      badge: true,
      sound: true,
      criticalAlert: false,
    );
    _permissionGranted =
        settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
    return _permissionGranted;
  }

  /// Fetch + register the device token with the backend. Safe to call
  /// repeatedly (backend upserts on user_id+token).
  static Future<bool> register(QolabApi api) async {
    if (!_initialized) return false;
    try {
      _token ??= await FirebaseMessaging.instance.getToken();
      if (_token == null) return false;
      final res = await api.registerDeviceToken(_token!);
      return res;
    } catch (_) {
      return false;
    }
  }

  /// Logout cleanup. Fails closed: if Firebase was never initialized
  /// (logout before init, or a test env) there is no token to unregister.
  static Future<void> unregister(QolabApi api) async {
    String? token;
    try {
      token = _token ?? await FirebaseMessaging.instance.getToken();
    } catch (_) {
      // [core/no-app] or messaging unavailable — nothing to unregister.
      _token = null;
      return;
    }
    if (token == null) return;
    try {
      await api.unregisterDeviceToken(token);
    } catch (_) {
      // best-effort; backend also prunes dead tokens on 404 UNREGISTERED
    }
    _token = null;
  }

  static bool get permissionGranted => _permissionGranted;
  static String? get token => _token;

  // ── internals ───────────────────────────────────────────────

  static void _dispatch(Map<String, dynamic> data) {
    final type = data['type'] as String?;
    if (type == null) return;
    if (type == 'call.incoming' || type == 'call.update') {
      onPush?.call(data);
    }
  }

  static void _handleAction(Map<String, dynamic> args) {
    final action = args['action'] as String?;
    if (action == null) return;
    onAction?.call(action, args);
  }

  static QolabApi? _registerApi;

  /// Set by the shell after login so token-refresh re-registration works.
  static void attachApi(QolabApi? api) => _registerApi = api;

  static Future<void> _registerQuiet() async {
    final api = _registerApi;
    final token = _token;
    if (api == null || token == null) return;
    try {
      await api.registerDeviceToken(token);
    } catch (_) {}
  }
}

/// Top-level background handler (app backgrounded/killed and a data-only
/// message arrives with the app still resident in a background isolate).
/// MUST be a top-level function.
@pragma('vm:entry-point')
Future<void> firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
  // Native QolabFirebaseMessagingService already shows the notification
  // and drives ringtone for call.incoming; nothing further here. Keeping
  // the handler registered ensures data messages are delivered promptly.
}
