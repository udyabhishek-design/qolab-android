import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/chat/chat_socket.dart';
import 'package:qolab_app/chat/reverb_client.dart';

/// Phase 4A.2.2 regression tests for foreground-resume sync (RC2):
/// - resume() with ZERO listeners still fires onResync (REST catch-up)
/// - resume() with zero listeners does NOT open a WebSocket (nothing desired)
/// - resume() with desired subscriptions while disconnected DOES reconnect
class _RecordingClient extends Fake implements ReverbClient {
  int connectCalls = 0;
  bool connected = false;
  final List<String> desired = [];
  final _events = StreamController<BroadcastEvent>.broadcast();
  final _resubscribed = StreamController<void>.broadcast();

  @override
  Stream<BroadcastEvent> get events => _events.stream;

  @override
  Stream<void> get resubscribed => _resubscribed.stream;

  @override
  bool get isConnected => connected;

  @override
  bool get hasDesiredSubscriptions => desired.isNotEmpty;

  @override
  void trackSubscription(String channel) => desired.add(channel);

  @override
  void untrackSubscription(String channel) => desired.remove(channel);

  @override
  void disconnect() => connected = false;

  @override
  void dispose() {
    _events.close();
    _resubscribed.close();
  }

  @override
  Future<void> connect() async {
    connectCalls++;
    connected = true;
  }
}

void main() {
  test('resume() with ZERO listeners fires onResync but does NOT connect',
      () async {
    final client = _RecordingClient();
    final service = ChatSocketService(api: null, appKey: 'K', client: client);
    var resyncs = 0;
    final sub = service.onResync.listen((_) => resyncs++);

    service.resume(); // previously: silent early-return

    await Future<void>.delayed(Duration.zero);
    expect(resyncs, 1, reason: 'onResync must fire even with no listeners');
    expect(client.connectCalls, 0,
        reason: 'no WebSocket without desired subscriptions');
    await sub.cancel();
    service.dispose();
  });

  test('resume() with subscriptions, disconnected → reconnect + onResync',
      () async {
    final client = _RecordingClient();
    final service = ChatSocketService(api: null, appKey: 'K', client: client);
    var resyncs = 0;
    final sub = service.onResync.listen((_) => resyncs++);
    service.subscribe('channel.5', (_) {});
    expect(client.desired, contains('private-channel.5'));

    service.resume();

    await Future<void>.delayed(Duration.zero);
    expect(client.connectCalls, 1, reason: 'reconnect bypasses backoff timer');
    expect(resyncs, 1);
    await sub.cancel();
    service.dispose();
  });

  test('resume() while already connected fires onResync, no second connect',
      () async {
    final client = _RecordingClient()..connected = true;
    final service = ChatSocketService(api: null, appKey: 'K', client: client);
    service.subscribe('channel.5', (_) {});
    var resyncs = 0;
    final sub = service.onResync.listen((_) => resyncs++);

    service.resume();

    await Future<void>.delayed(Duration.zero);
    expect(resyncs, 1);
    expect(client.connectCalls, 0,
        reason: 'healthy connection must not be re-dialed');
    await sub.cancel();
    service.dispose();
  });
}

// Fake from package:flutter_test is re-exported via flutter_test.
