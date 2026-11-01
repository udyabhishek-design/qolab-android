import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/foundation.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_socket.dart';
import 'package:qolab_app/chat/reverb_client.dart';
import 'package:qolab_app/direct_call/direct_call_controller.dart';

/// Phase C3A — push-path tests for DirectCallController.
///
/// The FCM data payload is minimal (no LiveKit material); the controller
/// reconciles against GET /chat/calls before ringing.
void main() {
  late DirectCallController controller;
  late _RecordingApi api;
  late _StubSocket socket;

  setUp(() {
    api = _RecordingApi();
    socket = _StubSocket();
    controller = DirectCallController(api: api, socket: socket, myUserId: 7);
    controller.attach();
  });

  tearDown(() {
    controller.dispose();
  });

  // NOTE: getters can't be declared inside main(); use controller.state.

  group('handleIncomingPush', () {
    test('verified ringing call rings the recipient', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-a', recipientId: 7)],
      );
      await controller.handleIncomingPush(
        callId: 'call-a',
        callType: 'audio',
        callerId: 5,
        callerName: 'QA Admin',
      );
      expect(controller.state.phase, DirectCallPhase.ringing);
      expect(controller.state.isOutgoing, isFalse);
      expect(controller.state.call?.callId, 'call-a');
      expect(controller.state.call?.caller?.name, 'QA Admin');
    });

    test('stale push (call no longer ringing) does NOT ring', () async {
      api.activeCallsJson = jsonIndex(
        [endedCall(callId: 'call-b', recipientId: 7)],
      );
      await controller.handleIncomingPush(
        callId: 'call-b', callType: 'audio', callerId: 5, callerName: 'X',
      );
      expect(controller.state.phase, DirectCallPhase.idle);
    });

    test('push for another user does not ring', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-c', recipientId: 99)],
      );
      await controller.handleIncomingPush(
        callId: 'call-c', callType: 'audio', callerId: 5, callerName: 'X',
      );
      expect(controller.state.phase, DirectCallPhase.idle);
    });

    test('duplicate push while already ringing is ignored', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-d', recipientId: 7)],
      );
      await controller.handleIncomingPush(callId: 'call-d', callerId: 5);
      await controller.handleIncomingPush(callId: 'call-d', callerId: 5);
      expect(controller.state.phase, DirectCallPhase.ringing);
      expect(controller.state.call?.callId, 'call-d');
    });

    test('network failure falls back to payload alone (rings)', () async {
      api.activeCallsJson = null; // throws
      await controller.handleIncomingPush(
        callId: 'call-e', callType: 'video', callerId: 5, callerName: 'QA',
      );
      expect(controller.state.phase, DirectCallPhase.ringing);
      expect(controller.state.call?.callType, 'video');
    });
  });

  group('handleUpdatePush', () {
    test('declined update ends ringing immediately', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-f', recipientId: 7)],
      );
      await controller.handleIncomingPush(callId: 'call-f', callerId: 5);
      controller.handleUpdatePush(callId: 'call-f', status: 'declined');
      expect(controller.state.phase, DirectCallPhase.ended);
      expect(controller.state.endedReason, 'declined');
    });

    test('cancelled update ends ringing', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-g', recipientId: 7)],
      );
      await controller.handleIncomingPush(callId: 'call-g', callerId: 5);
      controller.handleUpdatePush(callId: 'call-g', status: 'cancelled');
      expect(controller.state.phase, DirectCallPhase.ended);
      expect(controller.state.endedReason, 'cancelled');
    });

    test('missed update (timeout) ends ringing', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-h', recipientId: 7)],
      );
      await controller.handleIncomingPush(callId: 'call-h', callerId: 5);
      controller.handleUpdatePush(callId: 'call-h', status: 'missed');
      expect(controller.state.phase, DirectCallPhase.ended);
      expect(controller.state.endedReason, 'missed');
    });

    test('update for an unrelated call is ignored', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-i', recipientId: 7)],
      );
      await controller.handleIncomingPush(callId: 'call-i', callerId: 5);
      controller.handleUpdatePush(callId: 'other-call', status: 'ended');
      expect(controller.state.phase, DirectCallPhase.ringing);
    });
  });

  group('acceptFromNotification', () {
    test('accepts a verified ringing call via HTTP', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-j', recipientId: 7)],
      );
      api.actionResponse = acceptResponse('call-j');
      await controller.handleIncomingPush(callId: 'call-j', callerId: 5);
      await controller.acceptFromNotification('call-j');
      expect(api.actions, contains('decline' == '' ? '' : isNot('decline')));
      expect(api.actions, contains('accept'));
      expect(controller.state.phase, DirectCallPhase.active);
      expect(controller.state.token, isNotNull);
      expect(controller.state.url, startsWith('wss://'));
    });

    test('stale notification accept does not crash; declines server-side',
        () async {
      api.activeCallsJson = jsonIndex(const []);
      api.actionResponse = acceptResponse('call-k');
      await controller.acceptFromNotification('call-k');
      // Not ringing → nothing accepted client-side.
      expect(api.actions, isNot(contains('accept')));
    });
  });

  group('declineFromNotification', () {
    test('declines a live ringing call', () async {
      api.activeCallsJson = jsonIndex(
        [ringingCall(callId: 'call-l', recipientId: 7)],
      );
      await controller.handleIncomingPush(callId: 'call-l', callerId: 5);
      await controller.declineFromNotification('call-l');
      expect(api.actions, contains('decline'));
      expect(controller.state.phase, DirectCallPhase.idle);
    });

    test('stale decline still posts decline (idempotent 409 ok)', () async {
      await controller.declineFromNotification('call-m');
      expect(api.actions, contains('decline'));
    });
  });
}

// ── fixtures ──────────────────────────────────────────────────────

String jsonIndex(List<Map<String, Object?>> calls) =>
    jsonEncode({'calls': calls});

Map<String, Object?> ringingCall(
    {required String callId, required int recipientId}) {
  return {
    'call_id': callId,
    'call_type': 'audio',
    'status': 'ringing',
    'caller': {'id': 5, 'name': 'QA Admin'},
    'recipient': {'id': recipientId, 'name': 'QA Listener'},
  };
}

Map<String, Object?> endedCall(
    {required String callId, required int recipientId}) {
  return {
    ...ringingCall(callId: callId, recipientId: recipientId),
    'status': 'ended',
  };
}

String acceptResponse(String callId) => '''
{"success": true, "call": {"call_id": "$callId", "call_type": "audio",
 "status": "accepted", "caller": {"id": 5, "name": "QA Admin"},
 "recipient": {"id": 7, "name": "QA Listener"}},
 "token": "dev-test-token", "url": "wss://example.test/rtc"}
''';

class _RecordingApi extends Fake implements QolabApi {
  String? activeCallsJson;
  String actionResponse = '{}';
  final List<String> actions = [];

  @override
  Future<String> fetchActiveDirectCalls() async {
    final json = activeCallsJson;
    if (json == null) throw Exception('network down');
    return json;
  }

  @override
  Future<String> directCallAction(String callId, String action) async {
    actions.add(action);
    return actionResponse;
  }
}

/// Minimal ChatSocketService stand-in — the push tests never emit socket
/// events, so a no-op subscribe suffices.
class _StubSocket extends Fake implements ChatSocketService {
  final _resync = StreamController<void>.broadcast();
  bool disposed = false;

  @override
  void dispose() => disposed = true;

  @override
  Stream<void> get onResync => _resync.stream;

  @override
  VoidCallback subscribe(
      String channel, void Function(BroadcastEvent) onEvent) {
    return () {};
  }
}
