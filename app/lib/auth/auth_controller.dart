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
/// - logout(): POST /logout (best-effort) → always clear local state.
class AuthController extends ChangeNotifier {
  AuthController({required this.api, required this.store});

  final QolabApi api;
  final SessionStore store;

  AuthStatus status = AuthStatus.unknown;
  QolabUser? user;
  bool _busy = false;

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

  Future<void> logout() async {
    // Best-effort server logout; local state cleared regardless.
    try {
      await api.logout();
    } catch (_) {
      // Network failure does not block local sign-out.
    }
    await store.clear();
    api.cookies.clear();
    user = null;
    status = AuthStatus.signedOut;
    notifyListeners();
  }

  void _adoptJar(CookieJar jar) => api.cookies.adoptFrom(jar);
}
