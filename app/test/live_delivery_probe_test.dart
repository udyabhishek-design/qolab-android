library;

import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter_test/flutter_test.dart';

import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';
import 'package:qolab_app/home/session_socket.dart';

/// Phase 4A.2.2 LIVE delivery probe (manual; auto-skips without creds).
///
/// Runs the app's OWN protocol stack (QolabApi → AuthController →
/// QolabChatSocket.start → subscribe) against the live Development
/// deployment and verifies, with a DIFFERENT sender account, that:
///   connection → private-channel subscription → message.sent receipt
///
/// CI: skipped automatically (no QOLAB_PASSWORD_A/B in env). Run locally:
///   QOLAB_PASSWORD_A=... QOLAB_PASSWORD_B=... flutter test \
///     test/live_delivery_probe_test.dart
///
/// Logs channel/event names and stages ONLY — never credentials, tokens,
/// auth strings, or message bodies.
void main() {
  const origin = 'https://workpulse-gbpedt.drytis.dev';
  const emailA = 'qa-admin-dev@qolab.test';
  const emailB = 'qa-dj-dev@qolab.test';
  const dmChannelId = 57; // DM between users 5 and 6 (verified in dev DB)

  test('LIVE: different-account Web→Android delivery over the app stack',
      () async {
    final pwdA = Platform.environment['QOLAB_PASSWORD_A'];
    final pwdB = Platform.environment['QOLAB_PASSWORD_B'];
    if (pwdA == null || pwdA.isEmpty || pwdB == null || pwdB.isEmpty) {
      markTestSkipped(
          'live probe only: set QOLAB_PASSWORD_A / QOLAB_PASSWORD_B');
      return;
    }

    void log(String s) => printOnFailure(s); // visible with --reporter or -r expanded

    // ── A (receiver — "the Android app") ─────────────────────────────
    final apiA = QolabApi(origin: origin);
    final authA = AuthController(api: apiA, store: MemorySessionStore());
    await authA.login(emailA, pwdA);
    log('[A] login OK (user ${authA.user?.id})');

    final socket = QolabChatSocket(api: apiA, origin: origin);
    final service = socket.ensure();
    socket.start(); // same call main.dart's auth listener makes

    final connected = Completer<void>();
    final poll = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (service.isConnected && !connected.isCompleted) connected.complete();
    });
    try {
      await connected.future.timeout(const Duration(seconds: 20));
      log('[A] CONNECTION VERIFIED');
    } on TimeoutException {
      fail('socket never connected within 20s (authFailed='
          '${service.authFailedChannels})');
    } finally {
      poll.cancel();
    }

    final gotEvent = Completer<Map<String, Object?>>();
    final cancel = service.subscribe('channel.$dmChannelId', (e) {
      if (e.name.contains('message.sent') && !gotEvent.isCompleted) {
        gotEvent.complete(e.data);
      }
    });
    // Wait for subscription confirmation (subscribe → auth → confirm).
    var subscribed = false;
    for (var i = 0; i < 40 && !subscribed; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 250));
      subscribed = service.subscribedChannels.contains('channel.$dmChannelId');
    }
    log('[A] subscribed: ${service.subscribedChannels} '
        'authFailed: ${service.authFailedChannels}');
    expect(subscribed, isTrue,
        reason: 'private-channel.$dmChannelId subscription must be confirmed');

    // ── B (sender — "Qolab Web", DIFFERENT account) ──────────────────
    final apiB = QolabApi(origin: origin);
    final authB = AuthController(api: apiB, store: MemorySessionStore());
    await authB.login(emailB, pwdB);
    log('[B] login OK (user ${authB.user?.id})');

    await apiB.sendMessageJson(dmChannelId,
        'live delivery probe ${DateTime.now().millisecondsSinceEpoch}');
    log('[B] message sent to channel $dmChannelId');

    // ── Delivery on A ────────────────────────────────────────────────
    final data = await gotEvent.future.timeout(const Duration(seconds: 25));
    log('[A] EVENT RECEIVED: message.sent (id=${data['id']})');
    log('[A] DELIVERY VERIFIED');
    expect(data['channel_id'], dmChannelId);

    cancel();
    socket.dispose();
    apiA.dispose();
    apiB.dispose();
  }, timeout: const Timeout(Duration(minutes: 2)));
}
