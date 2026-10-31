library;

/// MeetingCallScreen — the in-call experience for Qolab meetings (Phase B).
///
/// Renders a responsive video grid driven by MeetingCallController: remote
/// participants FIRST, local tile last (never full-screen). Each tile is
/// either a live video renderer (subscribed remote track / enabled local
/// camera) or a clean name-initials placeholder (audio-only / camera off).
///
/// The video widget is injected via [videoRendererBuilder] so widget tests
/// run without native plugins — production passes a VideoTrackRenderer-
/// backed builder that pulls tracks from the LiveKit adapter.

import 'dart:async';

import 'package:flutter/material.dart';

import '../api/qolab_api.dart';
import '../theme.dart';
import 'call_controller.dart';

class MeetingCallScreen extends StatefulWidget {
  const MeetingCallScreen({
    super.key,
    required this.api,
    required this.meetingId,
    required this.meetingTitle,
    required this.localName,
    this.audioOnly = false,
    this.adapter,
    this.videoRendererBuilder,
    this.token,
    this.serverUrl,
    this.onLeave,
  });

  /// Direct Calling (Phase C1): join with a PRE-MINTED LiveKit token
  /// instead of fetching a meetings join-token. [onLeave] runs after the
  /// LiveKit room is torn down (e.g. POST /chat/calls/{id}/end).
  MeetingCallScreen.forDirectCall({
    super.key,
    required this.api,
    required this.token,
    required this.serverUrl,
    required String title,
    required this.localName,
    this.audioOnly = false,
    this.adapter,
    this.videoRendererBuilder,
    this.onLeave,
  })  : meetingId = 0,
        meetingTitle = title;

  final QolabApi api;
  final int meetingId;
  final String meetingTitle;
  final String localName;

  /// Direct-call mode: token + url are provided up front.
  final String? token;
  final String? serverUrl;

  /// Direct-call mode: called when the user leaves the call.
  final Future<void> Function()? onLeave;

  /// Join without camera (permission denied or user choice).
  final bool audioOnly;

  /// Production LiveKit room adapter (LiveKitRoom); tests pass any
  /// LiveKitRoom fake or omit it (a no-op room is used then).
  final LiveKitRoom? adapter;

  /// Builds the video widget for a participant tile. Production maps the
  /// participant identity to a VideoTrackRenderer.
  final Widget Function(BuildContext, CallParticipant)? videoRendererBuilder;

  @override
  State<MeetingCallScreen> createState() => _MeetingCallScreenState();
}

class _MeetingCallScreenState extends State<MeetingCallScreen> {
  late final MeetingCallController _call;
  bool _leaving = false;

  @override
  void initState() {
    super.initState();
    _call = MeetingCallController(
      api: widget.api,
      meetingId: widget.meetingId,
      meetingTitle: widget.meetingTitle,
      localName: widget.localName,
      room: widget.adapter ?? _NullRoom(),
    );
    _call.addListener(_onChanged);
    _call.join(
        audioOnly: widget.audioOnly,
        directToken: widget.token,
        directUrl: widget.serverUrl);
  }

  void _onChanged() {
    if (!mounted) return;
    // Repaint on every controller change (status, participants, toggles) —
    // build() reads controller state directly, so it must be invalidated
    // here or the screen stays frozen on its first frame.
    setState(() {});
    if (_call.status == CallStatus.ended && !_leaving) {
      Navigator.of(context).maybePop();
    }
  }

  @override
  void dispose() {
    _call.removeListener(_onChanged);
    _call.dispose();
    super.dispose();
  }

  Future<void> _leave() async {
    if (_leaving) return;
    _leaving = true;
    await _call.leave();
    if (widget.onLeave != null) {
      try {
        await widget.onLeave!();
      } catch (_) {}
    }
    if (mounted) Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _leave();
      },
      child: Scaffold(
        backgroundColor: const Color(0xFF17131A),
        body: SafeArea(
          child: Column(
            children: [
              _header(),
              Expanded(child: _body()),
              _controls(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _header() {
    final status = _call.status;
    final badge = switch (status) {
      CallStatus.connected => _StatusBadge(
          text: '${_call.participants.length} in call', live: true),
      CallStatus.reconnecting => const _StatusBadge(text: 'Reconnecting…'),
      CallStatus.connecting || CallStatus.requestingToken =>
        const _StatusBadge(text: 'Connecting…'),
      _ => const SizedBox.shrink(),
    };
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
      child: Row(
        children: [
          Expanded(
            child: Text(
              widget.meetingTitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  color: Colors.white,
                  fontSize: 16,
                  fontWeight: FontWeight.w700),
            ),
          ),
          const SizedBox(width: 10),
          badge,
        ],
      ),
    );
  }

  Widget _body() {
    switch (_call.status) {
      case CallStatus.idle:
      case CallStatus.requestingToken:
      case CallStatus.connecting:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(color: Colors.white70),
              SizedBox(height: 14),
              Text('Joining meeting…',
                  style: TextStyle(color: Colors.white70, fontSize: 14)),
            ],
          ),
        );
      case CallStatus.failed:
        return Center(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.cloud_off_outlined,
                    size: 40, color: Colors.white38),
                const SizedBox(height: 12),
                Text(
                  _call.error ?? 'Could not join the meeting.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white, fontSize: 14.5),
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    OutlinedButton(
                      onPressed: () => _call.join(audioOnly: widget.audioOnly),
                      style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.white,
                          side: const BorderSide(color: Colors.white38)),
                      child: const Text('Retry'),
                    ),
                    const SizedBox(width: 10),
                    TextButton(
                      onPressed: _leave,
                      child: const Text('Go back',
                          style: TextStyle(color: Colors.white70)),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      case CallStatus.reconnecting:
      case CallStatus.connected:
      case CallStatus.ended:
        return _grid();
    }
  }

  Widget _grid() {
    final participants = _call.participants;
    if (participants.isEmpty) {
      return const Center(
          child: Text('Waiting for participants…',
              style: TextStyle(color: Colors.white54)));
    }
    final cross = participants.length <= 1
        ? 1
        : participants.length <= 4
            ? 2
            : participants.length <= 9
                ? 3
                : 4;
    return LayoutBuilder(builder: (context, c) {
      return GridView.builder(
        key: ValueKey('call-grid-$cross'),
        padding: const EdgeInsets.all(10),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: cross,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
          childAspectRatio: 0.92,
        ),
        itemCount: participants.length,
        itemBuilder: (context, i) => _tile(participants[i]),
      );
    });
  }

