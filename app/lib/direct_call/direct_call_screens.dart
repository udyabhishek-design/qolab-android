/// Direct Calling (Phase C1) — UI screens.
///
/// IncomingCallScreen: full-screen accept/decline with ringtone+vibration.
/// OutgoingCallScreen: "Ringing…" with cancel.
/// The ACTIVE call reuses the meetings call screen via [DirectCallLauncher].
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:livekit_client/livekit_client.dart' as lk;
import 'package:flutter_webrtc/flutter_webrtc.dart' as rtc;

import '../api/qolab_api.dart';
import '../meetings/call_controller.dart';
import '../meetings/call_screen.dart';
import '../meetings/livekit_adapter.dart';
import '../theme.dart';
import 'direct_call_controller.dart';
import 'direct_call_models.dart';
import 'ringback_tone.dart';

/// Wraps a [DirectCallController] with UI flow management: shows the
/// appropriate screen for each phase and drives ringtone/vibration while
/// ringing. One instance per app shell.
class DirectCallCoordinator {
  DirectCallCoordinator({required this.controller, required this.api});

  final DirectCallController controller;
  final QolabApi api;
  BuildContext? _context;
  bool _showing = false;
  bool _busy = false;
  Timer? _ringTimer;
  bool _vibrateLoop = false;
  int _ringCount = 0;

  /// Attach the user-channel listener (idempotent).
  void attach(BuildContext context, {required String localName}) {
    _context = context;
    _localName = localName;
    controller.attach();
    controller.addListener(_onStateChanged);
    // Reconnect recovery: ask the backend for current call state.
    controller.recover();
  }

  String _localName = '';

  /// Local display name used for the LiveKit identity.
  String get localName => _localName;
  set localName(String v) => _localName = v;

  void detach() {
    controller.removeListener(_onStateChanged);
    _stopRing();
    unawaited(RingbackTone.stop());
    _context = null;
  }

  void _onStateChanged() {
    final ctx = _context;
    if (ctx == null) return;
    final s = controller.state;

    // ── Audible states (v1.5.64) ────────────────────────────────────
    // INCOMING (ringing): system alert-tone loop + vibration, as before.
    // OUTGOING (dialing): proper repeating RINGBACK (ToneGenerator via
    // qolab/ringback) so the caller hears standard ring-ring while the
    // recipient's device rings. Stopped on EVERY exit from dialing:
    // answered, declined, cancelled, missed, failed, or app teardown.
    if (s.phase == DirectCallPhase.dialing) {
      unawaited(RingbackTone.start());
    } else {
      unawaited(RingbackTone.stop());
    }
    if (s.phase == DirectCallPhase.ringing) {
      _startRing();
    } else {
      _stopRing();
    }

    if (s.phase == DirectCallPhase.idle) {
      if (_showing) {
        _showing = false;
        // v1.5.64: the media screen is INLINE in the flow route now — a
        // single pop removes the whole call UI. Only pop if the flow
        // route is still the top-most route we pushed (never pop a
        // stranger's route out from under the user).
        final nav = Navigator.of(ctx);
        if (nav.canPop()) nav.pop();
      }
      return;
    }

    if (_busy || _showing) {
      // The active screen takes over; keep the ring screen hidden.
      return;
    }
    _showing = true;
    _busy = false;
    Navigator.of(ctx).push(MaterialPageRoute(
      fullscreenDialog: true,
      barrierDismissible: false,
      builder: (_) => _DirectCallFlowScreen(coordinator: this),
    )).then((_) {
      _showing = false;
    });
  }

  Future<void> handleAccept() async {
    final err = await controller.accept();
    if (err != null && _context != null) {
      // Surface briefly; state machine already reset or re-synced.
      debugPrint('accept error: $err');
    }
  }

  Future<void> handleDecline() => controller.decline();
  Future<void> handleCancel() => controller.cancelOutgoing();

  /// Ringtone + vibration while foregrounded (C1: foreground only).
  void _startRing() {
    if (_ringTimer != null) return; // already ringing
    _ringCount = 0;
    SystemSound.play(SystemSoundType.alert);
    _vibrateLoop = true;
    _vibrate();
    _ringTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      _ringCount++;
      SystemSound.play(SystemSoundType.alert);
      if (_ringCount % 2 == 1) _vibrate();
      // Local UI safety net at ~35s (server expiry is authoritative).
      if (_ringCount >= 18) {
        _stopRing();
        controller.recover();
      }
    });
  }

  Future<void> _vibrate() async {
    while (_vibrateLoop) {
      await HapticFeedback.vibrate();
      await Future<void>.delayed(const Duration(milliseconds: 1200));
    }
  }

  void _stopRing() {
    _ringTimer?.cancel();
    _ringTimer = null;
    _vibrateLoop = false;
  }
}

