import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/api_errors.dart';
import '../api/cookie_jar.dart';
import '../api/inertia.dart';
import '../api/qolab_api.dart';
import 'session_store.dart';

enum AuthStatus { unknown, restoring, signedOut, signingIn, signedIn, offline }

/// Authentication state machine.
///
/// - login(): bootstrap CSRF → POST /login → fetch identity → persist.
/// - restore(): load stored cookies → validate against /dashboard →
///   signedIn (valid) | signedOut (expired/invalid) | offline (network
///   unavailable — stored session KEPT, user offered retry).
/// - logout(): clear local state IMMEDIATELY (UI switches to Login), then
///   run a bounded, isolated POST /logout in the background. Generation
///   counter prevents stale operations from touching a newer session.
class AuthController extends ChangeNotifier {
  AuthController({required this.api, required this.store});

  final QolabApi api;
  final SessionStore store;

  AuthStatus status = AuthStatus.unknown;
  QolabUser? user;
  bool _busy = false;

  /// Monotonic auth generation. Incremented on every local state clear
  /// (logout) so any still-in-flight server operation from an older
  /// generation cannot mutate cookies/storage of a newer session.
  int _generation = 0;

  bool get busy => _busy;

  /// Sign in with email/password. Throws [ApiException] with a user-safe
  /// message on failure — callers surface it; they never rethrow raw errors.
  Future<void> login(String email, String password) async {
    if (_busy) return; // duplicate submission prevention
    _busy = true;
    status = AuthStatus.signingIn;
    notifyListeners();
    try {
      // 419-safe flow: bootstrap first, then submit.
      await api.bootstrapSession();
      await api.login(email: email, password: password);
      final me = await api.fetchCurrentUser();
      user = me;
      status = AuthStatus.signedIn;
      await store.save(jar: api.cookies, user: me);
    } on ApiException {
      status = AuthStatus.signedOut;
      rethrow;
    } catch (_) {
      status = AuthStatus.signedOut;
      rethrow;
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  /// Restore on app start. Connectivity failure → offline (session kept).
  Future<void> restore() async {
    if (_busy) return;
    _busy = true;
    status = AuthStatus.restoring;
    notifyListeners();
    try {
      final saved = await store.load();
      if (saved == null || !saved.jar.hasSessionCookie) {
        status = AuthStatus.signedOut;
        return;
      }
      // Rehydrate the client's cookie jar with the stored cookies.
      _adoptJar(saved.jar);
      try {
        final me = await api.validateSession();
        user = me;
        status = AuthStatus.signedIn;
        // Persist possibly rotated cookies.
        await store.save(jar: api.cookies, user: me);
      } on ApiException catch (e) {
        if (e.kind == ApiErrorKind.network || e.kind == ApiErrorKind.timeout) {
          status = AuthStatus.offline; // keep stored session, offer retry
        } else {
          // Expired/invalid — clear and show Login.
          await store.clear();
          api.cookies.clear();
          user = null;
          status = AuthStatus.signedOut;
        }
      }
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  /// Sign out.
  ///
  /// Design (fixes: multi-tap logout, Home lingering ~90s, zombie logout
  /// clearing a re-login):
  /// 1. Single-flight: a second tap while a logout is in flight is ignored.
  /// 2. LOCAL state is cleared and the UI notified IMMEDIATELY — the user
  ///    sees Login at once and repeated taps are impossible.
  /// 3. The server POST /logout runs in the background, bounded by 8s, on a
  ///    dedicated connection with a cookie snapshot. It never touches the
  ///    shared jar, so a late (or timed-out) response cannot overwrite a
  ///    newer session's cookies — no cookie/state races with re-login.
  /// 4. Local cleanup completes before the background task is spawned, so
  ///    the store can never hold the OLD session again after this returns.
  ///
  /// Limitation (documented, not hidden): if the server logout cannot be
  /// confirmed within the bound (slow/unreachable backend), the device is
  /// signed out but the server-side session lives until its natural
  /// expiry — we do NOT claim confirmed server invalidation.
  Future<void> logout() async {
    if (_busy) return; // single-flight: rapid taps → one operation
    _busy = true;
    notifyListeners();

    final gen = ++_generation;

    // Fire the server logout FIRST: QolabApi.logout() runs synchronously up
    // to its first await, capturing the cookie/CSRF snapshot BEFORE any
    // local clearing below — the request keeps the session's cookies
    // intact as Laravel expects. catchError is attached immediately so no
    // unhandled async error can escape if it fails while we clean up.
    final serverLogout = api.logout().catchError((Object _) {
      // Network failure / timeout / 5xx — local sign-out still proceeds.
    });

    // ---- Local cleanup (must fully succeed before reporting signedOut).
    await store.clear();
    api.cookies.clear();
    user = null;
    status = AuthStatus.signedOut;
    notifyListeners();

    if (gen != _generation) return; // a newer logout already superseded us

    // The bounded, isolated server logout continues in the background.
    unawaited(serverLogout);

    // Release the busy flag SYNCHRONOUSLY before this future completes so
    // an immediately following login() is never swallowed by the guard.
    _busy = false;
    notifyListeners();
  }

  void _adoptJar(CookieJar jar) => api.cookies.adoptFrom(jar);
}
