/// Direct Calling (Phase C1) — UI screens.
///
/// IncomingCallScreen: full-screen accept/decline with ringtone+vibration.
/// OutgoingCallScreen: "Ringing…" with cancel.
/// The ACTIVE call reuses the meetings call screen via [DirectCallLauncher].
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api/qolab_api.dart';
import '../meetings/call_launcher.dart' show joinDirectCall;
import '../theme.dart';
import 'direct_call_controller.dart';
import 'direct_call_models.dart';

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
    _context = null;
  }

  void _onStateChanged() {
    final ctx = _context;
    if (ctx == null) return;
    final s = controller.state;
    final showRinging =
        s.phase == DirectCallPhase.ringing || s.phase == DirectCallPhase.dialing;
    if (showRinging) {
      _startRing();
    } else {
      _stopRing();
    }

    if (s.phase == DirectCallPhase.idle) {
      if (_showing) {
        _showing = false;
        Navigator.of(ctx).canPop() ? Navigator.of(ctx).pop() : null;
      }
      return;
    }

    if (_busy || _showing) {
      // The active screen takes over; keep the ring screen hidden.
      return;
    }
    _showing = true;
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

/// ACTIVE direct call: pushes the meetings LiveKit screen with direct-call
/// credentials (token already minted via accept/token HTTP — NOT via a
/// join-token fetch). v1.5.63: routes through [joinDirectCall] so a real
/// LiveKitRoomAdapter + video renderer are attached and mic/camera
/// permissions are requested BEFORE LiveKit starts capturing. The bare
/// MeetingCallScreen.forDirectCall used in ≤v1.5.62 passed no adapter, so
/// the controller fell back to _NullRoom and every accepted call failed
/// with "Could not connect to the meeting".
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
  bool _launched = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _launch());
  }

  Future<void> _launch() async {
    if (_launched || !mounted) return;
    _launched = true;
    await joinDirectCall(
      context,
      api: widget.coordinator.api,
      token: widget.token,
      serverUrl: widget.url ?? '',
      title: (widget.call.callType == 'video' ? 'Video call · ' : 'Call · ') +
          (widget.call.recipient?.name ?? widget.call.caller?.name ?? 'Member'),
      localName: widget.localName,
      videoCall: widget.call.isVideo,
      onLeave: () => widget.coordinator.controller.hangUp(),
    );
  }

  @override
  Widget build(BuildContext context) {
    // Connecting placeholder while permissions resolve and the call route
    // is being pushed by _launch().
    return Scaffold(
      backgroundColor: QolabTheme.ink,
      body: const SafeArea(
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(
                    strokeWidth: 2.2, color: Colors.white70),
              ),
              SizedBox(height: 12),
              Text('Connecting…',
                  style: TextStyle(color: Colors.white70, fontSize: 14)),
            ],
          ),
        ),
      ),
    );
  }
}
