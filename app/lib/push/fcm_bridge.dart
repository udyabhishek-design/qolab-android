import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
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
typedef _PushMap = Map<String, dynamic>;

class FcmBridge {
  static const MethodChannel _actionsChannel = MethodChannel('qolab/call_actions');

  static final List<_PushMap> _pendingPushes = [];
  static final List<_PushMap> _pendingActions = [];

  static void Function(Map<String, dynamic> payload)? _onPush;
  /// Callback invoked for every call-related push event, in-app or
  /// recovered after process death. `payload` = FCM data map.
  static void Function(Map<String, dynamic> payload)? get onPush => _onPush;
  static set onPush(void Function(Map<String, dynamic> payload)? handler) {
    _onPush = handler;
    if (handler != null && _pendingPushes.isNotEmpty) {
      final queued = List<_PushMap>.from(_pendingPushes);
      _pendingPushes.clear();
      for (final p in queued) {
        handler(p);
      }
    }
  }

  static void Function(String action, Map<String, dynamic> data)? _onAction;
  /// Native accept/decline actions (notification buttons). The
  /// coordinator authenticates and performs the action.
  static void Function(String action, Map<String, dynamic> data)? get onAction => _onAction;
  static set onAction(void Function(String action, Map<String, dynamic> data)? handler) {
    _onAction = handler;
    if (handler != null) {
      if (_pendingActions.isNotEmpty) {
        final queued = List<_PushMap>.from(_pendingActions);
        _pendingActions.clear();
        for (final args in queued) {
          final action = args['action'] as String?;
          if (action != null) handler(action, args);
        }
      }
      unawaited(_drainNativePending());
    }
  }

  static Future<void> _drainNativePending() async {
    try {
      final pending = await _actionsChannel.invokeMethod<Object?>('takePending');
      if (pending is Map) {
        _handleAction(Map<String, dynamic>.from(pending));
      }
    } catch (_) {}
  }

  static String? _token;
  static bool _initialized = false;
  static bool _permissionGranted = false;

  /// Completes when initialize() finishes (successfully or degraded).
  static final Completer<void> _initDone = Completer<void>();

  /// Hard cap on Firebase init. A runtime with broken/absent Google Play
  /// services (seen on emulators like BlueStacks) can make
  /// Firebase.initializeApp() hang for minutes rather than throw; boot
  /// must never depend on it resolving.
  static const _initTimeout = Duration(seconds: 10);

  // ── lifecycle ───────────────────────────────────────────────

  /// Guarded init: never throws, never hangs the caller. Firebase is
  /// either ready, permanently degraded, or the wait is abandoned at
  /// [_initTimeout] — in every case the app continues without push.
  static Future<void> initializeGuarded() async {
    if (_initialized) return;
    // Native action channel FIRST and unconditionally: it must work even
    // when Firebase is degraded (no Play services), otherwise cold-start
    // accept/decline actions would be lost on those devices.
    await _wireActions();
    try {
      await Firebase.initializeApp().timeout(_initTimeout);
      await _wireAfterInit();
      _initialized = true;
    } catch (e) {
      // Firebase unavailable (no config / no Play services / hung init).
      // Calling falls back to Reverb foreground delivery; the server
      // state remains authoritative. Never rethrow — boot continues.
      _initialized = false;
      debugPrint('QOLAB FCM init degraded: $e');
    } finally {
      if (!_initDone.isCompleted) _initDone.complete();
    }
  }

  /// Legacy entry kept for tests: same guarded semantics.
  static Future<void> initialize() async => initializeGuarded();

  /// Native → Dart action sink. Independent of Firebase: v1.5.62's
  /// MainActivity registers the native half of 'qolab/call_actions' in
  /// configureFlutterEngine, so this always has a counterpart.
  static Future<void> _wireActions() async {
    _actionsChannel.setMethodCallHandler((call) async {
      if (call.method == 'callAction' && call.arguments is Map) {
        final args = Map<String, dynamic>.from(call.arguments as Map);
        _handleAction(args);
      }
      return null;
    });
    try {
      // Drain any action captured while the engine was cold.
      final pending = await _actionsChannel.invokeMethod<Object?>('takePending');
      if (pending is Map) {
        _handleAction(Map<String, dynamic>.from(pending));
      }
    } on MissingPluginException {
      // Engine without the native half (tests) — fail closed.
    }
  }

  static Future<void> _wireAfterInit() async {
    _initialized = true;

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
  ///
  /// register() now waits (bounded by the same [_initTimeout] budget) for
  /// a still-running guarded init, so a login that lands during a slow
  /// Firebase init doesn't silently miss token registration.
  static Future<bool> register(QolabApi api) async {
    if (_initialized) return _registerNow(api);
    // Init still in flight (or failed fast). Give it a chance to finish —
    // real devices with healthy Play services init in well under a second.
    try {
      await _initDone.future.timeout(_initTimeout);
    } catch (_) {}
    if (!_initialized) return false;
    return _registerNow(api);
  }

  static Future<bool> _registerNow(QolabApi api) async {
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
      final handler = _onPush;
      if (handler != null) {
        handler(data);
      } else {
        _pendingPushes.add(Map<String, dynamic>.from(data));
      }
    }
  }

  static void _handleAction(Map<String, dynamic> args) {
    final action = args['action'] as String?;
    if (action == null) return;
    final handler = _onAction;
    if (handler != null) {
      handler(action, args);
    } else {
      _pendingActions.add(Map<String, dynamic>.from(args));
    }
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
