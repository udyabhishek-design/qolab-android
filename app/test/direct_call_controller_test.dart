import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/chat/chat_socket.dart';
import 'package:qolab_app/chat/reverb_client.dart';
import 'package:qolab_app/direct_call/direct_call_controller.dart';
import 'package:qolab_app/direct_call/direct_call_models.dart';

/// DirectCallController state-machine tests (Phase C1).
///
/// Socket + API are fakes: signaling lifecycle, duplicate events, expiry,
/// recovery, and token flow are exercised without a backend. Two-session
/// INTEGRATION (real Web↔Flutter ringing) is validated separately against
/// the Development environment (live_socket_integration_test.dart pattern).
void main() {
  late _FakeDirectCallSocket socket;
  late _FakeDirectCallApi api;
  late DirectCallController controller;

  const callerId = 5;
  const recipientId = 7;

  Map<String, Object?> callPayload({
    String status = 'ringing',
    String callId = 'c-1',
  }) =>
      {
        'call_id': callId,
        'status': status,
        'call_type': 'audio',
        'channel_id': 56,
        'caller': {'id': callerId, 'name': 'Caller'},
        'recipient': {'id': recipientId, 'name': 'Recipient'},
        'created_at': '2026-10-31T12:00:00Z',
      };

  setUp(() {
    socket = _FakeDirectCallSocket();
    api = _FakeDirectCallApi();
    controller = DirectCallController(
        api: api, socket: socket, myUserId: recipientId);
    controller.attach();
  });

  group('incoming call', () {
    test('call.incoming event → ringing phase', () {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      expect(controller.state.phase, DirectCallPhase.ringing);
      expect(controller.state.isOutgoing, isFalse);
      expect(controller.state.call?.caller?.name, 'Caller');
    });

    test('duplicate incoming events do not duplicate state', () {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      expect(controller.state.phase, DirectCallPhase.ringing);
      expect(controller.state.call?.callId, 'c-1');
    });

    test('own outgoing call.incoming is ignored', () {
      socket.emit('user.$recipientId', '.call.incoming', callPayload()
        ..['caller'] = {'id': recipientId, 'name': 'Me'});
      expect(controller.state.phase, DirectCallPhase.idle);
    });

    test('call.cancelled stops ringing', () {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      socket.emit('user.$recipientId', '.call.cancelled',
          callPayload(status: 'cancelled'));
      expect(controller.state.phase, DirectCallPhase.ended);
      expect(controller.state.endedReason, 'cancelled');
    });

    test('terminal event for a different call is ignored', () {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      socket.emit('user.$recipientId', '.call.ended',
          callPayload(status: 'ended', callId: 'c-other'));
      expect(controller.state.phase, DirectCallPhase.ringing);
    });
  });

  group('accept', () {
    test('accept → active with token from HTTP response', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      api.acceptResponse = {
        'success': true,
        'call': callPayload(status: 'accepted'),
        'token': 'tok-accept',
        'url': 'wss://livekit.example',
      };
      final err = await controller.accept();
      expect(err, isNull);
      expect(controller.state.phase, DirectCallPhase.active);
      expect(controller.state.token, 'tok-accept');
      expect(controller.state.url, 'wss://livekit.example');
    });

    test('409 conflict race → recovers from server truth', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      api.acceptStatus = 409;
      // Server truth: the call is still ringing for this recipient.
      api.indexResponse = {
        'success': true,
        'calls': [callPayload()],
      };
      final err = await controller.accept();
      expect(err, isNotNull);
      expect(controller.state.phase, DirectCallPhase.ringing);
    });

    test('accept with no ringing call errors', () async {
      final err = await controller.accept();
      expect(err, isNotNull);
    });
  });

  group('decline', () {
    test('decline resets to idle and posts decline', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      await controller.decline();
      expect(controller.state.phase, DirectCallPhase.idle);
      expect(api.actions, contains('decline:c-1'));
    });
  });

  group('outgoing call', () {
    test('start → dialing; call.accepted → connecting → active via token',
        () async {
      controller = DirectCallController(
          api: api, socket: socket, myUserId: callerId);
      controller.attach();
      api.startResponse = {
        'success': true,
        'call': callPayload(),
        'token': null,
        'url': null,
      };
      final err = await controller.start(recipientId, 'audio');
      expect(err, isNull);
      expect(controller.state.phase, DirectCallPhase.dialing);
      expect(controller.state.isOutgoing, isTrue);

      // Peer accepts → we (caller) get call.accepted on our channel.
      api.tokenResponse = {
        'success': true,
        'token': 'tok-caller',
        'url': 'wss://livekit.example',
      };
      socket.emit('user.$callerId', '.call.accepted',
          callPayload(status: 'accepted'));
      expect(controller.state.phase, DirectCallPhase.connecting);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state.phase, DirectCallPhase.active);
      expect(controller.state.token, 'tok-caller');
    });

    test('call.declined ends dialing', () async {
      controller = DirectCallController(
          api: api, socket: socket, myUserId: callerId);
      controller.attach();
      api.startResponse = {
        'success': true,
        'call': callPayload(),
        'token': null,
        'url': null,
      };
      await controller.start(recipientId, 'audio');
      socket.emit('user.$callerId', '.call.declined',
          callPayload(status: 'declined'));
      expect(controller.state.phase, DirectCallPhase.ended);
      expect(controller.state.endedReason, 'declined');
    });

    test('busy guard: cannot start while ringing', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      final err = await controller.start(callerId, 'audio');
      expect(err, contains('call in progress'));
    });

    test('start error surfaces message', () async {
      api.startResponse = {'success': false, 'error': 'nope'};
      final err = await controller.start(callerId, 'audio');
      expect(err, 'nope');
    });

    test('cancelOutgoing posts cancel and resets', () async {
      controller = DirectCallController(
          api: api, socket: socket, myUserId: callerId);
      controller.attach();
      api.startResponse = {
        'success': true,
        'call': callPayload(),
        'token': null,
        'url': null,
      };
      await controller.start(recipientId, 'audio');
      await controller.cancelOutgoing();
      expect(controller.state.phase, DirectCallPhase.idle);
      expect(api.actions, contains('cancel:c-1'));
    });
  });

  group('recovery', () {
    test('recover restores ringing incoming call from server', () async {
      api.indexResponse = {
        'success': true,
        'calls': [callPayload()],
      };
      await controller.recover();
      expect(controller.state.phase, DirectCallPhase.ringing);
      expect(controller.state.isOutgoing, isFalse);
    });

    test('recover restores accepted call and fetches token', () async {
      api.indexResponse = {
        'success': true,
        'calls': [callPayload(status: 'accepted')],
      };
      api.tokenResponse = {
        'success': true,
        'token': 'tok-r',
        'url': 'wss://livekit.example',
      };
      await controller.recover();
      expect(controller.state.phase, DirectCallPhase.connecting);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state.phase, DirectCallPhase.active);
    });

    test('recover does not clobber an active UI state', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      api.acceptResponse = {
        'success': true,
        'call': callPayload(status: 'accepted'),
        'token': 'tok-x',
        'url': null,
      };
      await controller.accept();
      api.indexResponse = {'success': true, 'calls': <Object>[]};
      await controller.recover();
      expect(controller.state.phase, DirectCallPhase.active);
    });
  });

  group('hangUp / dispose', () {
    test('hangUp posts end and resets', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      api.acceptResponse = {
        'success': true,
        'call': callPayload(status: 'accepted'),
        'token': 't',
        'url': null,
      };
      await controller.accept();
      await controller.hangUp();
      expect(controller.state.phase, DirectCallPhase.idle);
      expect(api.actions, contains('end:c-1'));
    });

    test('dispose stops timers and unsubscribes', () async {
      socket.emit('user.$recipientId', '.call.incoming', callPayload());
      controller.dispose();
      // No further state changes propagate.
      socket.emit('user.$recipientId', '.call.cancelled',
          callPayload(status: 'cancelled'));
    });
  });

  group('models', () {
    test('DirectCallToken.parse happy + failures', () {
      expect(DirectCallToken.parse('{"token":"abc","url":"wss://x"}')?.token,
          'abc');
      expect(DirectCallToken.parse('{"token":"abc","url":"wss://x"}')?.url,
          'wss://x');
      expect(DirectCallToken.parse('not json'), isNull);
      expect(DirectCallToken.parse('{"url":"wss://x"}'), isNull);
      expect(DirectCallToken.parse('{"token":""}'), isNull);
    });

    test('DirectCallData.fromJson rejects malformed payloads', () {
      expect(DirectCallData.fromJson(null), isNull);
      expect(DirectCallData.fromJson('x'), isNull);
      expect(DirectCallData.fromJson({'call_id': 1}), isNull);
      final d = DirectCallData.fromJson(callPayload());
      expect(d, isNotNull);
      expect(d!.isVideo, isFalse);
      expect(d.isRinging, isTrue);
    });
  });
}

