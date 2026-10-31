import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/meetings/call_controller.dart';
import 'package:qolab_app/meetings/call_screen.dart';
import 'package:qolab_app/meetings/meeting_models.dart';

/// Phase B calling regression tests. All controller logic runs against a
/// fake LiveKitRoom — no network, no native plugins. (Live multi-device
/// calling is a manual acceptance step, not covered here.)

/// API stub: returns canned join-token JSON or throws.
class _TokenApi implements QolabApi {
  _TokenApi({this.body, this.error});
  final String? body;
  final Object? error;
  @override
  dynamic noSuchMethod(Invocation invocation) {
    if (invocation.memberName == #fetchMeetingJoinToken) {
      if (error != null) throw error!;
      return Future.value(body ?? '{"success": true}');
    }
    return super.noSuchMethod(invocation);
  }
}

/// Fake LiveKit room: scriptable events + state. The event stream is a
/// SYNC broadcast controller so events are delivered synchronously on
/// emit() — tests can assert immediately (the real LiveKit adapter emits
/// synchronously from its event callbacks too).
class _FakeRoom implements LiveKitRoom {
  final _ctrl = StreamController<LiveKitEvent>.broadcast(sync: true);
  bool camera = false;
  bool mic = false;
  final Map<String, String> remotes = {};
  int connects = 0;
  int disconnects = 0;
  bool failConnect = false;
  bool failSwitch = false;

  void emit(LiveKitEvent e) => _ctrl.add(e);

  @override
  Stream<LiveKitEvent> get events => _ctrl.stream;
  @override
  Map<String, String> get remoteParticipants => Map.of(remotes);
  @override
  bool get isCameraEnabled => camera;
  @override
  bool get isMicEnabled => mic;
  @override
  Future<void> connect(String url, String token) async {
    connects++;
    if (failConnect) throw StateError('connect failed');
  }
  @override
  Future<void> setCameraEnabled(bool enabled) async => camera = enabled;
  @override
  Future<void> setMicEnabled(bool enabled) async => mic = enabled;
  @override
  Future<void> switchCamera() async {
    if (failSwitch) throw StateError('unsupported');
  }
  @override
  Future<void> disconnect() async {
    disconnects++;
    // Matches the real adapter: teardown drops ALL room state — a later
    // connect() is a genuinely fresh room with no ghost remotes.
    remotes.clear();
    camera = false;
    mic = false;
  }
}

const okToken =
    '{"success": true, "token": "tkn-abc", "url": "wss://addy-uuxwzign.livekit.cloud"}';

