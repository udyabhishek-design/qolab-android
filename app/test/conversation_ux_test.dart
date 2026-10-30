import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_controller.dart';
import 'package:qolab_app/chat/chat_models.dart';
import 'package:qolab_app/chat/message_formatting.dart';
import 'package:qolab_app/screens/conversation_screen.dart';

/// Regression tests for the conversation UX pass (4A.2.5):
/// 1. Loading skeleton (no premature "No messages" flash)
/// 2. Opens at the newest message (reversed list)
/// 3. Follow-tail on new message while at the newest end
/// 4. New message while reading older history: position preserved + pill
/// 5. Date separators across days (Today / Yesterday / absolute)
/// 6. Timezone conversion via toLocal()
/// 7. Older-message pagination keeps the newest anchored (reverse offset)
///
/// Controllers are seeded directly (widget tests block real HTTP — same
/// approach as chat_widget_test.dart).

ChatMessage msgAt(int id, int userId, String body, DateTime t) =>
    ChatMessage.fromJson({
      'id': id,
      'channel_id': 11,
      'user_id': userId,
      'body': body,
      'type': 'text',
      'created_at': t.toUtc().toIso8601String(),
      'user': {'id': userId, 'name': userId == 42 ? 'Test User' : 'Priya'},
    })!;

/// API stub for seeded controllers: every method HANGS forever (a future
/// that never completes), so load() stays genuinely in flight — exactly the
/// state the screen must treat as "initial fetch not finished".
///
/// Note the future is typed as a String future: the noSuchMethod forwarders
/// cast the returned future to each member's declared return type, and a
/// void-future to String-future cast throws a TypeError (which load()'s
/// catch-all would swallow, completing load() prematurely). A String-typed
/// future is assignable to every member (String returns directly, void
/// covariantly).
class _HangingApi implements QolabApi {
  @override
  dynamic noSuchMethod(Invocation invocation) => Completer<String>().future;
}

