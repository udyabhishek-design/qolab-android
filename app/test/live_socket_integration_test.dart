// Live integration test (run manually — NOT part of the default suite):
// exercises the REAL production classes (QolabApi, ChatSocketService,
// ReverbClient, SessionBroadcastAuth) against the Development backend.
//
//   flutter test test/live_socket_integration_test.dart --dart-define=QA_A_EMAIL=... --dart-define=QA_A_PASS=... --dart-define=QA_B_EMAIL=... --dart-define=QA_B_PASS=...
//
// Sanitized: only connection/subscription/event-name assertions; no
// credentials, cookies, or message bodies are printed.
@Timeout(Duration(minutes: 2))
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_socket.dart';
import 'package:qolab_app/chat/reverb_client.dart';

const origin = 'https://workpulse-gbpedt.drytis.dev';
const appKey = 'E6bKH3QNfxtlNpk7ByiX';

bool get _configured =>
    const String.fromEnvironment('QA_A_EMAIL') != '' &&
    const String.fromEnvironment('QA_B_EMAIL') != '';

void main() {
  if (!_configured) {
    // Skipped silently in normal CI runs (no credentials defined).
    test('live socket integration (skipped: no QA credentials defined)',
        () {}, timeout: const Timeout(Duration(seconds: 1)));
    return;
  }

  test('real ChatSocketService delivers cross-session message.sent',
      () async {
    final a = QolabApi(origin: origin);
    final b = QolabApi(origin: origin);
    await a.bootstrapSession();
    await a.login(
        email: const String.fromEnvironment('QA_A_EMAIL'),
        password: const String.fromEnvironment('QA_A_PASS'));
    await b.bootstrapSession();
    await b.login(
        email: const String.fromEnvironment('QA_B_EMAIL'),
        password: const String.fromEnvironment('QA_B_PASS'));
    final userA = await a.fetchCurrentUser();
    final userB = await b.fetchCurrentUser();
    // ignore: avoid_print
    print('probe: logged in uidA=${userA.id} uidB=${userB.id}');

    final convId = await a.startDm(userB.id);
    // ignore: avoid_print
    print('probe: conversation=$convId');

    // The PRODUCTION socket service, exactly as the app builds it.
    final socketB = ChatSocketService(
      api: b,
      appKey: appKey,
      wsOrigin: origin.replaceFirst('https://', 'wss://'),
    );
    final eventsB = <BroadcastEvent>[];
    socketB.start();
    final cancel1 =
        socketB.subscribe('user.${userB.id}', eventsB.add);
    final cancel2 =
        socketB.subscribe('channel.$convId', eventsB.add);

    // Wait for the private-channel subscription to be confirmed live.
    final ok = await waitFor(15, () => socketB.state == ReverbConnectionState.connected);
    expect(ok, isTrue, reason: 'socket B should connect');
    // Give the private-channel auth + subscribe + server confirmation time
    // to complete BEFORE sending (mirrors real usage where the conversation
    // is open for seconds/minutes before a message arrives).
    await Future<void>.delayed(const Duration(seconds: 5));
    // ignore: avoid_print
    print('probe: connected=${socketB.isConnected} '
        'desired=${socketB.subscribedChannels.toList()} '
        'authFailed=${socketB.authFailedChannels.toList()}');

    // Send from A (REST, like Qolab Web would).
    await a.sendMessageJson(convId, 'live-integration ${DateTime.now()}');

    var got = await waitFor(15, () => eventsB
        .any((e) => e.channel == 'private-channel.$convId' && e.name.endsWith('message.sent')));
    if (!got) {
      // Keep listening a little longer — late delivery is a bug too, but
      // distinguish "never" from "slow".
      await Future<void>.delayed(const Duration(seconds: 5));
      got = eventsB.any((e) =>
          e.channel == 'private-channel.$convId' && e.name.endsWith('message.sent'));
    }
    // ignore: avoid_print
    print('probe: message.sent delivered to B = $got '
        '(events seen: ${eventsB.map((e) => '${e.channel}:${e.name}').toList()})');
    expect(got, isTrue,
      reason: 'production ChatSocketService must deliver .message.sent '
          'on private-channel.$convId without manual refresh');

    final notif = await waitFor(15, () => eventsB.any(
        (e) => e.channel == 'private-user.${userB.id}' && e.name.endsWith('notification.received')));
    // ignore: avoid_print
    print('probe: notification.received delivered to B = $notif');
    expect(notif, isTrue, reason: 'user-channel notification must arrive');

    cancel1();
    cancel2();
    socketB.dispose();
    a.dispose();
    b.dispose();
  });
}

Future<bool> waitFor(int seconds, bool Function() cond) async {
  final deadline = DateTime.now().add(Duration(seconds: seconds));
  while (DateTime.now().isBefore(deadline)) {
    if (cond()) return true;
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  return cond();
}

// silence unused-import warnings for dart:convert/io in some configs
// ignore: unused_element
final _ = jsonDecode;
// ignore: unused_element
final __ = HttpClient;
