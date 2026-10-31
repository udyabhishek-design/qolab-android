library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:livekit_client/livekit_client.dart' as lk;
import 'package:flutter_webrtc/flutter_webrtc.dart' as rtc;
import 'package:permission_handler/permission_handler.dart';

import '../api/qolab_api.dart';
import 'call_controller.dart';
import 'call_screen.dart';
import 'livekit_adapter.dart';

/// Joins a Qolab meeting with LiveKit audio (+ video when permitted):
/// 1. requests mic (+ camera) runtime permission — camera denial joins
///    audio-only (the call screen shows mic/camera controls either way),
/// 2. opens [MeetingCallScreen] with the production [LiveKitRoomAdapter],
/// 3. disposes the adapter when the screen pops — no session lingers.
///
/// The join token is fetched inside the call controller and never logged.
Future<bool> joinMeetingCall(
  BuildContext context, {
  required QolabApi api,
  required int meetingId,
  required String meetingTitle,
  required String localName,
}) async {
  bool cameraGranted = false;
  try {
    final mic = await Permission.microphone.request();
    if (mic.isGranted || mic.isLimited) {
      final cam = await Permission.camera.request();
      cameraGranted = cam.isGranted || cam.isLimited;
    }
  } catch (_) {
    // permission_handler unavailable — join anyway; the call screen will
    // surface any track failure as a clean error state.
  }

  final adapter = LiveKitRoomAdapter();
  if (!context.mounted) {
    adapter.dispose();
    return false;
  }
  await Navigator.of(context).push(MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => MeetingCallScreen(
      api: api,
      meetingId: meetingId,
      meetingTitle: meetingTitle,
      localName: localName,
      audioOnly: !cameraGranted,
      adapter: adapter,
      videoRendererBuilder: (context, p) =>
          _LiveVideoTile(adapter: adapter, participant: p),
    ),
  ));
  adapter.dispose();
  return true;
}

/// Binds a participant tile to their LiveKit video track. Remotes render
/// their subscribed RemoteVideoTrack; the local tile renders the published
/// LocalVideoTrack (mirrored). No track → dark tile (the avatar fallback
/// renders underneath via the caller's Stack).
class _LiveVideoTile extends StatefulWidget {
  const _LiveVideoTile({required this.adapter, required this.participant});

  final LiveKitRoomAdapter adapter;
  final CallParticipant participant;

  @override
  State<_LiveVideoTile> createState() => _LiveVideoTileState();
}

class _LiveVideoTileState extends State<_LiveVideoTile> {
  lk.VideoTrack? _track;
  StreamSubscription<LiveKitEvent>? _sub;

  void _resync() {
    final p = widget.participant;
    final lk.VideoTrack? next =
        p.isLocal ? widget.adapter.localVideoTrack : widget.adapter.videoTrackFor(p.identity);
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
