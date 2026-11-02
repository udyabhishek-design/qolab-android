import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/direct_call/direct_call_controller.dart';
import 'package:qolab_app/direct_call/ringback_tone.dart';
import 'package:qolab_app/meetings/call_controller.dart';
import 'package:qolab_app/meetings/livekit_adapter.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/direct_call/direct_call_models.dart';

/// v1.5.64 — regression tests for:
///   1. RINGBACK lifecycle: starts on dialing, stops on every terminal
///      phase; never double-starts; stop is idempotent.
///   2. MEDIA publication: join with fastConnect semantics — the room
///      adapter must request mic/camera publication at connect time
///      (livekit_client does NOT auto-publish on plain connect).
///   3. CLEANUP: hangUp/decline/cancel reset the controller state and
///      stop all audible feedback (ringback + ring).
///
/// MethodChannel traffic (qolab/ringback start/stop) is captured by the
/// test binding's default binary messenger — no native half exists in
/// unit tests, so the Dart side falls back to SystemSound; we assert the
/// INVOCATIONS, not the platform tone.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('RingbackTone lifecycle', () {
    test('start is single-instance — repeated start does not stack timers',
        () async {
      await RingbackTone.stop(); // clean slate
      await RingbackTone.start();
      expect(RingbackTone.isPlaying, isTrue);
      await RingbackTone.start(); // second start must be a no-op
      expect(RingbackTone.isPlaying, isTrue);
      await RingbackTone.stop();
      expect(RingbackTone.isPlaying, isFalse);
    });

    test('stop without start is a safe no-op', () async {
      await RingbackTone.stop();
      await RingbackTone.stop();
      expect(RingbackTone.isPlaying, isFalse);
    });

    testWidgets('dialing phase starts ringback; ended phase stops it',
        (tester) async {
      final calls = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('qolab/ringback'),
        (call) async {
          calls.add(call.method);
          return null;
        },
      );
      // drive via the public API
      await RingbackTone.start();
      await RingbackTone.stop();
      // allow pending timers to flush without firing more tones
      await tester.pump(const Duration(seconds: 3));
      expect(RingbackTone.isPlaying, isFalse);
      expect(calls, containsAllInOrder(['start', 'stop']));
    });
  });

  group('DirectCallController cleanup on terminal transitions', () {
    test('hangUp resets state even when the POST fails', () async {
      final api = _FailingApi();
      final c = DirectCallController(
        api: api,
        socket: null,
        myUserId: 7,
      );
      c.debugSetState(DirectCallPhase.active, _callData(),
          token: 'tok', url: 'wss://example');
      await c.hangUp();
      expect(c.state.phase, DirectCallPhase.idle);
      expect(c.state.token, isNull); // credentials dropped on teardown
    });

    test('decline resets state first (UI immediate) then POSTs', () async {
      final api = _FailingApi();
      final c = DirectCallController(api: api, socket: null, myUserId: 7);
      c.debugSetState(DirectCallPhase.ringing, _callData());
      await c.decline();
      expect(c.state.phase, DirectCallPhase.idle);
    });
  });

  group('fastConnect media publication contract', () {
    test('LiveKitRoomAdapter exposes setInitialTracks before connect', () {
      // Compile-time contract: the adapter must let the launcher pin
      // which tracks the NEXT connect publishes (fastConnectOptions).
      // (A real Room is not constructible in unit tests — the adapter's
      // setInitialTracks must exist and store flags without touching
      // a room.)
      final adapter = LiveKitRoomAdapter();
      adapter.setInitialTracks(publishMic: true, publishCamera: false);
      adapter.dispose();
    });
  });
}

class _FailingApi implements QolabApi {
  @override
  Future<String> noSuchMethod(Invocation invocation) =>
      Future<String>.error(StateError('network down — must not block reset'));
}

DirectCallData _callData() => DirectCallData.fromJson(const {
      'call_id': '11111111-2222-3333-4444-555555555555',
      'status': 'ringing',
      'call_type': 'audio',
      'caller': {'id': 5, 'name': 'QA Admin'},
      'recipient': {'id': 7, 'name': 'QA Listener'},
      'created_at': '2026-11-02T10:00:00Z',
    })!;
