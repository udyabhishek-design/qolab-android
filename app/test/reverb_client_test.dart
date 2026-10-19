import 'dart:async';
import 'dart:convert';


import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/broadcast_auth.dart';
import 'package:qolab_app/chat/reverb_client.dart';

import 'qolab_api_test.dart' show MockLaravel;

/// In-memory WebSocket double for ReverbClient tests. Exposes exactly the
/// duck-typed surface ReverbClient calls dynamically (listen/add/close).
class FakeSocket {
  FakeSocket(this.url);

  final String url;
  final _incoming = StreamController<String>.broadcast();
  final List<String> sent = [];
  bool closed = false;

  void listen(void Function(String)? onData,
      {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    _incoming.stream.listen(onData,
        onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }

  void serverSend(Map<String, Object?> msg) =>
      _incoming.add(jsonEncode(msg));

  void add(String data) {
    if (!closed) sent.add(data);
  }

  Future<void> close() async {
    if (closed) return;
    closed = true;
    await _incoming.close();
  }
}

void main() {
  group('ReverbClient', () {
    test('connects, captures socket_id, subscribes a private channel with '
        'form-auth result, and receives events', () async {
      final sockets = <FakeSocket>[];
      final client = ReverbClient(
        wsUrl: 'wss://example.test',
        appKey: 'TESTKEY',
        auth: _OkAuth(),
        socketFactory: (url) async {
          expect(url, 'wss://example.test/app/TESTKEY?protocol=7&client=flutter');
          final s = FakeSocket(url);
          sockets.add(s);
          return s as dynamic;
        },
      );

      client.connect();
      // Server handshake arrives asynchronously — poll briefly.
      await _pumpUntil(() => sockets.isNotEmpty);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      sockets.first.serverSend({
        'event': 'pusher:connection_established',
        'data': {'socket_id': 'sock.123'}
      });
      await _pumpUntil(() => client.state == ReverbConnectionState.connected);
      expect(client.socketId, 'sock.123');

      // Subscribe a private channel: auth must run first.
      client.trackSubscription('private-channel.49');
      await _pumpUntil(
          () => sockets.first.sent.any((m) => m.contains('pusher:subscribe')));
      final subscribe =
          jsonDecode(sockets.first.sent.last) as Map<String, dynamic>;
      expect(subscribe['event'], 'pusher:subscribe');
      expect((subscribe['data'] as Map)['channel'], 'private-channel.49');
      expect((subscribe['data'] as Map)['auth'], 'TESTKEY:sig');

      // Server confirms, then an event arrives.
      sockets.first.serverSend({
        'event': 'pusher_internal:subscription_succeeded',
        'channel': 'private-channel.49',
      });
      final events = <BroadcastEvent>[];
      client.events.listen(events.add);
      sockets.first.serverSend({
        'event': 'message.sent',
        'channel': 'private-channel.49',
        'data': {'id': 9},
      });
      await _pumpUntil(() => events.isNotEmpty);
      expect(events.single.name, 'message.sent');
      expect(events.single.data['id'], 9);

      // Server ping → pong.
      sockets.first.serverSend({'event': 'pusher:ping'});
      await _pumpUntil(() =>
          sockets.first.sent.any((m) => m.contains('pusher:pong')));
      client.dispose();
    });

    test('authorization failure surfaces without aggressive retry', () async {
      final sockets = <FakeSocket>[];
      final client = ReverbClient(
        wsUrl: 'wss://example.test',
        appKey: 'K',
        auth: _FailingAuth(),
        socketFactory: (url) async {
          final s = FakeSocket(url);
          sockets.add(s);
          return s as dynamic;
        },
      );
      client.connect();
      await _pumpUntil(() => sockets.isNotEmpty);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      sockets.first.serverSend({
        'event': 'pusher:connection_established',
        'data': {'socket_id': 's1'}
      });
      await _pumpUntil(() => client.isConnected);

      client.trackSubscription('private-channel.999');
      // No subscribe frame is ever sent; channel recorded as auth-failed.
      await Future<void>.delayed(const Duration(milliseconds: 100));
      expect(
          sockets.first.sent
              .where((m) => m.contains('pusher:subscribe')),
          isEmpty);
      expect(client.authFailed, contains('private-channel.999'));
      client.dispose();
    });

    test('disconnect schedules a bounded exponential reconnect and fires '
        'resubscribed on recovery', () async {
      final sockets = <FakeSocket>[];
      var connectCount = 0;
      final client = ReverbClient(
        wsUrl: 'wss://example.test',
        appKey: 'K',
        auth: _OkAuth(),
        reconnectBase: const Duration(milliseconds: 10),
        reconnectMax: const Duration(milliseconds: 50),
        socketFactory: (url) async {
          connectCount++;
          final s = FakeSocket(url);
          sockets.add(s);
          return s as dynamic;
        },
      );
      client.connect();
      await _pumpUntil(() => sockets.isNotEmpty);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      sockets.first.serverSend({
        'event': 'pusher:connection_established',
        'data': {'socket_id': 'a'}
      });
      await _pumpUntil(() => client.isConnected);
      client.trackSubscription('private-channel.7');
      await _pumpUntil(() => sockets.first.sent.any((m) => m.contains('pusher:subscribe')));

      // Drop the connection.
      await sockets.first.close();
      await _pumpUntil(() => sockets.length == 2); // reconnect attempt
      await Future<void>.delayed(const Duration(milliseconds: 20));
      sockets[1].serverSend({
        'event': 'pusher:connection_established',
        'data': {'socket_id': 'b'}
      });
      var resynced = false;
      client.resubscribed.listen((_) => resynced = true);
      await _pumpUntil(() => sockets[1].sent.any((m) => m.contains('pusher:subscribe')));
      // Desired subscription was restored on the NEW socket.
      final sub =
          jsonDecode(sockets[1].sent.last) as Map<String, dynamic>;
      expect((sub['data'] as Map)['channel'], 'private-channel.7');
      expect(resynced, isTrue);
      expect(connectCount, 2);
      client.dispose();
    });

    test('disconnect() is final — no reconnect timer survives', () async {
      final sockets = <FakeSocket>[];
      final client = ReverbClient(
        wsUrl: 'wss://example.test',
        appKey: 'K',
        auth: _OkAuth(),
        socketFactory: (url) async {
          final s = FakeSocket(url);
          sockets.add(s);
          return s as dynamic;
        },
      );
      client.connect();
      await _pumpUntil(() => sockets.isNotEmpty);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      sockets.first.serverSend({
        'event': 'pusher:connection_established',
        'data': {'socket_id': 'a'}
      });
      await _pumpUntil(() => client.isConnected);
      client.trackSubscription('private-channel.1');
      client.disconnect();
      await Future<void>.delayed(const Duration(milliseconds: 200));
      expect(sockets.length, 1, reason: 'no reconnect after explicit disconnect');
      client.dispose();
    });
  });

  group('SessionBroadcastAuth form encoding', () {
    test('sends a form-encoded body with session cookies and parses the '
        'auth string (full stack against MockLaravel)', () async {
      final mock = MockLaravel()
        ..allowedChannels = {'private-channel.49': '1'};
      await mock.start();
      addTearDown(mock.stop);
      final api = QolabApi(origin: mock.origin);
      addTearDown(api.dispose);
      await api.bootstrapSession();

      final auth = SessionBroadcastAuth(api);
      final result = await auth.authorize(
          channel: 'private-channel.49', socketId: 'sock 1&x');
      expect(result, 'TESTKEY:sock 1&xsig');
      expect(mock.broadcastAuthRequests.single['channel_name'],
          'private-channel.49');
      expect(mock.broadcastAuthRequests.single['socket_id'], 'sock 1&x');
    });

    test('channel not in the allowed set → BroadcastAuthException',
        () async {
      final mock = MockLaravel()
        ..allowedChannels = {'private-channel.7': '1'};
      await mock.start();
      addTearDown(mock.stop);
      final api = QolabApi(origin: mock.origin);
      addTearDown(api.dispose);
      await api.bootstrapSession();

      final auth = SessionBroadcastAuth(api);
      await expectLater(
        auth.authorize(channel: 'private-channel.999', socketId: 's'),
        throwsA(isA<BroadcastAuthException>()),
      );
    });
  });
}

class _OkAuth implements BroadcastAuth {
  @override
  Future<String> authorize(
      {required String channel, required String socketId}) async {
    return 'TESTKEY:sig';
  }
}

class _FailingAuth implements BroadcastAuth {
  @override
  Future<String> authorize(
      {required String channel, required String socketId}) async {
    throw const BroadcastAuthException(403, 'forbidden');
  }
}

Future<void> _pumpUntil(bool Function() test,
    [Duration timeout = const Duration(seconds: 2)]) async {
  final deadline = DateTime.now().add(timeout);
  while (!test()) {
    if (DateTime.now().isAfter(deadline)) {
      fail('condition not met within $timeout');
    }
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
}