  Widget _tile(CallParticipant p) {
    final showVideo = p.hasVideoTrack;
    return Container(
      key: ValueKey('tile-${p.identity}'),
      decoration: BoxDecoration(
        color: const Color(0xFF231D27),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
            color: p.micEnabled
                ? Colors.transparent
                : const Color(0xFF5A4A2F)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        fit: StackFit.expand,
        children: [
          if (showVideo && widget.videoRendererBuilder != null)
            widget.videoRendererBuilder!(context, p)
          else
            _avatarTile(p),
          Positioned(
            left: 8,
            bottom: 8,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    p.micEnabled
                        ? Icons.mic_rounded
                        : Icons.mic_off_rounded,
                    size: 13,
                    color: p.micEnabled ? Colors.white70 : Colors.amber,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    p.isLocal ? 'You' : p.name,
                    style: const TextStyle(
                        color: Colors.white, fontSize: 12.5),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _avatarTile(CallParticipant p) {
    final initials = p.name.trim().split(RegExp(r'\s+')).take(2).map((w) => w.isEmpty ? '' : w[0]).join().toUpperCase();
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: const BoxDecoration(
              color: QolabTheme.wine,
              shape: BoxShape.circle,
            ),
            alignment: Alignment.center,
            child: Text(
              initials.isEmpty ? '?' : initials,
              style: const TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.w700),
            ),
          ),
          const SizedBox(height: 8),
          Text(p.isLocal ? 'You' : p.name,
              style: const TextStyle(color: Colors.white70, fontSize: 13)),
        ],
      ),
    );
  }

  Widget _controls() {
    final connected = _call.status == CallStatus.connected ||
        _call.status == CallStatus.reconnecting;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 14),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _ControlButton(
            key: const Key('btn-mic'),
            icon: _call.participants.any((p) => p.isLocal && p.micEnabled)
                ? Icons.mic_rounded
                : Icons.mic_off_rounded,
            active: _call.participants.any((p) => p.isLocal && p.micEnabled),
            onPressed: connected ? _call.toggleMic : null,
          ),
          const SizedBox(width: 14),
          _ControlButton(
            key: const Key('btn-cam'),
            icon: _call.participants.any((p) => p.isLocal && p.cameraEnabled)
                ? Icons.videocam_rounded
                : Icons.videocam_off_rounded,
            active: _call.participants.any((p) => p.isLocal && p.cameraEnabled),
            onPressed: connected ? _call.toggleCamera : null,
          ),
          const SizedBox(width: 14),
          _ControlButton(
            key: const Key('btn-flip'),
            icon: Icons.cameraswitch_rounded,
            onPressed: connected ? _call.switchCamera : null,
          ),
          const SizedBox(width: 14),
          _ControlButton(
            key: const Key('btn-leave'),
            icon: Icons.call_end_rounded,
            danger: true,
            onPressed: _leave,
          ),
        ],
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.text, this.live = false});

  final String text;
  final bool live;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: live ? QolabTheme.wine : Colors.white24,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: const TextStyle(
            color: Colors.white, fontSize: 11.5, fontWeight: FontWeight.w700),
      ),
    );
  }
}

class _ControlButton extends StatelessWidget {
  const _ControlButton({
    super.key,
    required this.icon,
    this.onPressed,
    this.active = false,
    this.danger = false,
  });

  final IconData icon;
  final VoidCallback? onPressed;
  final bool active;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: danger
          ? const Color(0xFFC0392B)
          : active
              ? Colors.white24
              : const Color(0xFF2E2733),
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(999),
        child: Padding(
          padding: const EdgeInsets.all(13),
          child: Icon(icon, size: 23, color: Colors.white),
        ),
      ),
    );
  }
}

/// No-op room for widget tests / missing adapter: connect fails cleanly.
class _NullRoom implements LiveKitRoom {
  final _ctrl = StreamController<LiveKitEvent>.broadcast();
  @override
  Stream<LiveKitEvent> get events => _ctrl.stream;
  @override
  Map<String, String> get remoteParticipants => const {};
  @override
  bool get isCameraEnabled => false;
  @override
  bool get isMicEnabled => false;
  @override
  Future<void> connect(String url, String token) async =>
      throw StateError('no livekit adapter');
  @override
  Future<void> setCameraEnabled(bool enabled) async {}
  @override
  Future<void> setMicEnabled(bool enabled) async {}
  @override
  Future<void> switchCamera() async {}
  @override
  Future<void> disconnect() async {}
}
