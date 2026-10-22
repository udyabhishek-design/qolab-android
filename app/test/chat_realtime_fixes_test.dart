import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_events.dart';
import 'package:qolab_app/chat/chat_controller.dart';
import 'package:qolab_app/chat/reverb_client.dart';

import 'qolab_api_test.dart' show MockLaravel;
import 'chat_realtime_test.dart' show FakeChatSocket;

/// Phase 4A.2.1 regression tests for the BlueStacks failure set:
/// 1. same-account cross-device delivery (own-user message.sent NOT dropped)
/// 2. nested data.channel_id notification parsing
/// 3. startDm accepts the real /chat/channels/{id} redirect (+ legacy)
/// 4. bounded transient auth retries; permanent 403 never retries
/// 5. merge-refresh keeps older loaded pages (id-dedupe merge, no replace)
/// 6. background-resume triggers REST catch-up (socket.resume → onResync)
void main() {
  group('Same-account cross-device delivery', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..channelDetailPropsOverride = _detailProps
        ..messagePages = {
          1: {
            'data': [
              _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'),
              _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
            ],
            'meta': {'current_page': 1, 'last_page': 1, 'total': 2},
          },
        };
      await mock.start();
      api = QolabApi(origin: mock.origin);
      await api.bootstrapSession();
      await api.login(email: 'user@qolab.test', password: 'secret123');
      socket = FakeChatSocket();
    });

    tearDown(() async {
      api.dispose();
      await mock.stop();
    });

    test('message.sent from MY OWN user id (Qolab Web, same account) is '
        'APPENDED, not suppressed — the fix for cross-device delivery',
        () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      expect(c.messages.length, 2);

      // Same account, other device (Web) — user_id 42 == myUserId.
      socket.emit('channel.49', 'message.sent',
          _msgJson(700, 42, 'sent from web', '2026-10-20T10:03:00.000000Z'));
      expect(c.messages.length, 3,
          reason: 'own-id event must be appended (cross-device fix)');
      expect(c.messages.last.body, 'sent from web');

      // And a duplicate of an already-present id is still deduped (REST
      // catch-up overlap) — dedupe survives without suppressing fresh
      // cross-device messages.
      socket.emit('channel.49', 'message.sent',
          _msgJson(700, 42, 'sent from web', '2026-10-20T10:03:00.000000Z'));
      expect(c.messages.length, 3, reason: 'id-dedupe still applies');
      c.dispose();
    });
  });

  group('Notification payload parsing (nested data)', () {
    const parser = ChatEventParser();

    test('channel_id nested under data{} (real Laravel NotificationReceived '
        'shape) is extracted', () {
      final n = parser.chatNotification(const BroadcastEvent(
        channel: 'private-user.42',
        name: 'notification.received',
        data: {
          'id': 'n-1',
          'type': 'App\\Notifications\\ChatMessageSent',
          'title': 'New message',
          'body': '…',
          'data': {
            'channel_id': 49,
            'message_id': 30,
          },
          'read_at': null,
          'created_at': '2026-10-20T10:02:00.000000Z',
        },
      ));
      expect(n, isNotNull,
          reason: 'nested data.channel_id must be parsed (ordering fix)');
      expect(n!.channelId, 49);
      expect(n.messageId, 30);
    });

    test('flat top-level channel_id still works (backward compatibility)',
        () {
      final n = parser.chatNotification(const BroadcastEvent(
        channel: 'private-user.42',
        name: 'notification.received',
        data: {'channel_id': 49, 'message_id': 30},
      ));
      expect(n, isNotNull);
      expect(n!.channelId, 49);
    });

    test('notification without any channel_id is ignored (not chat)', () {
      final n = parser.chatNotification(const BroadcastEvent(
        channel: 'private-user.42',
        name: 'notification.received',
        data: {
          'id': 'n-2',
          'type': 'App\\Notifications\\MeetingStarting',
          'data': {'meeting_id': 3},
        },
      ));
      expect(n, isNull);
    });

    test('ChatListController bumps unread + activity from the NESTED payload',
        () async {
      final mock = MockLaravel()
        ..chatIndexPropsOverride = _indexPropsFix
        ..chatUnreadBody = {
          'data': {'49': 2}
        };
      await mock.start();
      final api = QolabApi(origin: mock.origin);
      await api.bootstrapSession();
      await api.login(email: 'user@qolab.test', password: 'secret123');
      final socket = FakeChatSocket();
      final c = ChatListController(api: api, socket: socket);
      await c.refresh();
      c.attachUserChannel(42);
      // Let the independent unread fetch settle before emitting.
      await Future<void>.delayed(const Duration(milliseconds: 100));
      expect(c.unread, {49: 2}, reason: 'seeded unread loaded');

      socket.emit('user.42', 'notification.received', {
        'id': 'n-1',
        'type': 'App\\Notifications\\ChatMessageSent',
        'data': {'channel_id': 49, 'message_id': 31},
      });
      expect(c.unread![49], 3,
          reason: 'nested payload → real-time unread bump works');
      final conv = c.index.value!.direct.first;
      expect(c.activityOf(conv), isNotNull,
          reason: 'nested payload → real-time ordering hint works');
      c.dispose();
      api.dispose();
      await mock.stop();
    });
  });

  group('startDm redirect formats', () {
    late MockLaravel mock;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel();
      await mock.start();
      api = QolabApi(origin: mock.origin);
      await api.bootstrapSession();
      await api.login(email: 'user@qolab.test', password: 'secret123');
    });

    tearDown(() async {
      api.dispose();
      await mock.stop();
    });

    test('current format /chat/channels/{id} (real Development backend)',
        () async {
      mock.dmRedirectChannelsPath = true;
      final id = await api.startDm(7);
      expect(id, 77);
    });

    test('legacy format /chat/{id} still accepted', () async {
      mock.dmRedirectChannelsPath = false;
      final id = await api.startDm(7);
      expect(id, 77);
    });

    test('error redirect (back to /chat) surfaces a conflict error',
        () async {
      mock.dmRedirectToChannel = 0;
      await expectLater(
          api.startDm(7),
          throwsA(isA<ApiException>().having(
              (e) => e.kind, 'kind', ApiErrorKind.conflict)));
    });
  });

  group('Transient broadcast-auth retries (bounded)', () {
    test('transient failures (503) retry up to maxAuthRetries on the same '
        'connection, then stop — never infinite', () async {
      final auth = _CountingAuth(
          transientStatuses: [503, 503, 503, 503, 503, 503, 503]);
      final (client, sockets) = _makeClient(auth);
      await client.connect();
      // Let the retry timers (1s, 2s, 3s) fire.
      await Future<void>.delayed(const Duration(milliseconds: 1500));
      client.trackSubscription('private-channel.49');
      await Future<void>.delayed(const Duration(milliseconds: 7000));

      expect(auth.calls, lessThanOrEqualTo(ReverbClient.maxAuthRetries + 1),
          reason: 'bounded retries per connection');
      expect(client.authFailed, contains('private-channel.49'),
          reason: 'exhausted retries mark the channel auth-failed');
      client.dispose();
      for (final s in sockets) {
        s.close();
      }
    }, timeout: const Timeout(Duration(seconds: 30)));

    test('a NEW connection clears the retry budget: the channel is retried '
        'again instead of being blacklisted forever', () async {
      final auth = _CountingAuth(
          transientStatuses: [503, 503, 503, 503, 503, 503, 503, 503]);
      final (client, sockets) = _makeClient(auth);
      await client.connect();
      client.trackSubscription('private-channel.49');
      await Future<void>.delayed(const Duration(milliseconds: 7000));
      final callsAfterFirstConn = auth.calls;

      // Drop + reconnect (fresh retry budget on the new connection).
      sockets.first.close();
      await Future<void>.delayed(const Duration(milliseconds: 300));
      // Force immediate reconnection attempt.
      await client.connect();
      await Future<void>.delayed(const Duration(milliseconds: 700));

      expect(auth.calls, greaterThan(callsAfterFirstConn),
          reason: 'fresh retry budget on the new connection');
      expect(client.authFailed, isNot(contains('private-channel.49')),
          reason: 'not permanently blacklisted');
      client.dispose();
      for (final s in sockets) {
        s.close();
      }
    }, timeout: const Timeout(Duration(seconds: 30)));

    test('permanent 403 does NOT retry', () async {
      final auth = _CountingAuth(transientStatuses: const [], permanent: true);
      final (client, sockets) = _makeClient(auth);
      await client.connect();
      client.trackSubscription('private-channel.49');
      await Future<void>.delayed(const Duration(milliseconds: 500));
      expect(auth.calls, 1, reason: '403 → single attempt, no retry');
      expect(client.authFailed, contains('private-channel.49'));
      client.dispose();
      for (final s in sockets) {
        s.close();
      }
    });
  });

  group('Merge-refresh preserves loaded content', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..channelDetailPropsOverride = _detailProps
        ..messagePages = {
          1: {
            'data': [
              _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'),
              _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
            ],
            'meta': {'current_page': 1, 'last_page': 2, 'total': 4},
          },
          2: {
            'data': [
              _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
            ],
            'meta': {'current_page': 2, 'last_page': 2, 'total': 4},
          },
        };
      await mock.start();
      api = QolabApi(origin: mock.origin);
      await api.bootstrapSession();
      await api.login(email: 'user@qolab.test', password: 'secret123');
      socket = FakeChatSocket();
    });

    tearDown(() async {
      api.dispose();
      await mock.stop();
    });

    test('refreshMessages merges the newest page by id — older loaded pages '
        'and new arrivals both survive, no duplicates', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      await c.loadOlder(); // page 2 (older) merged in front
      expect(c.messages.first.body, 'first');
      expect(c.messages.length, 2);

      // Server now has a NEW message on page 1 (page 2 shifted).
      mock.messagePages = {
        1: {
          'data': [
            _msgJson(9, 7, 'brand new', '2026-10-20T11:00:00.000000Z'),
            _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'),
          ],
          'meta': {'current_page': 1, 'last_page': 2, 'total': 5},
        },
        2: {
          'data': [
            _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'),
            _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
          ],
          'meta': {'current_page': 2, 'last_page': 2, 'total': 5},
        },
      };

      await c.refreshMessages();
      expect(c.messages.length, 3, reason: '2 loaded + 1 new, no duplicates');
      expect(c.messages.last.body, 'brand new');
      expect(c.messages.any((m) => m.body == 'first'), isTrue,
          reason: 'older page content preserved after refresh');
      expect(c.messages.map((m) => m.id).toSet().length, c.messages.length,
          reason: 'id-dedupe holds');
      c.dispose();
    });

    test('server-side edit is picked up by merge-refresh (server copy wins)',
        () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();

      mock.messagePages = {
        1: {
          'data': [
            _msgJson(2, 42, 'mine (edited)', '2026-10-20T10:01:00.000000Z',
                editedAt: '2026-10-20T10:05:00.000000Z'),
            _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
          ],
          'meta': {'current_page': 1, 'last_page': 1, 'total': 2},
        },
      };
      await c.refreshMessages();
      expect(
          c.messages.firstWhere((m) => m.id == 2).body, 'mine (edited)');
      c.dispose();
    });
  });

  group('Foreground resume catch-up', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..channelDetailPropsOverride = _detailProps
        ..messagePages = {
          1: {
            'data': [
              _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'),
              _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
            ],
            'meta': {'current_page': 1, 'last_page': 1, 'total': 2},
          },
        };
      await mock.start();
      api = QolabApi(origin: mock.origin);
      await api.bootstrapSession();
      await api.login(email: 'user@qolab.test', password: 'secret123');
      socket = FakeChatSocket();
    });

    tearDown(() async {
      api.dispose();
      await mock.stop();
    });

    test('onResync (foreground resume) triggers a messages refresh that '
        'merges missed messages', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();

      // While backgrounded, a message arrived that we never saw.
      mock.messagePages = {
        1: {
          'data': [
            _msgJson(3, 7, 'missed while backgrounded',
                '2026-10-20T10:04:00.000000Z'),
            _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'),
            _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
          ],
          'meta': {'current_page': 1, 'last_page': 1, 'total': 3},
        },
      };

      // Foreground resume fires onResync (ChatSocketService.resume always
      // fires it even while connected — covers frozen background sockets).
      socket.emitResync();
      await Future<void>.delayed(const Duration(milliseconds: 200));
      expect(c.messages.any((m) => m.id == 3), isTrue,
          reason: 'missed message fetched after resume');
      expect(c.messages.length, 3);
      c.dispose();
    });
  });
}