/// Single flow screen: renders per-phase content. When the call becomes
/// ACTIVE it swaps to the meetings LiveKit screen (audio-first).
/// Public host for [_DirectCallFlowScreen] so widget tests can pump a
/// seeded phase without a live socket or network.
class DirectCallFlowScreenHost extends StatelessWidget {
  const DirectCallFlowScreenHost({super.key, required this.coordinator});

  final DirectCallCoordinator coordinator;

  @override
  Widget build(BuildContext context) =>
      _DirectCallFlowScreen(coordinator: coordinator);
}

class _DirectCallFlowScreen extends StatefulWidget {
  const _DirectCallFlowScreen({required this.coordinator});

  final DirectCallCoordinator coordinator;

  @override
  State<_DirectCallFlowScreen> createState() => _DirectCallFlowScreenState();
}

class _DirectCallFlowScreenState extends State<_DirectCallFlowScreen> {
  @override
  void initState() {
    super.initState();
    widget.coordinator.controller.addListener(_rebuild);
  }

  @override
  void dispose() {
    widget.coordinator.controller.removeListener(_rebuild);
    super.dispose();
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.coordinator.controller.state;
    final call = s.call;

    if (s.phase == DirectCallPhase.active && s.token != null) {
      // Hand off to the LiveKit media screen with the direct-call token.
      return ActiveDirectCallScreen(
        coordinator: widget.coordinator,
        call: call!,
        token: s.token!,
        url: s.url,
        localName: widget.coordinator._localName,
      );
    }
    if (s.phase == DirectCallPhase.ringing) {
      return _IncomingCallScreen(
          coordinator: widget.coordinator, call: call!);
    }
    if (s.phase == DirectCallPhase.dialing) {
      return _OutgoingCallScreen(
          coordinator: widget.coordinator, call: call!);
    }
    // ended / connecting / error states: compact status screen.
    return _CallEndedScreen(state: s, onClose: widget.coordinator.controller.dismiss);
  }
}

class _IncomingCallScreen extends StatelessWidget {
  const _IncomingCallScreen({required this.coordinator, required this.call});

  final DirectCallCoordinator coordinator;
  final DirectCallData call;

