// Live Development Reverb integration check (manual, NOT part of CI).
//
//   dart run tool/dev_reverb_check.dart
//
// Reads QOLAB_DEV_EMAIL / QOLAB_DEV_PASSWORD from the environment.
// Safe: read-only + one throwaway message in a QA DM channel.
import 'dart:async';
import 'dart:io';

import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/broadcast_auth.dart';
import 'package:qolab_app/chat/reverb_client.dart';

Future<void> main(List<String> args) async {
  final email = Platform.environment['QOLAB_DEV_EMAIL'];
  final password = Platform.environment['QOLAB_DEV_PASSWORD'];
  const origin = 'https://workpulse-gbpedt.drytis.dev';
  const appKey = 'E6bKH3QNfxtlNpk7ByiX';

  if (email == null || password == null) {
    stderr.writeln('QOLAB_DEV_EMAIL / QOLAB_DEV_PASSWORD required');
    exitCode = 2;
    return;
  }

  final api = QolabApi(origin: origin);
  await api.bootstrapSession();
  await api.login(email: email, password: password);
  final me = await api.fetchCurrentUser();
  stdout.writeln('1 login OK (user ${me.id})');

  // 1) Form-encoded broadcast auth for the user's private channel.
  var channelTarget = '';
  final auth = SessionBroadcastAuth(api);
  final authStr = await auth.authorize(
      channel: 'private-user.${me.id}', socketId: '123.456');
  if (!authStr.contains(appKey)) {
    stderr.writeln('FAIL: auth string does not embed the app key: redacted');
    exitCode = 1;
    return;
  }
  stdout.writeln('2 broadcast auth OK (form-encoded, private-user.*)');

  // 2) Real WSS connection + subscriptions.
  final client = ReverbClient(
      wsUrl: origin.replaceFirst('https://', 'wss://'),
      appKey: appKey,
      auth: auth);
  final connected = Completer<void>();
  final userEvent = Completer<void>();
  final messageSent = Completer<void>();
  int channelSubOk = 0;

  late final StreamSubscription<ReverbConnectionState> stateSub;
  stateSub = client.stateChanges.listen((s) {
    if (s == ReverbConnectionState.connected && !connected.isCompleted) {
      connected.complete();
    }
  });
  late final StreamSubscription<BroadcastEvent> evSub;
  evSub = client.events.listen((e) {
    if (e.channel == 'private-user.${me.id}' && !userEvent.isCompleted) {
      userEvent.complete();
    }
    if (e.name == 'message.sent' &&
        !messageSent.isCompleted &&
        channelSubOk == 1 &&
        e.channel == channelTarget) {
      messageSent.complete();
    }
  });

  client.connect();
  await connected.future.timeout(const Duration(seconds: 20),
      onTimeout: () => _fail('Reverb connection never established'));
  stdout.writeln('3 WSS connection established');

  client.trackSubscription('private-user.${me.id}');
  final deadline = DateTime.now().add(const Duration(seconds: 20));
  while (!client.subscribedChannels.contains('private-user.${me.id}')) {
    if (DateTime.now().isAfter(deadline)) {
      _fail('private-user subscription never confirmed');
    }
    await Future<void>.delayed(const Duration(milliseconds: 200));
  }
  stdout.writeln('4 private-user subscription confirmed');

  // 3) Trigger a real event via REST send and watch for message.sent.
  final counts = await api.fetchChatUnreadCounts();
  if (counts.isEmpty) _fail('unread-counts empty');
  channelTarget = 'private-channel.${counts.keys.first}';
  client.trackSubscription(channelTarget);
  final deadline2 = DateTime.now().add(const Duration(seconds: 20));
  while (!client.subscribedChannels.contains(channelTarget)) {
    if (DateTime.now().isAfter(deadline2)) {
      _fail('channel subscription never confirmed');
    }
    await Future<void>.delayed(const Duration(milliseconds: 200));
  }
  channelSubOk = 1;
  stdout.writeln('5 channel subscription confirmed ($channelTarget)');

  final stamp = DateTime.now().millisecondsSinceEpoch;
  await api.sendMessageJson(
      counts.keys.first, 'reverb-integration-check $stamp');
  stdout.writeln('6 REST send OK');

  await messageSent.future.timeout(const Duration(seconds: 25),
      onTimeout: () => _fail('message.sent never arrived on the socket'));
  stdout.writeln('7 REALTIME OK: message.sent received over Reverb');

  await evSub.cancel();
  await stateSub.cancel();
  client.dispose();
  await api.logout();
  stdout.writeln('ALL LIVE CHECKS PASSED');
}

Never _fail(String msg) {
  stderr.writeln('FAIL: $msg');
  exit(1);
}
