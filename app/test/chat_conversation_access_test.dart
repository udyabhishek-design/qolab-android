// Phase 4A.2.3 — Conversation access failure regression suite.
//
// Root cause being locked down: Laravel with APP_URL configured emits
// session-expiry redirects with ABSOLUTE Locations
// ('https://host/login'), but ApiResult.redirectedToLogin only matched
// relative '/login'. Absolute redirects were misclassified as an
// unrelated 3xx → fetchChannelDetailHtml mapped ANY 302 to
// ApiErrorKind.conflict → the UI showed "You do not have access to this
// conversation." for every expired-session open.
//
// These tests run the REAL QolabApi + ConversationController against
// the shared MockLaravel from qolab_api_test.dart.

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_controller.dart';

import 'qolab_api_test.dart' show MockLaravel;

void main() {
  late MockLaravel mock;
  late QolabApi api;

  setUp(() async {
    mock = MockLaravel();
    await mock.start();
    api = QolabApi(origin: mock.origin);
    await api.bootstrapSession();
    await api.login(email: mock.validEmail, password: mock.validPassword);

    // A normal DM conversation shape.
    mock.channelDetailPropsOverride = {
      'channel': {
        'id': 58,
        'name': 'Admin User',
        'type': 'dm',
        'is_room': false,
        'dm_partner': {
          'id': 1,
          'name': 'Admin User',
        },
        'last_message': null,
        'unread_count': 0,
      },
      'channels': {},
      'employees': [],
    };
    mock.messagePages[1] = {
      'data': [
        {
          'id': 101,
          'channel_id': 58,
          'user_id': 1,
          'user': {'id': 1, 'name': 'Admin User'},
          'body': 'hello from history',
          'created_at': '2026-02-01T10:00:00Z',
          'attachments': [],
          'replies': [],
        },
      ],
      'meta': {'current_page': 1, 'last_page': 1},
    };
  });

  tearDown(() async {
    await mock.stop();
  });

  ConversationController controller(
          {void Function()? onSessionExpired}) =>
      ConversationController(
          api: api,
          channelId: 58,
          myUserId: 5,
          onSessionExpired: onSessionExpired);

  group('4A.2.3 REGRESSION: session-expiry redirect classification', () {
    test('ABSOLUTE https://host/login redirect maps to unauthenticated '
        '(the confirmed root cause)', () async {
      mock.unauthenticatedChannelRedirect = '${mock.origin}/login';
      // Genuinely invalidate the session (rotates it out server-side).
      await api.logout();

      await expectLater(
        api.fetchChannelDetailHtml(58),
        throwsA(isA<ApiException>()
            .having((e) => e.kind, 'kind', ApiErrorKind.unauthenticated)
            .having(
                (e) => e.message, 'message', contains('session has expired'))),
      );
    });

    test('absolute login redirect on /chat index also unauthenticated',
        () async {
      mock.unauthenticatedChannelRedirect = '${mock.origin}/login';
      await api.logout();
      await expectLater(
        api.fetchChatIndexHtml(),
        throwsA(isA<ApiException>()
            .having((e) => e.kind, 'kind', ApiErrorKind.unauthenticated)),
      );
    });
  });

  group('4A.2.3: authorized conversation opens', () {
    test('DM detail + history load successfully', () async {
      final html = await api.fetchChannelDetailHtml(58);
      expect(html, contains('Chat/Show'));

      final json = await api.fetchMessagesJson(58, page: 1);
      final data =
          (jsonDecode(json) as Map<String, dynamic>)['data'] as List;
      expect(data, isNotEmpty, reason: 'history must contain messages');
      expect(data.first['id'], 101);
    });

    test('group channel detail loads (non-dm shape)', () async {
      mock.channelDetailPropsOverride = {
        'channel': {
          'id': 54,
          'name': 'general',
          'type': 'group',
          'is_room': false,
          'unread_count': 0,
        },
        'channels': {},
        'employees': [],
      };
      final html = await api.fetchChannelDetailHtml(54);
      expect(html, contains('Chat/Show'));
    });

    test('ConversationController.load() populates detail and messages '
        'end-to-end', () async {
      final c = controller();
      await c.load();

      expect(c.detail.hasValue, isTrue,
          reason: 'conversation detail must load');
      expect(c.messages, isNotEmpty, reason: 'history must load');
      expect(c.messages.first.body, 'hello from history');
      c.dispose();
    });

    test('Retry after a transient 503 performs a genuine new request and '
        'recovers', () async {
      mock.channelDetailStatus = 503;
      mock.messagesStatus = 503;
      final c = controller();
      await c.load();
      expect(c.detail.state, SectionState.error,
          reason: 'transient failure surfaces an error, not "no access"');
      expect(c.detail.error, contains('server had a problem'));

      // Server recovers.
      mock.channelDetailStatus = 200;
      mock.messagesStatus = 200;
      await c.refresh(); // the Retry button's callback
      expect(c.detail.hasValue, isTrue,
          reason: 'Retry must re-issue requests and recover');
      expect(c.messages, isNotEmpty);
      c.dispose();
    });

    test('error clears on retry (visible loading transition)', () async {
      mock.channelDetailStatus = 500;
      mock.messagesStatus = 500;
      final c = controller();
      await c.load();
      expect(c.detail.state, SectionState.error);

      mock.channelDetailStatus = 200;
      mock.messagesStatus = 200;
      final fut = c.refresh();
      expect(c.detail.state, SectionState.loading,
          reason: 'retry must leave the error state immediately');
      await fut;
      expect(c.detail.hasValue, isTrue);
      c.dispose();
    });
  });

  group('4A.2.3: genuine access failures are distinct', () {
    test('genuine 403 maps to forbidden with access message', () async {
      mock.channelDetailStatus = 403;
      await expectLater(
        api.fetchChannelDetailHtml(58),
        throwsA(isA<ApiException>()
            .having((e) => e.kind, 'kind', ApiErrorKind.forbidden)
            .having((e) => e.message, 'message', contains('do not have access'))),
      );
    });

    test('room redirect (301 → /rooms/5) maps to conflict with the '
        'NOT-in-Chat message — never "no access"', () async {
      mock.channelDetailRedirect = '${mock.origin}/rooms/5';
      await expectLater(
        api.fetchChannelDetailHtml(58),
        throwsA(isA<ApiException>()
            .having((e) => e.kind, 'kind', ApiErrorKind.conflict)
            .having((e) => e.message, 'message', contains('not available in Chat'))),
      );
    });

    test('proxy/canonical-host redirect (301 → elsewhere) maps to server '
        'error — never "no access"', () async {
      mock.channelDetailRedirect = 'https://other.example.com/somewhere';
      await expectLater(
        api.fetchChannelDetailHtml(58),
        throwsA(isA<ApiException>()
            .having((e) => e.kind, 'kind', ApiErrorKind.server)),
      );
    });
  });

  group('4A.2.3: session expiry drives the sign-in flow', () {
    test('unauthenticated detail load triggers onSessionExpired and does '
        'NOT show access-denied', () async {
      mock.unauthenticatedChannelRedirect = '${mock.origin}/login';
      await api.logout();

      var expired = false;
      final c = controller(onSessionExpired: () => expired = true);
      await c.load();

      expect(expired, isTrue,
          reason: 'expired session must route to re-auth, not an error');
      expect(c.detail.state, isNot(SectionState.error));
      c.dispose();
    });
  });

  group('4A.2.3: send + receive after opening a conversation', () {
    test('posting a message appends to history', () async {
      final c = controller();
      await c.load();
      expect(c.messages, isNotEmpty);

      mock.messagePostResponse = (body) => jsonEncode({
            'message': {
              'id': 102,
              'channel_id': 58,
              'user_id': 5,
              'user': {'id': 5, 'name': 'QA Admin'},
              'body': body,
              'created_at': '2026-02-01T10:01:00Z',
              'attachments': [],
              'replies': [],
            },
          });
      await c.send('hello from the app');
      expect(c.messages.any((m) => m.id == 102), isTrue,
          reason: 'sent message must appear in history');
      expect(mock.messagePosts, isNotEmpty);
      c.dispose();
    });
  });
}
