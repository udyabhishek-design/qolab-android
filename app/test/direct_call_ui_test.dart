import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/direct_call/direct_call_controller.dart';
import 'package:qolab_app/direct_call/direct_call_models.dart';
import 'package:qolab_app/direct_call/direct_call_screens.dart';

/// v1.5.63 — regression tests for the direct-call UI fixes:
///
///  1. Accept-failure fix: ActiveDirectCallScreen must route through
///     joinDirectCall (real LiveKitRoomAdapter + permissions), NOT
///     construct a bare MeetingCallScreen — v1.5.62 fell back to _NullRoom
///     and every accepted call failed with "Could not connect…".
///     Here we verify the launch is attempted exactly once and the
///     connecting placeholder shows while the route is pushed (the actual
///     LiveKit connect needs a device).
///
///  2. Alignment: outgoing (dialing) and incoming (ringing) screens must
///     be horizontally CENTERED on both device shapes — tall phone
///     (Motorola G32: 360×800 logical) and wide emulator (BlueStacks
///     landscape: 800×360 logical). The avatar chip's horizontal center
///     must equal the screen's horizontal center.
void main() {
  // ── helpers ─────────────────────────────────────────────────────────

  final api = QolabApi(origin: 'https://example.invalid');

  DirectCallData outgoingCall() => DirectCallData(
        callId: '11111111-1111-1111-1111-111111111111',
        status: 'ringing',
        callType: 'audio',
        recipient: const DirectCallUser(id: 7, name: 'QA Listener'),
      );

  DirectCallData incomingCall() => DirectCallData(
        callId: '22222222-2222-2222-2222-222222222222',
        status: 'ringing',
        callType: 'video',
        caller: const DirectCallUser(id: 5, name: 'QA Admin'),
      );

  Future<void> pumpFlow(
    WidgetTester tester,
    Size size,
    DirectCallPhase phase,
    DirectCallData call,
  ) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    final controller = DirectCallController(
      api: api,
      socket: null,
      myUserId: 26,
    );
    // Seed the desired phase without touching real network paths.
    controller.debugSetState(phase, call,
        token: phase == DirectCallPhase.active ? 'tok' : null,
        url: phase == DirectCallPhase.active ? 'wss://lk.example/room' : null);
    final coordinator = DirectCallCoordinator(controller: controller, api: api);

    await tester.pumpWidget(MaterialApp(
      home: DirectCallFlowScreenHost(coordinator: coordinator),
    ));
    await tester.pump(); // let the post-frame launch callback run
  }

  double avatarCenterX(WidgetTester tester, Key key) {
    final rect = tester.getRect(find.byKey(key));
    return rect.center.dx;
  }

  double screenCenterX(WidgetTester tester) =>
      tester.view.physicalSize.width / tester.view.devicePixelRatio / 2;

  // ── 2. alignment on both device shapes ─────────────────────────────

  testWidgets('outgoing screen avatar is centered on G32-like portrait',
      (tester) async {
    const size = Size(360, 800); // Motorola G32 logical portrait
    await pumpFlow(tester, size, DirectCallPhase.dialing, outgoingCall());
    final avatar = avatarCenterX(tester, const Key('outgoing-avatar'));
    expect(
      (avatar - screenCenterX(tester)).abs(),
      lessThan(2.0),
      reason: 'outgoing avatar must be horizontally centered on 360dp wide',
    );
  });

  testWidgets('outgoing screen avatar is centered on BlueStacks-like landscape',
      (tester) async {
    const size = Size(800, 360); // BlueStacks landscape logical
    await pumpFlow(tester, size, DirectCallPhase.dialing, outgoingCall());
    final avatar = avatarCenterX(tester, const Key('outgoing-avatar'));
    expect(
      (avatar - screenCenterX(tester)).abs(),
      lessThan(2.0),
      reason: 'outgoing avatar must be horizontally centered on 800dp wide',
    );
  });

  testWidgets('incoming screen avatar is centered on G32-like portrait',
      (tester) async {
    const size = Size(360, 800);
    await pumpFlow(tester, size, DirectCallPhase.ringing, incomingCall());
    final avatar = avatarCenterX(tester, const Key('incoming-avatar'));
    expect(
      (avatar - screenCenterX(tester)).abs(),
      lessThan(2.0),
      reason: 'incoming avatar must be horizontally centered on 360dp wide',
    );
  });

  testWidgets('incoming screen avatar is centered on BlueStacks-like landscape',
      (tester) async {
    const size = Size(800, 360);
    await pumpFlow(tester, size, DirectCallPhase.ringing, incomingCall());
    final avatar = avatarCenterX(tester, const Key('incoming-avatar'));
    expect(
      (avatar - screenCenterX(tester)).abs(),
      lessThan(2.0),
      reason: 'incoming avatar must be horizontally centered on 800dp wide',
    );
  });

  // ── 1. accept path no longer constructs a bare MeetingCallScreen ────

  testWidgets(
      'active phase renders the inline media screen (MeetingCallScreen '
      'hosted INSIDE the flow route — v1.5.64 single-route architecture)',
      (tester) async {
    const size = Size(360, 800);
    final controller = DirectCallController(api: api, socket: null, myUserId: 26);
    controller.debugSetState(DirectCallPhase.active, incomingCall(),
        token: 'tok', url: 'wss://lk.example/room');
    final coordinator = DirectCallCoordinator(controller: controller, api: api);
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(MaterialApp(
      home: DirectCallFlowScreenHost(coordinator: coordinator),
    ));
    await tester.pump();
    // v1.5.64: the media screen is hosted inline (no second route push).
    // The hosted MeetingCallScreen shows the joining state on THIS route
    // while the LiveKit adapter connects.
    expect(find.text('Joining meeting…'), findsOneWidget);

    // Settle: fire livekit's 10s timers. Timer 1: the engine's connect
    // timeout (pending from the failed join). Then dismiss — teardown
    // runs room.disconnect(), whose internal 10s waitFor timer must also
    // fire before the test ends.
    await tester.pump(const Duration(seconds: 11));
    controller.dismiss();
    await tester.pump(); // teardown starts
    await tester.pump(const Duration(seconds: 11)); // disconnect waitFor fires
    controller.dispose();
    await tester.pump(const Duration(milliseconds: 100));
  });
}