  @override
  Widget build(BuildContext context) {
    final peer = call.caller;
    final isVideo = call.isVideo;
    return Scaffold(
      backgroundColor: QolabTheme.ink,
      // v1.5.63: explicit Center + ConstrainedBox (same as outgoing) so
      // the incoming UI stays horizontally centered on every form factor.
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              children: [
                const Spacer(flex: 2),
                CircleAvatar(
                  key: const Key('incoming-avatar'),
                  radius: 44,
                  backgroundColor: QolabTheme.canvas,
                  child: Text(
                    (peer?.name ?? '?').characters.first.toUpperCase(),
                    style: const TextStyle(
                        color: QolabTheme.ink,
                        fontSize: 30,
                        fontWeight: FontWeight.w700),
                  ),
                ),
                const SizedBox(height: 18),
                Text(peer?.name ?? 'Member',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        color: Colors.white, fontSize: 22, fontWeight: FontWeight.w700)),
                Text(
                  isVideo ? 'Incoming video call' : 'Incoming audio call',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white70, fontSize: 14),
                ),
                const Spacer(flex: 3),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _CallAction(
                      icon: Icons.call_end,
                      label: 'Decline',
                      color: Colors.red.shade600,
                      onPressed: () async {
                        await coordinator.handleDecline();
                      },
                    ),
                    _CallAction(
                      icon: isVideo ? Icons.videocam : Icons.call,
                      label: 'Accept',
                      color: Colors.green.shade600,
                      onPressed: () async {
                        await coordinator.handleAccept();
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 48),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _OutgoingCallScreen extends StatelessWidget {
  const _OutgoingCallScreen({required this.coordinator, required this.call});

  final DirectCallCoordinator coordinator;
  final DirectCallData call;

  @override
  Widget build(BuildContext context) {
    final peer = call.recipient;
    return Scaffold(
      backgroundColor: QolabTheme.ink,
      // v1.5.63: explicit Center + ConstrainedBox so the dialing UI is
      // horizontally centered on every form factor (narrow phones like the
      // G32 AND wide emulators like BlueStacks) instead of relying on a
      // full-width Column, which the platform view inset on some devices
      // could offset. Content is capped at 420 logical px of width.
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              children: [
                const Spacer(flex: 2),
                CircleAvatar(
                  key: const Key('outgoing-avatar'),
                  radius: 44,
                  backgroundColor: QolabTheme.canvas,
                  child: Text(
                    (peer?.name ?? '?').characters.first.toUpperCase(),
                    style: const TextStyle(
                        color: QolabTheme.ink,
                        fontSize: 30,
                        fontWeight: FontWeight.w700),
                  ),
                ),
                const SizedBox(height: 18),
                Text(peer?.name ?? 'Member',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                const SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white70),
                ),
                const SizedBox(height: 10),
                Text(
                  call.isVideo ? 'Ringing · video call' : 'Ringing · audio call',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white70, fontSize: 14),
                ),
                const Spacer(flex: 3),
                Center(
                  child: _CallAction(
                    icon: Icons.call_end,
                    label: 'Cancel',
                    color: Colors.red.shade600,
                    onPressed: () => coordinator.handleCancel(),
                  ),
                ),
                const SizedBox(height: 48),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _CallEndedScreen extends StatelessWidget {
  const _CallEndedScreen({required this.state, required this.onClose});

  final DirectCallState state;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final reason = state.error ??
        switch (state.endedReason) {
          'declined' => 'Call declined',
          'cancelled' => 'Call cancelled',
          'missed' => 'No answer',
          'ended' => 'Call ended',
          'failed' => 'Call failed',
          _ => 'Call ended',
        };
    return Scaffold(
      backgroundColor: QolabTheme.ink,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(reason,
                style: const TextStyle(
                    color: Colors.white, fontSize: 17, fontWeight: FontWeight.w600)),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: onClose,
              style: FilledButton.styleFrom(backgroundColor: QolabTheme.canvas, foregroundColor: QolabTheme.ink),
              child: const Text('Close'),
            ),
          ],
        ),
      ),
    );
  }
}

class _CallAction extends StatelessWidget {
  const _CallAction({
    required this.icon,
    required this.label,
    required this.color,
    required this.onPressed,
  });

  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        FloatingActionButton(
          heroTag: label,
          backgroundColor: color,
          foregroundColor: Colors.white,
          onPressed: onPressed,
          child: Icon(icon, size: 30),
        ),
        const SizedBox(height: 8),
        Text(label,
            style: const TextStyle(color: Colors.white70, fontSize: 12.5)),
      ],
    );
  }
}

/// ACTIVE direct call: renders the meetings LiveKit screen INLINE with
/// direct-call credentials (token already minted via accept/token HTTP —
/// NOT via a join-token fetch).
///
/// v1.5.64 route architecture: the media screen is a CHILD of the flow
/// route — no second route pushed on top. v1.5.63 pushed a separate
/// MaterialPageRoute from a post-frame callback; when the call ended, the
/// coordinator popped once (media route), leaving the ORPHANED flow route
/// underneath with a dead Close button (stuck "Call ended" screen).
/// Inline embedding means every terminal path (call.ended, partner left,
/// local hang-up) tears down the media widget and the SINGLE flow route
/// in one step.
class ActiveDirectCallScreen extends StatefulWidget {
  const ActiveDirectCallScreen({
    super.key,
    required this.coordinator,
    required this.call,
    required this.token,
    this.url,
    required this.localName,
  });

  final DirectCallCoordinator coordinator;
  final DirectCallData call;
  final String token;
  final String? url;
  final String localName;

  @override
  State<ActiveDirectCallScreen> createState() => _ActiveDirectCallScreenState();
}

class _ActiveDirectCallScreenState extends State<ActiveDirectCallScreen> {
  MeetingCallController? _media;
  LiveKitRoomAdapter? _adapter;
  bool _leaving = false;

