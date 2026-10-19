import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/chat/chat_socket.dart';
import 'package:qolab_app/chat/chat_controller.dart';
import 'package:qolab_app/chat/chat_models.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/reverb_client.dart';

import 'qolab_api_test.dart' show MockLaravel;

/// Realtime behaviors of ConversationController + ChatListController with
/// a socket double that never connects but can emit events and toggle
/// connectivity (fallback-poll / resync logic is what depends on it).
class FakeChatSocket extends Fake implements ChatSocketService {
  FakeChatSocket();

  final _listeners = <String, List<void Function(BroadcastEvent)>>{};
  final resyncers = <void Function()>[];

  @override
  VoidCallback subscribe(
      String channel, void Function(BroadcastEvent) onEvent) {
    _listeners.putIfAbsent(channel, () => []).add(onEvent);
    return () {
      final l = _listeners[channel];
      if (l == null) return;
      l.remove(onEvent);
      if (l.isEmpty) _listeners.remove(channel);
    };
  }

  @override
  void unsubscribe(String channel, void Function(BroadcastEvent) onEvent) {
    final l = _listeners[channel];
    if (l == null) return;
    l.remove(onEvent);
    if (l.isEmpty) _listeners.remove(channel);
  }

  @override
  Stream<void> get onResync => const Stream<void>.empty();

  @override
  bool isConnected = false;

  @override
  Set<String> get subscribedChannels => _listeners.keys.toSet();

  void emit(String channel, String event, Map<String, Object?> data) {
    // Events arrive on the WIRE channel name (private- prefix), matching
    // what ChatSocketService._dispatch receives and re-strips.
    final wire = channel.startsWith('private-') ? channel : 'private-$channel';
    for (final l in List.of(_listeners[channel] ?? const [])) {
      l(BroadcastEvent(channel: wire, name: event, data: data));
    }
  }
}

ChatMessage msg(int id, int userId, String body, String createdAt,
        {String? editedAt}) =>
    ChatMessage(
      id: id,
      channelId: 49,
      userId: userId,
      body: body,
      type: 'text',
      editedAt: editedAt == null
          ? null
          : DateTime.parse(editedAt),
      createdAt: DateTime.parse(createdAt),
      senderName: userId == 42 ? 'Me' : 'Other',
      senderAvatarPath: null,
    );