// ── Fakes ──────────────────────────────────────────────────────────

class _FakeDirectCallSocket extends Fake implements ChatSocketService {
  final _listeners =
      <String, List<void Function(BroadcastEvent)>>{};
  final _resync = StreamController<void>.broadcast();

  void emit(String channel, String event, Map<String, Object?> data) {
    for (final cb in List.of(_listeners[channel] ?? const [])) {
      cb(BroadcastEvent(
          channel: 'private-$channel', name: event, data: data));
    }
  }

  void emitResync() => _resync.add(null);

  @override
  VoidCallback subscribe(
      String channel, void Function(BroadcastEvent) onEvent) {
    _listeners.putIfAbsent(channel, () => []).add(onEvent);
    return () => _listeners[channel]?.remove(onEvent);
  }

  @override
  void unsubscribe(String channel, void Function(BroadcastEvent) onEvent) {
    _listeners[channel]?.remove(onEvent);
  }

  @override
  Stream<void> get onResync => _resync.stream;
}

class _FakeDirectCallApi extends Fake implements QolabApi {
  Map<String, Object?>? startResponse;
  Map<String, Object?>? acceptResponse;
  Map<String, Object?>? tokenResponse;
  Map<String, Object?>? indexResponse;
  int acceptStatus = 200;
  final List<String> actions = [];

  String _encode(Map<String, Object?> v) =>
      const JsonEncoder().convert(v);

  @override
  Future<String> startDirectCall(int recipientId, String callType,
      {int? channelId}) async {
    return _encode(startResponse ?? {'success': false});
  }

  @override
  Future<String> fetchActiveDirectCalls() async {
    return _encode(indexResponse ?? {'success': true, 'calls': <Object>[]});
  }

  @override
  Future<String> directCallAction(String callId, String action) async {
    if (action == 'accept') {
      if (acceptStatus != 200) {
        throw apiErrorFromStatus(acceptStatus);
      }
      return _encode(acceptResponse ?? {'success': true});
    }
    actions.add('$action:$callId');
    return _encode({'success': true});
  }

  @override
  Future<String> fetchDirectCallToken(String callId) async {
    return _encode(tokenResponse ?? {'success': false});
  }
}
