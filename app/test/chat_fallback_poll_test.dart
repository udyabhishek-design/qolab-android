import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_controller.dart';

import 'qolab_api_test.dart' show MockLaravel;
import 'chat_realtime_test.dart' show FakeChatSocket;

/// Phase 4A.2.4 regression tests for the "manual refresh required" bug.
///
/// Root cause: the zombie-connection reconciliation in
/// ConversationController required `_lastSocketEventAt != null`, but that
/// field stayed null from attach time until the FIRST event arrived. A
/// socket that connected but delivered nothing (silently dropped
/// subscription, auth-failed channel) therefore never triggered the
/// fallback REST poll — the open conversation stayed stale until the user
/// pulled to refresh. Exactly the reported BlueStacks symptom.
///
/// Fix under test:
/// 1. `_lastSocketEventAt` starts at attach time (silence clock starts
///    immediately — a never-delivering connection becomes suspect).
/// 2. A channel whose authorization failed permanently is treated as
///    not-connected → 30s poll runs regardless of socket state.
///
/// Uses real Timers (plain `test`, not `testWidgets`) with short waits;
/// the controller polls every 30s, so the zombie case needs ~2min of
/// silence. Total suite cost ≈ 2.5 min.
void main() {
  group('Fallback polling (Phase 4A.2.4)', () {
    late MockLaravel mock;
    late FakeChatSocket socket;
    late QolabApi api;
    int fetchCount = 0;

    setUp(() async {
      fetchCount = 0;
      mock = MockLaravel()
        ..channelDetailPropsOverride = _detailProps
        ..messagePages = {
          1: {
            'data': [
              _msgJson(1, 7, 'first', '2026-10-20T10:00:00.000000Z'),
            ],
            'meta': {'current_page': 1, 'last_page': 1, 'total': 1},
          },
        };
      mock.onMessagesFetched = () => fetchCount++;
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

    test(
        'connected-but-silent socket (zombie) polls after the silence '
        'threshold even when NO event was ever received',
        () async {
      socket.isConnected = true; // socket up…
      // …but no events EVER arrive (silently dropped subscription).
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      expect(c.messages.length, 1);
      final initialFetches = fetchCount;

      // Wait past the 2-minute zombie threshold (with margin for the 30s
      // tick cadence). No events are emitted the entire time.
      await Future<void>.delayed(const Duration(seconds: 150));
      expect(fetchCount, greaterThan(initialFetches),
          reason: 'a connected-but-silent (zombie) connection must trigger '
              'the REST fallback poll — the pre-4A.2.4 code required '
              '_lastSocketEventAt != null, which stayed null forever when '
              'no event ever arrived, so the conversation stayed stale '
              'until manual refresh');
      c.dispose();
    }, timeout: const Timeout(Duration(minutes: 4)));

    test(
        'permanently auth-failed channel polls on the fast cadence even '
        'while the socket is connected',
        () async {
      socket.isConnected = true;
      socket.authFailedChannels = {'channel.49'};
      final c = ConversationController(
          api: api, channelId: 49, myUserId: 42, socket: socket);
      await c.load();
      expect(c.messages.length, 1);
      final initialFetches = fetchCount;

      await Future<void>.delayed(const Duration(seconds: 75));
      expect(fetchCount, greaterThan(initialFetches),
          reason: 'auth-failed channel must poll on the ~30s cadence '
              '(treated as not-connected) instead of trusting the socket');
      c.dispose();
    }, timeout: const Timeout(Duration(minutes: 3)));
  });
}

final _detailProps = <String, Object?>{
  'auth': {
    'user': {'id': 42, 'name': 'Me'},
  },
  'channel': {
    'id': 49,
    'name': 'General',
    'type': 'direct',
    'last_message_at': '2026-10-20T10:00:00.000000Z',
    'dm_partner': null,
    'members': [],
    'unread_count': 0,
  },
};

Map<String, Object?> _msgJson(int id, int userId, String body, String at) => {
      'id': id,
      'channel_id': 49,
      'user_id': userId,
      'body': body,
      'type': 'text',
      'created_at': at,
      'user': {'id': userId, 'name': userId == 42 ? 'Me' : 'Other'},
    };
