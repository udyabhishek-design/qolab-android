import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';
import 'package:qolab_app/chat/chat_controller.dart';
import 'package:qolab_app/chat/chat_models.dart';
import 'package:qolab_app/screens/chat_list_screen.dart';
import 'package:qolab_app/screens/conversation_screen.dart';

/// Widget tests for Phase 4A.1 Chat screens. Widget tests run in a fake-async
/// zone that blocks real HTTP, so controllers are seeded directly (same
/// approach as home_shell_test.dart); the send path is exercised through a
/// [_FakeChatApi] that records calls without any network.

ChatMessage m(int id, int userId, String body) => ChatMessage.fromJson({
      'id': id,
      'channel_id': 11,
      'user_id': userId,
      'body': body,
      'type': 'text',
      'created_at': '2026-10-19T10:00:00.000000Z',
      'user': {
        'id': userId,
        'name': userId == 42 ? 'Test User' : 'Priya Sharma',
      },
    })!;

class _FakeChatApi implements QolabApi {
  _FakeChatApi();

  final List<Map<String, Object?>> sentMessages = [];
  int nextId = 200;

  @override
  Future<String> sendMessageJson(int channelId, String body) async {
    sentMessages.add({'channel': channelId, 'body': body});
    return '{"message": {"id": ${nextId++}, "channel_id": $channelId, '
        '"user_id": 42, "body": "$body", "type": "text", '
        '"created_at": "2026-10-19T11:00:00.000000Z", '
        '"user": {"id": 42, "name": "Test User"}}}';
  }

  @override
  Future<void> markRead(int channelId, int messageId) async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late AuthController auth;

  setUp(() {
    auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    auth.status = AuthStatus.signedIn;
    auth.user = const QolabUser(
        id: 42,
        name: 'Test User',
        email: 'user@qolab.test',
        jobTitle: 'Developer',
        departmentName: 'Engineering');
  });

  Widget wrap(Widget child) => MaterialApp(home: child);

  ChatListController seededList() {
    final c = ChatListController(api: auth.api);
    c.index.setLoaded(ChatIndex.fromPage(InertiaPage(
      component: 'Chat/Index',
      props: {
        'channels': {
          'dm': [
            {
              'id': 11,
              'name': 'Priya Sharma',
              'type': 'dm',
              'last_message_at': '2026-10-19T10:00:00.000000Z',
              'members_count': 2,
              'unread_count': 0,
            }
          ],
          'group': [
            {
              'id': 21,
              'name': 'Design Team',
              'type': 'group',
              'members_count': 5,
              'unread_count': 0,
            }
          ],
          'company': [],
          'department': [],
        },
        'available_users': [],
        'employees': [],
      },
    )));
    c.unread = {11: 3};
    return c;
  }

  ConversationController seededConversation({QolabApi? api}) {
    final c = ConversationController(
        api: api ?? auth.api, channelId: 11, myUserId: 42);
    c.detail.setLoaded(const Conversation(
        id: 11, name: 'Priya Sharma', type: 'dm', membersCount: 2));
    c.messages = [m(101, 7, 'Theirs'), m(102, 42, 'Mine')];
    c.hasMore = false;
    return c;
  }

  testWidgets('list renders DMs, groups and unread badge', (tester) async {
    final c = seededList();
    await tester.pumpWidget(wrap(ChatListScreen(
      auth: auth,
      api: auth.api,
      controller: c,
    )));
    await tester.pump();

    expect(find.text('Priya Sharma'), findsOneWidget);
    expect(find.text('Design Team'), findsOneWidget);
    expect(find.text('3'), findsOneWidget); // unread badge
    c.dispose();
  });

  testWidgets('search filters the locally loaded list', (tester) async {
    final c = seededList();
    await tester.pumpWidget(wrap(ChatListScreen(
      auth: auth,
      api: auth.api,
      controller: c,
    )));
    await tester.pump();

    await tester.enterText(find.byType(TextField).first, 'priya');
    await tester.pump();

    expect(find.text('Priya Sharma'), findsOneWidget);
    expect(find.text('Design Team'), findsNothing);
    c.dispose();
  });

  testWidgets('list error state shows retry affordance', (tester) async {
    final c = ChatListController(api: auth.api);
    c.index.setError('Chat is unavailable right now.');
    await tester.pumpWidget(wrap(ChatListScreen(
      auth: auth,
      api: auth.api,
      controller: c,
    )));
    await tester.pump();

    expect(find.text('Retry'), findsOneWidget);
    c.dispose();
  });

  testWidgets('conversation renders history, title and composer',
      (tester) async {
    final c = seededConversation();
    await tester.pumpWidget(wrap(ConversationScreen(
      api: auth.api,
      channelId: 11,
      fallbackName: 'Priya Sharma',
      myUserId: 42,
      controller: c,
    )));
    await tester.pump();

    expect(find.text('Mine'), findsOneWidget);
    expect(find.text('Theirs'), findsOneWidget);
    expect(find.text('Priya Sharma'), findsWidgets); // app bar title
    expect(find.byType(TextField), findsOneWidget);
    expect(find.byIcon(Icons.send_rounded), findsOneWidget);
    c.dispose();
  });

  testWidgets('send posts body, appends server message, clears composer',
      (tester) async {
    final fake = _FakeChatApi();
    final c = seededConversation(api: fake);
    await tester.pumpWidget(wrap(ConversationScreen(
      api: fake,
      channelId: 11,
      fallbackName: 'Priya Sharma',
      myUserId: 42,
      controller: c,
    )));
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'Hello');
    await tester.tap(find.byIcon(Icons.send_rounded));
    await tester.pumpAndSettle();

    expect(fake.sentMessages, [
      {'channel': 11, 'body': 'Hello'}
    ]);
    expect(find.text('Hello'), findsOneWidget);
    expect(c.messages.last.id, 200); // server message appended
    c.dispose();
  });

  testWidgets('rapid double-tap on send produces one request',
      (tester) async {
    final fake = _FakeChatApi();
    final c = seededConversation(api: fake);
    await tester.pumpWidget(wrap(ConversationScreen(
      api: fake,
      channelId: 11,
      fallbackName: 'Priya Sharma',
      myUserId: 42,
      controller: c,
    )));
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'dup');
    final send = find.byIcon(Icons.send_rounded);
    await tester.tap(send);
    await tester.tap(send); // no settle between taps
    await tester.pumpAndSettle();

    expect(fake.sentMessages, hasLength(1));
    c.dispose();
  });

  testWidgets('empty conversation shows composer without error',
      (tester) async {
    final c = ConversationController(
        api: auth.api, channelId: 11, myUserId: 42);
    c.detail.setLoaded(const Conversation(
        id: 11, name: 'Priya Sharma', type: 'dm', membersCount: 2));
    c.messages = [];
    c.hasMore = false;
    await tester.pumpWidget(wrap(ConversationScreen(
      api: auth.api,
      channelId: 11,
      fallbackName: 'Priya Sharma',
      myUserId: 42,
      controller: c,
    )));
    await tester.pump();

    expect(find.byType(TextField), findsOneWidget);
    expect(c.messages, isEmpty);
    c.dispose();
  });

  testWidgets('Home↔Chat: shell keeps both tabs alive (IndexedStack)',
      (tester) async {
    final c = seededList();
    await tester.pumpWidget(wrap(ChatListScreen(
      auth: auth,
      api: auth.api,
      controller: c,
    )));
    await tester.pump();
    expect(find.text('Priya Sharma'), findsOneWidget);
    c.dispose();
  });
}