// ───────────────────────── helpers & doubles ─────────────────────────

/// Channel-detail Inertia props fixture for channel 49 (DM with user 7).
final _detailProps = {
  'channel': {
    'id': 49,
    'name': 'DM Partner',
    'type': 'dm',
    'description': null,
    'avatar_path': null,
    'is_official': false,
    'department': null,
    'creator': null,
    'members': [],
    'members_count': 2,
    'my_role': 'member',
    'dm_partner': {'id': 7, 'name': 'Partner'},
    'unread_count': 0,
    'created_at': '2026-10-01T00:00:00.000000Z',
  },
  'channels': {},
  'employees': [],
};

/// Message JSON identical to the backend formatMessage() shape.
Map<String, Object?> _msgJson(int id, int userId, String body,
    String createdAt,
    {String? editedAt}) {
  return {
    'id': id,
    'channel_id': 49,
    'user_id': userId,
    'body': body,
    'type': 'text',
    'parent_message_id': null,
    'parent': null,
    'edited_at': editedAt,
    'created_at': createdAt,
    'user': {
      'id': userId,
      'name': userId == 42 ? 'Test User' : 'Partner',
      'avatar_path': null,
      'job_title': 'Dev',
      'department': {'id': 7, 'name': 'Engineering'},
      'shift': 'day',
      'timezone': 'UTC',
    },
    'attachments': [],
    'replies': [],
  };
}