  @override
  void initState() {
    super.initState();
    widget.coordinator.controller.addListener(_onCallStateChanged);

    // Build the media controller inline with the real LiveKit adapter.
    // Publish local tracks during the join handshake (v1.5.64 media fix);
    // mic permission was granted when the call flow started.
    _adapter = LiveKitRoomAdapter();
    final cameraCall = widget.call.callType == 'video';
    _adapter!.setInitialTracks(publishMic: true, publishCamera: cameraCall);
    _media = MeetingCallController(
      api: widget.coordinator.api,
      meetingId: 0,
      meetingTitle: (cameraCall ? 'Video call · ' : 'Call · ') +
          (widget.call.recipient?.name ??
              widget.call.caller?.name ??
              'Member'),
      localName: widget.localName,
      room: _adapter!,
    );
    _media!.join(
        audioOnly: !cameraCall,
        directToken: widget.token,
        directUrl: widget.url);
  }

  void _onCallStateChanged() {
    final s = widget.coordinator.controller.state;
    final terminal =
        s.phase == DirectCallPhase.idle || s.phase == DirectCallPhase.ended;
    if (terminal && !_leaving) {
      // Partner ended/declined/cancelled, or the backend transitioned the
      // call (call.ended via Reverb or FCM) — tear down media; the flow
      // screen then shows the ended banner whose Close pops the ONE route.
      _teardownMedia();
    }
  }

  Future<void> _teardownMedia() async {
    if (_leaving) return;
    _leaving = true;
    final m = _media;
    final a = _adapter;
    if (m != null && m.status != CallStatus.ended) {
      try {
        await m.leave();
      } catch (_) {}
    }
    // Fully dispose the room (disconnect + dispose) — adapter.dispose()
    // alone leaves livekit's engine timers alive after route teardown.
    if (a != null) {
      try {
        await a.disconnect();
      } catch (_) {}
      a.dispose();
    }
    _adapter = null;
    _media = null;
    if (mounted) setState(() {});
  }

  /// Local hang-up (End Call in the media controls).
  Future<void> _localHangUp() async {
    await _teardownMedia();
    await widget.coordinator.controller.hangUp();
  }

  @override
  void dispose() {
    widget.coordinator.controller.removeListener(_onCallStateChanged);
    final a = _adapter;
    if (a != null) {
      // Route torn down without teardown (edge: parent popped the whole
      // navigator): synchronously release what we can.
      try {
        unawaited(a.disconnect());
      } catch (_) {}
      a.dispose();
    }
    _media?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final media = _media;
    final s = widget.coordinator.controller.state;
    final terminal = s.phase == DirectCallPhase.idle ||
        s.phase == DirectCallPhase.ended;
    if (media != null && !terminal) {
      return MeetingCallScreen.hosted(
        controller: media,
        videoRendererBuilder: (c, p) => _adapter == null
            ? const SizedBox.shrink()
            : _DirectCallVideoTile(adapter: _adapter!, participant: p),
        onLocalLeave: _localHangUp,
      );
    }
    // Media gone / call terminal: compact ended banner on this route.
    return _CallEndedScreen(
      state: s,
      onClose: widget.coordinator.controller.dismiss,
    );
  }
}

/// Inline video tile for the direct call's media screen — mirrors the
/// launcher's tile but without permission handling (the direct-call flow
/// already resolved them).
class _DirectCallVideoTile extends StatefulWidget {
  const _DirectCallVideoTile({required this.adapter, required this.participant});

  final LiveKitRoomAdapter adapter;
  final CallParticipant participant;

  @override
  State<_DirectCallVideoTile> createState() => _DirectCallVideoTileState();
}

class _DirectCallVideoTileState extends State<_DirectCallVideoTile> {
  lk.VideoTrack? _track;
  StreamSubscription<LiveKitEvent>? _sub;

  void _resync() {
    final p = widget.participant;
    final lk.VideoTrack? next = p.isLocal
        ? widget.adapter.localVideoTrack
        : widget.adapter.videoTrackFor(p.identity);
    if (!identical(next, _track)) {
      setState(() => _track = next);
    }
  }

  @override
  void initState() {
    super.initState();
    _resync();
    _sub = widget.adapter.events.listen((_) {
      if (mounted) _resync();
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final track = _track;
    if (track == null) {
      return Container(color: const Color(0xFF231D27));
    }
    return lk.VideoTrackRenderer(
      track,
      fit: rtc.RTCVideoViewObjectFit.RTCVideoViewObjectFitCover,
      mirrorMode: widget.participant.isLocal
          ? lk.VideoViewMirrorMode.mirror
          : lk.VideoViewMirrorMode.off,
    );
  }
}
