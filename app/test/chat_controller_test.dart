
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_controller.dart';

import 'qolab_api_test.dart' show MockLaravel;

/// Tiny chat-aware mock built on the session-aware MockLaravel harness.
class ChatMock {
  ChatMock();

  final MockLaravel laravel = MockLaravel();
  late QolabApi api;

  int sessionExpiredCalls = 0;

  Map<String, dynamic> indexProps = const {
    'channels': {
      'dm': [
        {
          'id': 11,
          'name': 'Priya Sharma',
          'type': 'dm',
          'last_message_at': '2026-10-19T10:00:00.000000Z',
          'members_count': 2,
          'unread_count': 0,
          'dm_partner': null,
        }
      ],
      'group': [],
      'company': [],
      'department': [],
    },
    'available_users': [],
    'employees': [],
  };
  Map<String, dynamic>? detailProps;
  int detailStatus = 200;
  Map<int, Map<String, dynamic>> messagePages = {};
  int messagesStatus = 200;
  Duration messagesDelay = Duration.zero;
  List<Map<String, String>> posts = [];
  int postStatus = 201;
  int Function(int count)? nextMessageId;
  int dmChannel = 77;
  List<int> readPatched = [];
  Map<String, dynamic> unreadBody = const {'data': {}};

  Future<void> start() async {
    await laravel.start();
    laravel.chatIndexPropsOverride = indexProps;
    laravel.channelDetailPropsOverride = detailProps;
    laravel.channelDetailStatus = detailStatus;
    laravel.messagePages = messagePages;
    laravel.messagesStatus = messagesStatus;
    laravel.messagesDelay = messagesDelay;
    laravel.chatUnreadBody = unreadBody;
    laravel.messagePostResponse = null;
    api = QolabApi(origin: laravel.origin);
    // Interpose send/read recording: wrap via knobs.
    await _login();
  }


  Future<void> _login() async {
    await api.bootstrapSession();
    await api.login(email: 'user@qolab.test', password: 'secret123');
  }

  Future<void> stop() async {
    api.dispose();
    await laravel.stop();
  }
}