final _indexPropsFix = {
  'channels': {
    'dm': [
      {
        'id': 49,
        'name': 'Partner',
        'type': 'dm',
        'description': null,
        'avatar_path': null,
        'is_official': false,
        'department': null,
        'creator': null,
        'members': [],
        'members_count': 2,
        'my_role': 'member',
        'dm_partner': {'id': 7, 'name': 'Partner'},
        'unread_count': 0,
        'last_message_at': null,
        'created_at': '2026-10-01T00:00:00.000000Z',
      },
    ],
    'group': [],
    'company': [],
    'department': [],
  },
  'available_users': [],
  'employees': [],
};

/// BroadcastAuth double that fails with configurable transient statuses a
/// fixed number of times before succeeding (or failing permanently).
class _CountingAuth implements BroadcastAuth {
  _CountingAuth(
      {this.transientStatuses = const [], this.permanent = false});

  final List<int> transientStatuses;
  final bool permanent;
  int calls = 0;

  @override
  Future<String> authorize(
      {required String channel, required String socketId}) async {
    calls++;
    if (permanent) {
      throw const BroadcastAuthException(403, 'not allowed');
    }
    final i = calls - 1;
    if (i < transientStatuses.length &&
        transientStatuses[i] == 503) {
      throw const BroadcastAuthException(503, 'upstream down');
    }
    return 'TESTKEY:sig';
  }
}

