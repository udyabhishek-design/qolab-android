import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';
import 'package:qolab_app/chat/chat_controller.dart';
import 'package:qolab_app/screens/home_shell.dart';

/// Phase 4A.2.2 regression tests for the Chat tab unread badge:
/// - shows the unread count when > 0
/// - hides at zero / unknown (null)
/// - caps at 99+
/// - updates reactively when the ChatListController notifies
/// - visible while the Home tab is selected
/// - clears after messages are read
void main() {
  late AuthController auth;
  late QolabApi api;
  late ChatListController chat;

  setUp(() {
    api = QolabApi(origin: 'http://127.0.0.1:9'); // dead port — fine, badge
    auth = AuthController(api: api, store: MemorySessionStore());
    auth.status = AuthStatus.signedIn;
    auth.user = const QolabUser(
        id: 42,
        name: 'Test User',
        email: 'user@qolab.test',
        jobTitle: 'Developer',
        departmentName: 'Engineering');
    chat = ChatListController(api: api);
  });

  tearDown(() {
    chat.dispose();
    api.dispose();
  });

  Widget wrap() => MaterialApp(home: HomeShell(auth: auth, api: api, chatController: chat));

  /// Drive the badge value through the controller's real state field.
  void seedUnread(Map<int, int>? map) {
    // ignore: invalid_use_of_protected_member, invalid_use_of_visible_for_testing
    chat.debugSeedUnread(map);
  }

  testWidgets('badge hidden when unread is unknown (null)',
      (tester) async {
    seedUnread(null);
    await tester.pumpWidget(wrap());
    await tester.pump();
    expect(find.text('Chat'), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsNothing);
  });

  testWidgets('badge hidden at zero', (tester) async {
    seedUnread({});
    await tester.pumpWidget(wrap());
    await tester.pump();
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsNothing);
  });

  testWidgets('badge shows the count (3) while Home tab is selected',
      (tester) async {
    seedUnread({49: 2, 50: 1});
    await tester.pumpWidget(wrap());
    await tester.pump();
    // Home tab is index 0 — badge must still be visible.
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsOneWidget);
    expect(find.text('3'), findsOneWidget);
  });

  testWidgets('badge caps at 99+', (tester) async {
    seedUnread({49: 150});
    await tester.pumpWidget(wrap());
    await tester.pump();
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsOneWidget);
    expect(find.text('99+'), findsOneWidget);
  });

  testWidgets('badge updates reactively: arrival then read (clears)',
      (tester) async {
    seedUnread({});
    await tester.pumpWidget(wrap());
    await tester.pump();
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsNothing);

    // A message arrives (controller state change → notifyListeners).
    seedUnread({49: 1});
    chat.debugNotifyForTest();
    await tester.pump();
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsOneWidget);
    expect(find.text('1'), findsOneWidget);

    // Conversation marked read — badge clears.
    seedUnread({49: 0});
    chat.debugNotifyForTest();
    await tester.pump();
    expect(find.byKey(const ValueKey('chat-nav-badge')), findsNothing);
  });

  testWidgets('badge appears on Home tab without switching (journey)',
      (tester) async {
    seedUnread({49: 5});
    await tester.pumpWidget(wrap());
    await tester.pump();
    // Journey: user sits on Home; badge visible bottom-right nav.
    expect(find.text('Home'), findsOneWidget);
    expect(find.text('5'), findsOneWidget);
    // Tapping Chat switches tabs; badge stays correct.
    await tester.tap(find.text('Chat'));
    await tester.pump();
    expect(find.text('5'), findsOneWidget);
  });
}

extension on ChatListController {
  void debugSeedUnread(Map<int, int>? map) {
    // ignore: invalid_use_of_protected_member
    unread = map;
  }

  void debugNotifyForTest() {
    // ignore: invalid_use_of_protected_member
    notifyListeners();
  }
}
