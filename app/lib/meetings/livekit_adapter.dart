library;

/// LiveKitRoomAdapter — the production [LiveKitRoom] backed by
/// package:livekit_client. Maps LiveKit's room/participant/track events
/// onto the coarse [LiveKitEvent] stream the controller consumes, and owns
/// the real Room lifecycle (connect, tracks, engine).
///
/// Remote video tracks are forwarded so the UI can render real remote
/// tiles (the legacy Kotlin bug was the local tile filling the screen and
/// remote tiles never appearing — here remotes drive the grid).

import 'dart:async';

import 'package:livekit_client/livekit_client.dart' as lk;

import '../util/log.dart';
import 'call_controller.dart';

class LiveKitRoomAdapter implements LiveKitRoom {
  LiveKitRoomAdapter() {
    _wireRoom();
  }

  lk.Room? _room;
  lk.EventsListener<lk.RoomEvent>? _listener;
  lk.Room get room => _room!;
  final _ctrl = StreamController<LiveKitEvent>.broadcast();
  bool _disposedAdapter = false;

  /// A disposed LiveKit Room cannot be reused — every [connect] starts a
  /// fresh Room + listener (rejoin after leave works, no stale state).
  void _wireRoom() {
    final r = lk.Room();
    _room = r;
    _listener = r.createListener()
      ..on<lk.RoomConnectedEvent>((_) {
        qolabLog('livekit: room connected '
            'mic=$_enableMicOnJoin cam=$_enableCameraOnJoin');
        _emit(const ConnectionChanged(CallStatus.connected));
      })
      ..on<lk.RoomReconnectingEvent>(
          (_) => _emit(const ConnectionChanged(CallStatus.reconnecting)))
      ..on<lk.RoomReconnectedEvent>(
          (_) => _emit(const ConnectionChanged(CallStatus.connected)))
      ..on<lk.RoomDisconnectedEvent>((e) {
        qolabLog('livekit: room disconnected reason=${e.reason?.name}');
        _emit(const ConnectionChanged(CallStatus.ended));
      })
      ..on<lk.ParticipantConnectedEvent>((e) {
        qolabLog('livekit: participant joined ${e.participant.identity}');
        _emit(ParticipantJoined(
            e.participant.identity,
            e.participant.name.isNotEmpty
                ? e.participant.name
                : e.participant.identity));
      })
      ..on<lk.ParticipantDisconnectedEvent>((e) {
        qolabLog('livekit: participant left ${e.participant.identity}');
        _emit(ParticipantLeft(e.participant.identity));
      })
      // Every track publication/subscription/mute change resyncs the
      // affected participant — immune to per-track event-order races.
      ..on<lk.TrackSubscribedEvent>((e) {
        qolabLog('livekit: track subscribed '
            '${e.participant.identity} ${e.track.kind.name}');
        _emitTrack(e.participant.identity);
      })
      ..on<lk.TrackUnsubscribedEvent>((e) {
        qolabLog('livekit: track unsubscribed ${e.participant.identity}');
        _emitTrack(e.participant.identity);
      })
      ..on<lk.TrackMutedEvent>((e) => _emitTrack(e.participant.identity))
      ..on<lk.TrackUnmutedEvent>((e) => _emitTrack(e.participant.identity))
      ..on<lk.LocalTrackPublishedEvent>((e) {
        qolabLog('livekit: local track published '
            '${e.publication.kind.name}');
        _emitTrack(e.participant.identity);
      })
      ..on<lk.LocalTrackUnpublishedEvent>((e) {
        qolabLog('livekit: local track unpublished '
            '${e.publication.kind.name}');
        _emitTrack(e.participant.identity);
      });
  }

  @override
  Future<void> connect(String url, String token) async {
    if (_room == null || _room!.isDisposed) _wireRoom();
    await room.connect(
      url,
      token,
      // v1.5.64: livekit_client does NOT auto-publish mic/camera on a
      // plain connect(). Without fastConnectOptions both participants
      // joined with zero tracks published — call screen showed, media
      // was dead in both directions. fastConnect publishes the local
      // mic/camera during the join handshake itself, before the remote
      // sees us arrive.
      fastConnectOptions: lk.FastConnectOptions(
        microphone: lk.TrackOption(enabled: _enableMicOnJoin),
        camera: lk.TrackOption(enabled: _enableCameraOnJoin),
      ),
    );
  }

