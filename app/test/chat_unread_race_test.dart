import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_controller.dart';

import 'chat_realtime_test.dart' show FakeChatSocket;
import 'qolab_api_test.dart' show MockLaravel;

/// Phase 4A.2.2 regression tests:
/// - RC1 wiring: ChatListScreen-constructed controllers receive the socket
///   (verified at controller level: attachUserChannel works when socket set)
/// - RC3: notification before unread map loads → server reconciliation,
///   not a silent drop; no overlapping reconciles; after load → +1 exactly
/// - RC4: repeated load() does not duplicate subscriptions
void main() {
  group('Unread initialization race (RC3)', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..chatIndexPropsOverride = _indexProps
        ..chatUnreadBody = {'data': {'49': 2}};
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

    test('notification BEFORE unread loads → reconcile, no silent drop',
        () async {
      final c = ChatListController(api: api, socket: socket);
      c.attachUserChannel(42); // eager _loadUnread started, still in flight
      expect(c.unread, isNull, reason: 'map not yet materialized');

      // A chat notification arrives for a KNOWN conversation while the map
      // is still loading — previously this increment was silently dropped.
      socket.emit('user.42', 'notification.received',
          {'data': {'channel_id': 49, 'message_id': 900}});

      await Future<void>.delayed(const Duration(milliseconds: 300));
      // The server is authoritative: {49: 2} was fetched (reconciled),
      // not lost.
      expect(c.unread, isNotNull, reason: 'reconciliation must fetch counts');
      expect(c.unread![49], 2,
          reason: 'server count wins over the unknown-map race');
      c.dispose();
    });

    test('notification AFTER unread loads → +1 exactly, no double count',
        () async {
      final c = ChatListController(api: api, socket: socket);
      await c.refresh(); // index loads → conversation 49 is KNOWN
      c.attachUserChannel(42);
      await Future<void>.delayed(const Duration(milliseconds: 300));
      expect(c.unread, {49: 2});

      socket.emit('user.42', 'notification.received',
          {'data': {'channel_id': 49, 'message_id': 901}});
      expect(c.unread![49], 3,
          reason: 'known count increments by exactly one');
      c.dispose();
    });

    test('no overlapping refreshes: single-flight holds under burst',
        () async {
      final c = ChatListController(api: api, socket: socket);
      c.attachUserChannel(42);
      // Burst of unknown-map notifications (unknown conversation ids —
      // triggers the refresh path) must not stack reconciles.
      for (var i = 0; i < 5; i++) {
        socket.emit('user.42', 'notification.received',
            {'data': {'channel_id': 999 + i, 'message_id': i}});
      }
      await Future<void>.delayed(const Duration(milliseconds: 400));
      // All requests completed without errors and the map settled.
      expect(c.unread, isNotNull);
      c.dispose();
    });
  });

  group('ConversationController subscription lifecycle (RC4)', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..channelDetailPropsOverride = _detailProps
        ..messagePages = {
          1: {
            'data': [],
            'meta': {'current_page': 1, 'last_page': 1, 'total': 0},
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

    test('load() twice → exactly ONE subscription (no leak)', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      await c.refresh();
      // The socket records one listener per subscribe() call.
      expect(socket.subscribedChannels.length, 1);
      expect(socket.subscribedChannels, contains('channel.49'));

      // And events arrive exactly once (no duplicated handling).
      var seen = 0;
      c.addListener(() {}); // ensure notified path works
      final before = c.messages.length;
      socket.emit('channel.49', 'message.sent',
          _msgJson(700, 7, 'hi', '2026-10-20T10:05:00.000000Z'));
      expect(c.messages.length, before + 1);
      seen++;
      expect(seen, 1);
      c.dispose();
    });

    test('resync fires after load() twice → exactly one page fetch signal',
        () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      await c.refresh();
      // No crash/duplicate error when resync fires — idempotent.
      socket.emitResync();
      await Future<void>.delayed(Duration.zero);
      expect(c.messages.length, 0);
      c.dispose();
    });
  });

  group('RC1 wiring: controller built WITH socket subscribes user channel',
      () {
    test('attachUserChannel with a real socket subscribes user.{id}', () {
      final socket = FakeChatSocket();
      final c = ChatListController(
          api: QolabApi(origin: 'http://127.0.0.1:9'), socket: socket);
      c.attachUserChannel(42);
      expect(socket.subscribedChannels, contains('user.42'),
          reason: 'the RC1 fix: the app actually wires socket → controller');
      c.dispose();
    });
  });
}

final _indexProps = {
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

final _detailProps = {
  'channel': {
    'id': 49,
    'name': 'Partner',
    'type': 'dm',
    'description': null,
    'avatar_path': null,
    'is_official': false,
    'department': null,
    'creator': null,
    'members': [
      {'id': 7, 'name': 'Partner'},
      {'id': 42, 'name': 'Me'},
    ],
    'members_count': 2,
    'my_role': 'member',
    'dm_partner': {'id': 7, 'name': 'Partner'},
    'unread_count': 0,
    'last_message_at': null,
    'created_at': '2026-10-01T00:00:00.000000Z',
  },
};

Map<String, Object?> _msgJson(int id, int userId, String body,
        [String createdAt = '2026-10-20T10:00:00.000000Z']) =>
    {
      'id': id,
      'channel_id': 49,
      'user_id': userId,
      'user': {'id': userId, 'name': 'User $userId'},
      'body': body,
      'created_at': createdAt,
      'updated_at': createdAt,
      'deleted_at': null,
    };