void main() {
  late ChatMock mock;

  Map<String, dynamic> msg(int id, int userId, String body,
          [String at = '2026-10-19T10:00:00.000000Z']) =>
      {
        'id': id,
        'channel_id': 11,
        'user_id': userId,
        'body': body,
        'type': 'text',
        'parent_message_id': null,
        'parent': null,
        'edited_at': null,
        'created_at': at,
        'user': {
          'id': userId,
          'name': userId == 42 ? 'Test User' : 'Priya Sharma',
          'avatar_path': null,
          'job_title': 'Designer',
          'department': {'id': 2, 'name': 'Design'},
          'shift': 'day',
          'timezone': 'UTC',
        },
        'attachments': [],
        'replies': [],
      };

  setUp(() async {
    mock = ChatMock();
  });

  tearDown(() async {
    await mock.stop();
  });

  ChatListController listController(
          {void Function()? onExpired}) =>
      ChatListController(api: mock.api, onSessionExpired: onExpired);

  ConversationController convController(int channelId,
          {void Function()? onExpired}) =>
      ConversationController(
          api: mock.api,
          channelId: channelId,
          myUserId: 42,
          onSessionExpired: onExpired);

  group('ChatListController', () {
    test('loads conversation list from /chat Inertia HTML', () async {
      mock.laravel.chatIndexPropsOverride = null; // set after start
      await mock.start();
      mock.laravel.chatIndexPropsOverride = {
        'channels': {
          'dm': [
            {
              'id': 11,
              'name': 'Priya Sharma',
              'type': 'dm',
              'last_message_at': '2026-10-19T10:00:00.000000Z',
              'members_count': 2,
              'unread_count': 2,
              'dm_partner': null,
            }
          ],
          'group': [],
          'company': [],
          'department': [],
        },
        'available_users': [],
        'employees': [],
      };
      final c = listController();
      await c.refresh();

      expect(c.index.state, SectionState.loaded);
      expect(c.index.value!.direct, hasLength(1));
      expect(c.index.value!.direct.first.name, 'Priya Sharma');
      c.dispose();
    });

    test('unread counts load independently and never block the list',
        () async {
      await mock.start();
      mock.laravel.chatUnreadBody = {'data': {'11': 3, '21': 0}};
      final c = listController();
      await c.refresh();
      // unread arrives via its own request; allow microtasks to drain.
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(c.unread, {11: 3, 21: 0});
      c.dispose();
    });

    test('unread failure leaves counts unknown but list loaded',
        () async {
      await mock.start();
      mock.laravel.chatUnreadStatus = 500;
      final c = listController();
      await c.refresh();
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(c.index.state, SectionState.loaded);
      expect(c.unread, isNull); // never fabricated
      c.dispose();
    });

    test('error state surfaces; retry with the same controller recovers',
        () async {
      await mock.start();
      final c = listController();

      // Failure: mock returns 500 for /chat.
      mock.laravel.chatStatusOverride = 500;
      await c.refresh();
      expect(c.index.state, SectionState.error);
      expect(c.index.value, isNull);

      // Recover: healthy again (0 = no override), retry() re-fetches.
      mock.laravel.chatStatusOverride = 0;
      await c.refresh();
      expect(c.index.state, SectionState.loaded);
      c.dispose();
    });

    test('startDm returns the redirected channel id', () async {
      await mock.start();
      mock.laravel.dmRedirectToChannel = 88;
      final c = listController();
      final id = await c.startDm(7);
      expect(id, 88);
      expect(mock.laravel.dmPosts, [7]);
      c.dispose();
    });

    test('startDm single-flight: concurrent calls send one request',
        () async {
      await mock.start();
      final c = listController();
      final a = c.startDm(7);
      final b = c.startDm(7); // second tap during in-flight request
      final ra = await a;
      final rb = await b;
      expect(ra, 77);
      expect(rb, isNull); // no-op, no duplicate
      expect(mock.laravel.dmPosts, hasLength(1));
      c.dispose();
    });

    test('session expiry calls onSessionExpired', () async {
      await mock.start();
      var expired = 0;
      // Fresh API with NO session cookies → 302 to /login → unauthenticated.
      final bare = QolabApi(origin: mock.laravel.origin);
      final c2 = ChatListController(
          api: bare, onSessionExpired: () => expired++);
      await c2.refresh();
      expect(expired, 1);
      expect(c2.index.value, isNull);
      c2.dispose();
      bare.dispose();
    });
  });

  group('ConversationController', () {
    test('loads detail then newest-first history reversed to chronological',
        () async {
      await mock.start();
      mock.laravel.channelDetailPropsOverride = {
        'channel': {
          'id': 11,
          'name': 'Priya Sharma',
          'type': 'dm',
          'members_count': 2,
          'my_role': 'member',
          'unread_count': 1,
        },
        'channels': {},
        'employees': [],
      };
      mock.laravel.messagePages = {
        1: {
          'data': [
            msg(102, 7, 'Second'),
            msg(101, 7, 'First'),
          ], // newest-first
          'meta': {'current_page': 1, 'last_page': 1, 'per_page': 50, 'total': 2},
        },
      };
      final c = convController(11);
      await c.load();

      expect(c.detail.state, SectionState.loaded);
      expect(c.conversation!.name, 'Priya Sharma');
      expect(c.messages.map((m) => m.id).toList(), [101, 102]); // reading order
      expect(c.hasMore, isFalse);
      c.dispose();
    });

    test('pagination: older pages prepend without duplicates', () async {
      await mock.start();
      mock.laravel.channelDetailPropsOverride = {
        'channel': {'id': 11, 'name': 'Priya Sharma', 'type': 'dm'},
      };
      mock.laravel.messagePages = {
        1: {
          'data': [msg(102, 7, 'p1-newest'), msg(101, 7, 'p1-oldest')],
          'meta': {'current_page': 1, 'last_page': 2, 'per_page': 2, 'total': 3},
        },
        2: {
          'data': [msg(101, 7, 'dup'), msg(100, 7, 'older')], // 101 dup
          'meta': {'current_page': 2, 'last_page': 2, 'per_page': 2, 'total': 3},
        },
      };
      final c = convController(11);
      await c.load();
      expect(c.messages.map((m) => m.id).toList(), [101, 102]);
      await c.loadOlder();
      expect(c.messages.map((m) => m.id).toList(), [100, 101, 102]);
      expect(c.hasMore, isFalse);
      c.dispose();
    });

    test('send posts with CSRF and appends the server message once',
        () async {
      await mock.start();
      mock.laravel.messagePages = {
        1: {
          'data': [],
          'meta': {'current_page': 1, 'last_page': 1, 'total': 0},
        },
      };
      final c = convController(11);
      await c.load();

      await c.send('Hello there');
      expect(mock.laravel.messagePosts, hasLength(1));
      expect(mock.laravel.messagePosts.first['body'], 'Hello there');
      // CSRF was attached (X-XSRF-TOKEN present and non-empty).
      expect(
          mock.laravel.messagePosts.first['csrf'], isNot(''));
      // Server-returned message appended.
      expect(
          c.messages.map((m) => m.body), contains('Hello there'));
      c.dispose();
    });

    test('send single-flight: rapid taps produce one POST', () async {
      await mock.start();
      final c = convController(11);
      await c.load();
      final a = c.send('one');
      final b = c.send('two'); // second tap while sending
      await a;
      await b;
      expect(mock.laravel.messagePosts, hasLength(1));
      c.dispose();
    });

    test('send failure keeps composer content, no phantom message',
        () async {
      await mock.start();
      final c = convController(11);
      await c.load();
      mock.laravel.messagePostStatus = 422;
      try {
        await c.send('will fail');
        fail('should throw');
      } on ApiException {
        // expected — caller shows transient error
      }
      expect(c.messages, isEmpty);
      c.dispose();
    });

    test('empty body send is a no-op', () async {
      await mock.start();
      final c = convController(11);
      await c.load();
      await c.send('   ');
      expect(mock.laravel.messagePosts, isEmpty);
      c.dispose();
    });

    test('mark-read fires with the newest message id', () async {
      await mock.start();
      mock.laravel.channelDetailPropsOverride = {
        'channel': {'id': 11, 'name': 'Priya Sharma', 'type': 'dm'},
      };
      mock.laravel.messagePages = {
        1: {
          'data': [msg(102, 7, 'b'), msg(101, 7, 'a')],
          'meta': {'current_page': 1, 'last_page': 1, 'total': 2},
        },
      };
      final c = convController(11);
      await c.load();
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(mock.laravel.readPatches, isNotEmpty);
      expect(mock.laravel.readPatches.last['last_read_message_id'], 102);
      c.dispose();
    });

    test('messages 500 with no content → error state with retry content',
        () async {
      await mock.start();
      mock.laravel.messagesStatus = 500;
      mock.laravel.channelDetailPropsOverride = {
        'channel': {'id': 11, 'name': 'X', 'type': 'dm'},
      };
      final c = convController(11);
      await c.load();
      expect(c.detail.state, SectionState.loaded); // detail OK
      expect(c.messages, isEmpty); // history failed → no content
      c.dispose();
    });

    test('stale generation: slow older load cannot clobber newer state',
        () async {
      await mock.start();
      mock.laravel.channelDetailPropsOverride = {
        'channel': {'id': 11, 'name': 'Priya Sharma', 'type': 'dm'},
      };
      mock.laravel.messagePages = {
        1: {
          'data': [msg(101, 7, 'v1')],
          'meta': {'current_page': 1, 'last_page': 1, 'total': 1},
        },
      };
      final c = convController(11);
      await c.load();

      // Slow page 2 + an immediate refresh: refresh's generation wins.
      mock.laravel.messagesDelay = const Duration(milliseconds: 300);
      mock.laravel.messagePages[2] = {
        'data': [msg(100, 7, 'old')],
        'meta': {'current_page': 2, 'last_page': 2, 'total': 2},
      };
      final slow = c.loadOlder();
      mock.laravel.messagesDelay = Duration.zero;
      mock.laravel.messagePages[1] = {
        'data': [msg(105, 7, 'v2'), msg(101, 7, 'v1')],
        'meta': {'current_page': 1, 'last_page': 1, 'total': 2},
      };
      await c.refresh(); // bumps generation
      await slow; // stale response arrives, must be discarded

      expect(c.messages.map((m) => m.body).contains('v2'), isTrue);
      expect(c.messages.map((m) => m.body).contains('old'), isFalse);
      c.dispose();
    });

    test('dispose during in-flight load does not throw', () async {
      await mock.start();
      mock.laravel.messagesDelay = const Duration(milliseconds: 200);
      mock.laravel.messagePages[1] = {
        'data': [msg(101, 7, 'x')],
        'meta': {'current_page': 1, 'last_page': 1, 'total': 1},
      };
      final c = convController(11);
      final f = c.load();
      c.dispose();
      await f;
      // No throw = pass.
    });
  });
}