  /// Whether the next connect() should publish the mic/camera (set from
  /// the call's audioOnly/videoCall flags before joining).
  bool _enableMicOnJoin = true;
  bool _enableCameraOnJoin = false;

  /// Configure which local tracks the NEXT connect publishes via
  /// fastConnect. Must be set before connect() is awaited.
  void setInitialTracks({required bool publishMic, required bool publishCamera}) {
    _enableMicOnJoin = publishMic;
    _enableCameraOnJoin = publishCamera;
  }

  /// Exposed for the video widgets: a remote's subscribed video track.
  lk.RemoteVideoTrack? videoTrackFor(String identity) {
    final r = _room;
    if (r == null) return null;
    final p = r.remoteParticipants[identity];
    if (p == null) return null;
    for (final pub in p.videoTrackPublications) {
      final t = pub.track;
      if (t is lk.RemoteVideoTrack && !t.muted) return t;
    }
    return null;
  }

  /// Exposed for the local tile: the local camera track when enabled.
  lk.LocalVideoTrack? get localVideoTrack {
    final local = _room?.localParticipant;
    if (local == null) return null;
    for (final pub in local.videoTrackPublications) {
      final t = pub.track;
      if (t is lk.LocalVideoTrack) return t;
    }
    return null;
  }

  @override
  Stream<LiveKitEvent> get events => _ctrl.stream;

  @override
  Map<String, String> get remoteParticipants =>
      Map.fromEntries(_room?.remoteParticipants.entries.map(
              (e) => MapEntry(
                  e.key,
                  e.value.name.isNotEmpty
                      ? e.value.name
                      : e.key)) ??
          const Iterable<MapEntry<String, String>>.empty());

  @override
  bool get isCameraEnabled =>
      _room?.localParticipant?.isCameraEnabled() ?? false;

  @override
  bool get isMicEnabled =>
      _room?.localParticipant?.isMicrophoneEnabled() ?? false;

  @override
  Future<void> setCameraEnabled(bool enabled) async {
    await _room?.localParticipant?.setCameraEnabled(enabled);
  }

  @override
  Future<void> setMicEnabled(bool enabled) async {
    await _room?.localParticipant?.setMicrophoneEnabled(enabled);
  }

  @override
  Future<void> switchCamera() async {
    // Cycle between the device's video inputs (front/rear on phones).
    final inputs = await lk.Hardware.instance.videoInputs();
    if (inputs.length < 2) {
      throw StateError('no second camera to switch to');
    }
    final current = lk.Hardware.instance.selectedVideoInput;
    final i = inputs.indexWhere((d) => d.deviceId == current?.deviceId);
    final next = inputs[(i + 1) % inputs.length];
    await room.setVideoInputDevice(next);
  }

  @override
  Future<void> disconnect() async {
    final r = _room;
    if (r == null) return;
    await r.disconnect();
    await r.dispose();
    _listener?.dispose();
    _listener = null;
    _room = null;
  }

  void dispose() {
    if (_disposedAdapter) return;
    _disposedAdapter = true;
    _listener?.dispose();
    _ctrl.close();
  }

  void _emit(LiveKitEvent e) {
    if (!_disposedAdapter && !_ctrl.isClosed) _ctrl.add(e);
  }

  void _emitTrack(String identity) {
    final local = _room?.localParticipant;
    final lk.Participant? p = identity == local?.identity
        ? local
        : _room?.remoteParticipants[identity];
    if (p == null) return;
    final isLocal = identical(p, local);
    bool hasVideo;
    if (isLocal) {
      hasVideo = p.isCameraEnabled();
    } else {
      hasVideo = p.videoTrackPublications.any((pub) =>
          pub.track is lk.RemoteVideoTrack && pub.track?.muted != true);
    }
    _emit(TrackChanged(
      identity,
      cameraEnabled: p.isCameraEnabled(),
      micEnabled: p.isMicrophoneEnabled(),
      hasVideoTrack: hasVideo,
    ));
  }
}