/// Minimal recording WebSocket double: captures the client's message
/// handler so `deliver` routes wire frames back into the client, and fires
/// onDone on close so the client sees disconnects (like a real socket).
class _RecordingSocket {
  _RecordingSocket();
  final List<String> sent = [];
  bool closed = false;
  void Function(String)? _onData;
  void Function()? _onDone;

  void deliver(String frame) => _onData?.call(frame);

  void add(String s) => sent.add(s);

  void close() {
    if (closed) return;
    closed = true;
    scheduleMicrotask(() => _onDone?.call());
  }

  void listen(void Function(String) onData,
      {void Function()? onDone, Function? onError, bool? cancelOnError}) {
    _onData = onData;
    _onDone = onDone;
  }
}

(ReverbClient, List<_RecordingSocket>) _makeClient(BroadcastAuth auth) {
  final sockets = <_RecordingSocket>[];
  final client = ReverbClient(
    wsUrl: 'wss://example.test',
    appKey: 'KEY',
    auth: auth,
    socketFactory: (url) async {
      final s = _RecordingSocket();
      sockets.add(s);
      // Deliver connection_established on the EVENT LOOP (not a microtask):
      // connect() awaits this factory before attaching listen(), and a
      // microtask would fire first and get dropped.
      final socketId = '${sockets.length}.${sockets.length}';
      unawaited(Future<void>.delayed(Duration.zero).then((_) {
        s.deliver(
            '{"event":"pusher:connection_established","data":"{\\"socket_id\\":\\"$socketId\\"}"}');
      }));
      return s as Object;
    },
  );
  return (client, sockets);
}