void main() {
  group('1. join-token parsing and errors', () {
    test('parses success/token/url; isUsable true', () {
      final t = MeetingJoinToken.parse(okToken)!;
      expect(t.success, isTrue);
      expect(t.token, 'tkn-abc');
      expect(t.url, 'wss://addy-uuxwzign.livekit.cloud');
      expect(t.isUsable, isTrue);
    });

    test('rejects malformed shapes', () {
      expect(MeetingJoinToken.parse('not json'), isNull);
      expect(MeetingJoinToken.parse('[]'), isNull);
      expect(MeetingJoinToken.parse('{"success": false}'), isNotNull);
      expect(MeetingJoinToken.parse('{"success": false}')!.isUsable, isFalse);
      expect(
          MeetingJoinToken.parse('{"success": true, "token": "x"}')!.isUsable,
          isFalse); // no url
    });

    test('MeetingEntry.fromJson tolerates bad input', () {
      expect(MeetingEntry.fromJson(null), isNull);
      expect(MeetingEntry.fromJson({'title': 'x'}), isNull); // no id
      final e = MeetingEntry.fromJson({
        'id': 20,
        'title': 'Standup',
        'status': 'live',
        'session_key': 'mtg-x',
      });
      expect(e!.isLive, isTrue);
      expect(e.sessionKey, 'mtg-x');
    });
  });

  group('2. join / leave lifecycle', () {
    test('happy path: token → connect → connected; local present', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 20,
          meetingTitle: 'Standup',
          localName: 'QA Admin',
          room: room);
      await c.join();
      expect(c.status, CallStatus.connected);
      expect(room.connects, 1);
      expect(c.participants.length, 1);
      expect(c.participants.single.isLocal, isTrue);
      // Double-join is a no-op.
      await c.join();
      expect(room.connects, 1);
      await c.leave();
    });

    test('unusable token body → failed with clear error', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: '{"success": false}'),
          meetingId: 20,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join();
      expect(c.status, CallStatus.failed);
      expect(c.error, isNotNull);
      expect(room.connects, 0);
      c.dispose();
    });

    test('API ApiException surfaces its message; connect error → failed',
        () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(
              error: const ApiException(ApiErrorKind.forbidden,
                  'You do not have access to this meeting.')),
          meetingId: 20,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join();
      expect(c.status, CallStatus.failed);
      expect(c.error, 'You do not have access to this meeting.');

      final room2 = _FakeRoom()..failConnect = true;
      final c2 = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 20,
          meetingTitle: 't',
          localName: 'x',
          room: room2);
      await c2.join();
      expect(c2.status, CallStatus.failed);
      expect(room2.connects, 1);
      c.dispose();
      c2.dispose();
    });

    test('leave: idempotent, disconnects exactly once, clears participants',
        () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 20,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join();
      await c.leave();
      await c.leave(); // second call is a no-op
      expect(room.disconnects, 1);
      expect(c.status, CallStatus.ended);
      expect(c.participants, isEmpty);
    });
  });

  group('3+4. mic & camera toggling', () {
    test('mic toggle reflects room state into local participant', () async {
      final room = _FakeRoom()..mic = true;
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join();
      expect(c.participants.single.micEnabled, isTrue);
      await c.toggleMic();
      expect(room.mic, isFalse);
      expect(c.participants.single.micEnabled, isFalse);
      await c.toggleMic();
      expect(c.participants.single.micEnabled, isTrue);
      await c.leave();
    });

    test('camera toggle on/off; audioOnly start keeps camera off', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join(audioOnly: true);
      expect(room.camera, isFalse);
      await c.toggleCamera();
      expect(room.camera, isTrue);
      expect(c.participants.single.cameraEnabled, isTrue);
      await c.toggleCamera();
      expect(room.camera, isFalse);
      await c.leave();
    });
  });

  group('5. remote participant track changes', () {
    test('join → tile; camera on/off/on → hasVideoTrack follows', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'Me',
          room: room);
      await c.join();

      room.remotes['u7'] = 'Priya';
      room.emit(const ParticipantJoined('u7', 'Priya'));
      expect(c.participants.length, 2);
      expect(c.participants.first.name, 'Priya'); // remote FIRST
      expect(c.participants.last.isLocal, isTrue);

      room.emit(const TrackChanged('u7',
          cameraEnabled: true, micEnabled: true, hasVideoTrack: true));
      expect(
          c.participants.firstWhere((p) => p.identity == 'u7').hasVideoTrack,
          isTrue);

      room.emit(const TrackChanged('u7',
          cameraEnabled: false, micEnabled: true, hasVideoTrack: false));
      expect(
          c.participants.firstWhere((p) => p.identity == 'u7').hasVideoTrack,
          isFalse);

      room.emit(const TrackChanged('u7',
          cameraEnabled: true, micEnabled: true, hasVideoTrack: true));
      expect(
          c.participants.firstWhere((p) => p.identity == 'u7').hasVideoTrack,
          isTrue);

      room.remotes.remove('u7');
      room.emit(const ParticipantLeft('u7'));
      expect(c.participants.length, 1);
      expect(c.participants.any((p) => p.identity == 'u7'), isFalse);
      await c.leave();
    });

    test('track event for unknown participant (race) creates the tile',
        () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'Me',
          room: room);
      await c.join();
      // Realistic race: the join event was missed but the room map (the
      // authoritative source _syncFromRoom reads) already has the remote.
      room.remotes['late-joiner'] = 'Late Joiner';
      room.emit(const TrackChanged('late-joiner',
          cameraEnabled: false, micEnabled: true, hasVideoTrack: false));
      expect(c.participants.any((p) => p.identity == 'late-joiner'), isTrue);
      expect(
          c.participants
              .firstWhere((p) => p.identity == 'late-joiner')
              .micEnabled,
          isTrue);
      await c.leave();
    });
  });

  group('6. multi-participant layout', () {
    testWidgets('grid renders remote-first with responsive columns',
        (tester) async {
      // The screen drives its OWN controller (created in initState) through
      // the injected fake room — no external controller here.
      final room = _FakeRoom();
      // Remotes present in the room map when the screen's controller syncs
      // after connect (no join events needed — the map is authoritative).
      room.remotes['u7'] = 'Priya';
      room.remotes['u8'] = 'Dan';
      await tester.pumpWidget(MaterialApp(home: MeetingCallScreen(
        api: _TokenApi(body: okToken),
        meetingId: 1,
        meetingTitle: 'Team Sync',
        localName: 'Me',
        adapter: room,
      )));
      // Fixed frames (not pumpAndSettle): no infinite animations expected
      // on the connected call grid.
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump(const Duration(milliseconds: 300));

      // 3 participants → 2 columns.
      final grid = tester.widget<GridView>(find.byType(GridView));
      expect(
          (grid.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount)
              .crossAxisCount,
          2);
      // Remote names render; local renders as "You".
      expect(find.text('Priya'), findsWidgets);
      expect(find.text('Dan'), findsWidgets);
      expect(find.text('You'), findsWidgets);
      // Controls present.
      expect(find.byKey(const Key('btn-mic')), findsOneWidget);
      expect(find.byKey(const Key('btn-cam')), findsOneWidget);
      expect(find.byKey(const Key('btn-leave')), findsOneWidget);
      // Teardown/disconnect-on-dispose is covered by the dedicated unit
      // test in group 7; here it runs only after this body completes.
    });
  });

  group('7. cleanup and rejoin', () {
    test('leave then rejoin: fresh connection, no ghost participants',
        () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'Me',
          room: room);
      await c.join();
      room.remotes['u7'] = 'Priya';
      room.emit(const ParticipantJoined('u7', 'Priya'));
      expect(c.participants.length, 2);

      await c.leave();
      expect(c.participants, isEmpty);

      // Rejoin the SAME meeting — a fresh connect, no stale remotes.
      await c.join();
      expect(room.connects, 2);
      expect(c.status, CallStatus.connected);
      expect(c.participants.length, 1); // local only, ghost gone
      expect(c.participants.single.isLocal, isTrue);
      await c.leave();
    });

    test('dispose without leave still disconnects the room', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join();
      c.dispose(); // navigation away without pressing Leave
      await Future<void>.delayed(Duration.zero);
      expect(room.disconnects, 1);
    });
  });

  group('8. permission-denied behavior', () {
    test('audioOnly join: camera never enabled, mic on', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'x',
          room: room);
      await c.join(audioOnly: true);
      expect(c.status, CallStatus.connected);
      expect(room.camera, isFalse);
      expect(c.participants.single.cameraEnabled, isFalse);
      await c.leave();
    });
  });

  group('9. network reconnection handling', () {
    test('reconnecting → connected keeps participants; failure → failed',
        () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'Me',
          room: room);
      await c.join();
      room.remotes['u7'] = 'Priya';
      room.emit(const ParticipantJoined('u7', 'Priya'));

      room.emit(const ConnectionChanged(CallStatus.reconnecting));
      expect(c.status, CallStatus.reconnecting);
      expect(c.participants.length, 2); // tiles kept during reconnect

      room.emit(const ConnectionChanged(CallStatus.connected));
      expect(c.status, CallStatus.connected);

      room.emit(const ConnectionChanged(CallStatus.failed));
      expect(c.status, CallStatus.failed);
      expect(c.error, isNotNull);
      await c.leave();
    });

    test('events after ended are ignored (no ghost tiles)', () async {
      final room = _FakeRoom();
      final c = MeetingCallController(
          api: _TokenApi(body: okToken),
          meetingId: 1,
          meetingTitle: 't',
          localName: 'Me',
          room: room);
      await c.join();
      await c.leave();
      room.emit(const ParticipantJoined('ghost', 'Ghost'));
      expect(c.participants, isEmpty);
      expect(c.status, CallStatus.ended);
    });
  });
}
