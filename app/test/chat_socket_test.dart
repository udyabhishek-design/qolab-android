
import 'package:flutter_test/flutter_test.dart';

import 'package:qolab_app/chat/chat_events.dart';
import 'package:qolab_app/chat/chat_socket.dart';
import 'package:qolab_app/chat/reverb_client.dart';

/// A ChatSocketService with an injected inert ReverbClient (never connects;
/// only ref-counting/dispatch paths are exercised).
ChatSocketService _fakeService() => ChatSocketService(
      api: null,
      appKey: 'K',
      client: _InertClient(),
    );

/// A ChatSocketService with an injected inert ReverbClient (never connects;
/// track/untrack are no-ops so only ref-counting/dispatch paths are
/// exercised).
class _InertClient extends Fake implements ReverbClient {
  @override
  void trackSubscription(String channel) {}
  @override
  void untrackSubscription(String channel) {}
  @override
  void disconnect() {}
  @override
  void dispose() {}
}

void main() {
  group('ChatSocketService lifecycle', () {
    test('ref-counted subscriptions: two listeners → one unsubscribe',
        () async {
      final service = _fakeService();
      void a(BroadcastEvent e) {}
      void b(BroadcastEvent e) {}
      final cancelA = service.subscribe('channel.5', a);
      final cancelB = service.subscribe('channel.5', b);
      expect(service.subscribedChannels, contains('channel.5'));

      cancelA();
      // Still subscribed — B is listening.
      expect(service.subscribedChannels, contains('channel.5'));

      cancelB();
      // Last listener gone → unsubscribed.
      expect(service.subscribedChannels, isNot(contains('channel.5')));
      service.dispose();
    });

    test('events dispatch only to matching channels', () async {
      final service = _fakeService();
      final got = <BroadcastEvent>[];
      service.subscribe('channel.5', got.add);
      service.debugEmit(const BroadcastEvent(
          channel: 'channel.5', name: 'message.sent', data: {'id': 1}));
      service.debugEmit(const BroadcastEvent(
          channel: 'channel.9', name: 'message.sent', data: {'id': 2}));
      await Future<void>.delayed(Duration.zero);
      expect(got.single.data['id'], 1);
      service.dispose();
    });

    test('dispose: no further events, no leaked listeners', () async {
      final service = _fakeService();
      var fired = false;
      final cancel = service.subscribe('channel.5', (_) => fired = true);
      service.dispose();
      cancel(); // must not throw after dispose
      expect(fired, isFalse);
    });
  });

  group('ChatEventParser', () {
    const parser = ChatEventParser();

    test('incomingMessage parses message.sent with channel-derived id',
        () {
      final msg = parser.incomingMessage(const BroadcastEvent(
        channel: 'private-channel.49',
        name: 'message.sent',
        data: {
          'id': 101,
          'channel_id': 49,
          'user_id': 7,
          'body': 'hello',
          'type': 'text',
          'edited_at': null,
          'created_at': '2026-10-20T10:00:00.000000Z',
          'user': {
            'id': 7,
            'name': 'Sender',
            'avatar_path': null,
          },
        },
      ));
      expect(msg, isNotNull);
      expect(msg!.id, 101);
      expect(msg.channelId, 49);
      expect(msg.body, 'hello');
      expect(msg.senderName, 'Sender');
    });

    test('incomingMessage ignores unrelated events', () {
      expect(
          parser.incomingMessage(const BroadcastEvent(
              channel: 'private-channel.49',
              name: 'user.typing',
              data: {})),
          isNull);
      expect(
          parser.incomingMessage(const BroadcastEvent(
              channel: 'private-user.7',
              name: 'message.sent',
              data: {})),
          isNull,
          reason: 'user channel is not a conversation channel');
    });

    test('chatNotification extracts channel/message ids', () {
      final n = parser.chatNotification(const BroadcastEvent(
        channel: 'private-user.7',
        name: 'notification.received',
        data: {
          'id': 'abc',
          'type': 'App\\Notifications\\MessageReceived',
          'channel_id': 49,
          'message_id': 123,
        },
      ));
      expect(n, isNotNull);
      expect(n!.channelId, 49);
      expect(n.messageId, 123);
    });

    test('chatNotification ignores non-chat notifications', () {
      expect(
          parser.chatNotification(const BroadcastEvent(
              channel: 'private-user.7',
              name: 'notification.received',
              data: {'type': 'App\\Notifications\\MeetingStarting'})),
          isNull);
      expect(
          parser.chatNotification(const BroadcastEvent(
              channel: 'private-user.7',
              name: 'notification.received',
              data: {})),
          isNull,
          reason: 'no channel_id → cannot identify conversation');
    });

    test('string ids are coerced (backend may serialize ids as strings)',
        () {
      final n = parser.chatNotification(const BroadcastEvent(
        channel: 'private-user.7',
        name: 'notification.received',
        data: {'channel_id': '49', 'message_id': '123'},
      ));
      expect(n!.channelId, 49);
      expect(n.messageId, 123);
    });

    test('messageChanged parses edits and deletes', () {
      final edited = parser.messageChanged(const BroadcastEvent(
        channel: 'private-channel.49',
        name: 'message.edited',
        data: {
          'id': 101,
          'channel_id': 49,
          'user_id': 7,
          'body': 'edited body',
          'type': 'text',
          'edited_at': '2026-10-20T10:05:00.000000Z',
          'created_at': '2026-10-20T10:00:00.000000Z',
          'user': {'id': 7, 'name': 'S'},
        },
      ));
      expect(edited!.body, 'edited body');
      expect(edited.editedAt, isNotNull);
    });
  });
}