void main() {
  final api = _HangingApi();

  ConversationController controller({
    List<ChatMessage>? messages,
    bool loaded = true,
    bool hasMore = false,
    bool initialHistoryLoading = false,
  }) {
    final c = ConversationController(api: api, channelId: 11, myUserId: 42);
    if (loaded) {
      c.detail.setLoaded(const Conversation(
          id: 11, name: 'Priya Sharma', type: 'dm', membersCount: 2));
    }
    c.messages = messages ?? [];
    c.hasMore = hasMore;
    c.initialHistoryLoading = initialHistoryLoading;
    return c;
  }

  Widget wrap(ConversationController c) => MaterialApp(
        home: ConversationScreen(
          api: api,
          channelId: 11,
          fallbackName: 'Priya Sharma',
          myUserId: 42,
          controller: c,
        ),
      );

  final now = DateTime.now();
  final yesterday = now.subtract(const Duration(days: 1));
  final older = now.subtract(const Duration(days: 2));

  group('message_formatting labels', () {
    test('dayLabel: Today / Yesterday / absolute date', () {
      expect(dayLabel(now), 'Today');
      expect(dayLabel(yesterday), 'Yesterday');
      final d = DateTime(2026, 10, 28, 12);
      expect(dayLabel(d), '28 October 2026');
    });

    test('dayLabel converts UTC to local day (timezone conversion)', () {
      // 2026-10-28 23:30 UTC — in any timezone east of UTC this is already
      // the 29th locally. Assert the label matches toLocal() semantics.
      final utc = DateTime.utc(2026, 10, 28, 23, 30);
      final local = utc.toLocal();
      expect(dayLabel(utc), dayLabel(local));
      // And it is an absolute-date form (not Today/Yesterday) unless the
      // device clock happens to be on those days.
      final expectedDay = local.day;
      expect(dayLabel(utc).contains('$expectedDay'), isTrue);
    });

    test('chatTimeLabel formats local HH:mm from UTC input', () {
      final utc = DateTime.utc(2026, 10, 28, 14, 5);
      final local = utc.toLocal();
      String two(int n) => n.toString().padLeft(2, '0');
      expect(chatTimeLabel(utc),
          '${two(local.hour)}:${two(local.minute)}');
    });

    test('startsNewDay: boundary + null newer + same-day', () {
      final a = DateTime(2026, 10, 28, 23, 59);
      final b = DateTime(2026, 10, 29, 0, 1);
      expect(startsNewDay(a, b), isTrue); // day changed
      final c1 = DateTime(2026, 10, 28, 8);
      final c2 = DateTime(2026, 10, 28, 20);
      expect(startsNewDay(c1, c2), isFalse); // same day
      expect(startsNewDay(a, null), isTrue); // first message
    });
  });

  testWidgets('1. initial fetch in flight → skeleton, NOT empty state',
      (tester) async {
    final c = controller(initialHistoryLoading: true, messages: []);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    expect(find.text('No messages yet'), findsNothing);
    expect(find.byKey(const Key('history-skeleton')), findsOneWidget);
    // No flash: the empty state is gated on a completed fetch.
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.text('No messages yet'), findsNothing);
    c.dispose();
  });

  testWidgets('1b. completed fetch with empty result → empty state',
      (tester) async {
    final c = controller(messages: [], initialHistoryLoading: false);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    expect(find.text('No messages yet'), findsOneWidget);
    expect(find.byKey(const Key('history-skeleton')), findsNothing);
    c.dispose();
  });

  testWidgets('2. opens showing the NEWEST message (reversed list)',
      (tester) async {
    final c = controller(messages: [
      for (var i = 1; i <= 40; i++)
        msgAt(i, i % 2 == 0 ? 42 : 7, 'msg $i',
            now.subtract(Duration(minutes: 40 - i))),
    ]);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    // Newest built; oldest NOT built (lazy reversed builder keeps only the
    // newest viewport — proof the conversation opens at the latest).
    expect(find.text('msg 40'), findsOneWidget);
    expect(find.text('msg 1'), findsNothing);
    c.dispose();
  });

  testWidgets('3. new message while near newest end → followed',
      (tester) async {
    final c = controller(messages: [
      msgAt(1, 7, 'old', now.subtract(const Duration(minutes: 30))),
      msgAt(2, 42, 'newest', now.subtract(const Duration(minutes: 1))),
    ]);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    c.messages = [...c.messages, msgAt(3, 7, 'live arrival', now)];
    c.notifyListeners();
    await tester.pump();
    await tester.pumpAndSettle();

    expect(find.text('live arrival'), findsOneWidget);
    expect(find.byKey(const Key('jump-pill')), findsNothing);
    c.dispose();
  });

  testWidgets('4. new message while reading older history → pill, no jump',
      (tester) async {
    final c = controller(messages: [
      for (var i = 1; i <= 40; i++)
        msgAt(i, i % 2 == 0 ? 42 : 7, 'msg $i',
            now.subtract(Duration(minutes: 40 - i))),
    ]);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    // Scroll into older history (visual top = maxScrollExtent on the
    // reversed list → drag DOWN to reveal older content).
    await tester.dragUntilVisible(
      find.text('msg 1'),
      find.byType(ListView),
      const Offset(0, 250),
    );
    await tester.pumpAndSettle();

    // New message arrives while reading older history.
    c.messages = [...c.messages, msgAt(41, 7, 'while away', now)];
    c.notifyListeners();
    await tester.pump();
    await tester.pumpAndSettle();

    // Not forced to the newest: still viewing old history...
    expect(find.text('msg 1'), findsOneWidget);
    expect(find.text('while away'), findsNothing);
    // ...but the New-messages pill is offered.
    expect(find.byKey(const Key('jump-pill')), findsOneWidget);

    // Tap the pill → jumps to the newest message.
    await tester.tap(find.byKey(const Key('jump-pill')));
    await tester.pumpAndSettle();
    expect(find.text('while away'), findsOneWidget);
    c.dispose();
  });

  testWidgets('5. date separators across three days', (tester) async {
    final c = controller(messages: [
      msgAt(1, 7, 'two days ago', older),
      msgAt(2, 7, 'still older day', older.add(const Duration(hours: 1))),
      msgAt(3, 42, 'yesterday', yesterday),
      msgAt(4, 7, 'today', now),
    ]);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    // One separator per day group: newest item (today) carries "Today",
    // older days surface on scroll — verify all four messages + labels.
    expect(find.text('Today'), findsOneWidget);
    // Older days: scroll down (reversed list → older content at the top).
    await tester.dragUntilVisible(
      find.text('two days ago'),
      find.byType(ListView),
      const Offset(0, 200),
    );
    await tester.pumpAndSettle();
    expect(find.text('Yesterday'), findsOneWidget);
    // The absolute-date label for the oldest day (28 October 2026 style).
    final oldestLabel = dayLabel(older);
    expect(find.text(oldestLabel), findsOneWidget);
    // No duplicates: exactly 3 separators in total.
    int separators() => tester
        .widgetList<Text>(find.byType(Text))
        .where((t) => ['Today', 'Yesterday', oldestLabel].contains(t.data))
        .length;
    expect(separators(), 3);
    c.dispose();
  });

  testWidgets('6. timestamps rendered in local time', (tester) async {
    final utc = DateTime.utc(2026, 10, 28, 14, 5);
    final c = controller(messages: [msgAt(1, 42, 'time check', utc)]);
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    final local = utc.toLocal();
    String two(int n) => n.toString().padLeft(2, '0');
    final expected = '${two(local.hour)}:${two(local.minute)}';
    expect(find.text(expected), findsOneWidget);
    c.dispose();
  });

  testWidgets('7. loadOlder prepends older messages; newest stays anchored',
      (tester) async {
    final c = controller(
      messages: [
        for (var i = 21; i <= 40; i++)
          msgAt(i, i % 2 == 0 ? 42 : 7, 'msg $i',
              now.subtract(Duration(minutes: 40 - i))),
      ],
      hasMore: true,
    );
    await tester.pumpWidget(wrap(c));
    await tester.pump();

    // At the newest end (offset ~0 on the reversed list).
    expect(find.text('msg 40'), findsOneWidget);

    // Older page arrives (prepended chronologically → appended at the older
    // end of the reversed list): anchor must not move.
    final olderPage = [
      for (var i = 1; i <= 20; i++)
        msgAt(i, i % 2 == 0 ? 42 : 7, 'msg $i',
            now.subtract(Duration(minutes: 40 - i))),
    ];
    c.messages = [...olderPage, ...c.messages];
    c.notifyListeners();
    await tester.pump();
    await tester.pumpAndSettle();

    // Newest still visible without any scroll — the anchor held.
    expect(find.text('msg 40'), findsOneWidget);
    c.dispose();
  });
}