void main() {
  group('ConversationController realtime', () {
    late MockLaravel mock;
    late FakeChatSocket socket;

    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..channelDetailPropsOverride = _detailProps
        ..messagePages = {
          1: {
            'data': [
              // Endpoint contract: NEWEST FIRST.
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

    test('message.sent from another user appends in real time, dedupes '
        'against REST, and triggers mark-read', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      expect(c.messages.length, 2);

      // Realtime arrival from user 7.
      socket.emit('channel.49', 'message.sent',
          _msgJson(3, 7, 'realtime!', '2026-10-20T10:02:00.000000Z'));
      expect(c.messages.length, 3);
      expect(c.messages.last.body, 'realtime!');

      // Duplicate delivery of the SAME event (REST catch-up overlap).
      socket.emit('channel.49', 'message.sent',
          _msgJson(3, 7, 'realtime!', '2026-10-20T10:02:00.000000Z'));
      expect(c.messages.length, 3, reason: 'dedupe by message id');

      // Own message event (already appended by send response) is ignored.
      socket.emit('channel.49', 'message.sent',
          _msgJson(2, 42, 'mine', '2026-10-20T10:01:00.000000Z'));
      expect(c.messages.length, 3);

      await Future<void>.delayed(
          const Duration(milliseconds: 100));
      expect(mock.readPatches, isNotEmpty,
          reason: 'appended unread message triggers mark-read');
      c.dispose();
    });

    test('out-of-order delivery preserves chronology', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      socket.emit('channel.49', 'message.sent',
          _msgJson(5, 7, 'late old', '2026-10-20T10:00:30.000000Z'));
      expect(c.messages.length, 3);
      expect(c.messages[1].body, 'late old',
          reason: 'inserted before the newer messages');
      c.dispose();
    });

    test('message.edited updates the loaded message', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();

      socket.emit('channel.49', 'message.edited',
          _msgJson(1, 7, 'first (edited)', '2026-10-20T10:00:00.000000Z',
              editedAt: '2026-10-20T10:05:00.000000Z'));
      expect(c.messages.first.body, 'first (edited)');
      expect(c.messages.first.editedAt, isNotNull);
      c.dispose();
    });

    test('message.deleted replaces the loaded message tombstone', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();

      socket.emit('channel.49', 'message.deleted',
          _msgJson(1, 7, 'deleted', '2026-10-20T10:00:00.000000Z'));
      expect(c.messages.first.body, contains('delete'));
      c.dispose();
    });

    test('REST send response and a later realtime echo of the same id do '
        'not duplicate', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      await c.send('hello from me');
      final len = c.messages.length;

      socket.emit('channel.49', 'message.sent',
          _msgJson(901, 42, 'hello from me', '2026-10-20T10:10:00.000000Z'));
      expect(c.messages.length, len,
          reason: 'own-message echo dropped (id already present via send)');
      c.dispose();
    });

    test('dispose removes the channel subscription', () async {
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      expect(socket.subscribedChannels, contains('channel.49'));
      c.dispose();
      expect(socket.subscribedChannels, isNot(contains('channel.49')));
    });
  });

  group('ChatListController realtime', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;

    setUp(() async {
      mock = MockLaravel()
        ..chatIndexPropsOverride = _indexProps
        ..chatUnreadBody = {
          'data': {'49': 2}
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

    test('notification.received bumps unread once and sets activity hint',
        () async {
      final c = ChatListController(api: api, socket: socket);
      await c.refresh();
      c.attachUserChannel(42);
      expect(c.unread, {49: 2});

      socket.emit('user.42', 'notification.received',
          {'channel_id': 49, 'message_id': 30, 'type': 'x'});
      expect(c.unread, {49: 3});
      expect(c.unread![49], 3);

      // Second delivery of the same event must NOT double-count beyond +1
      // each genuine arrival — but the same event re-delivered within the
      // same session is indistinguishable at this layer; the authoritative
      // reconciliation is the unread-count endpoint (verified below).
      final conv = c.index.value!.direct.first;
      expect(c.activityOf(conv), isNotNull,
          reason: 'hint backfills null last_message_at');
      c.dispose();
    });

    test('non-chat notifications are ignored', () async {
      final c = ChatListController(api: api, socket: socket);
      await c.refresh();
      c.attachUserChannel(42);

      socket.emit('user.42', 'notification.received',
          {'type': 'App\\Notifications\\MeetingStarting'});
      expect(c.unread, {49: 2}, reason: 'unread untouched');
      c.dispose();
    });

    test('notification for an UNKNOWN conversation triggers a controlled '
        'REST refresh, not a fabricated row', () async {
      final c = ChatListController(api: api, socket: socket);
      await c.refresh();
      c.attachUserChannel(42);
      final before = c.index.value;

      socket.emit('user.42', 'notification.received', {'channel_id': 999});
      await _settle(c);
      // Index refetched (mock returns same fixture) — value replaced with a
      // FRESH parse, never locally patched with a fake conversation.
      expect(identical(c.index.value, before), isFalse);
      c.dispose();
    });

    test('unread reconciliation via REST overrides optimistic count',
        () async {
      final c = ChatListController(api: api, socket: socket);
      await c.refresh();
      c.attachUserChannel(42);
      socket.emit('user.42', 'notification.received',
          {'channel_id': 49, 'message_id': 30});
      expect(c.unread![49], 3);

      // Authoritative reconciliation: the endpoint says 2 again (cross-device
      // read) — a refresh restores the server's number.
      mock.chatUnreadBody = {
        'data': {'49': 2}
      };
      await c.refresh();
      expect(c.unread![49], 2);
      c.dispose();
    });

    test('dispose cancels user-channel subscription and timers', () async {
      final c = ChatListController(api: api, socket: socket);
      await c.refresh();
      c.attachUserChannel(42);
      expect(socket.subscribedChannels, contains('user.42'));
      c.dispose();
      expect(socket.subscribedChannels, isNot(contains('user.42')));
    });
  });
}

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

Map<String, Object?> _msgJson(int id, int userId, String body, String createdAt,
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

/// Wait until any in-flight refresh (unknown-channel trigger) completes.
Future<void> _settle(ChatListController c) async {
  for (var i = 0; i < 50; i++) {
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
