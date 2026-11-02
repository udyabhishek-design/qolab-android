library;

/// MeetingCallController — the bridge between the Qolab meeting (Laravel
/// join-token) and a LiveKit Room, exposed as a small observable state the
/// in-call screen renders. Pure Dart ChangeNotifier so widget tests can
/// drive it with a fake [LiveKitRoom] (no network, no native plugins).
///
/// Design rules (from the Kotlin-implementation postmortem the customer
/// wants to avoid):
/// - Remote participants are the FIRST-CLASS grid; the local tile never
///   occupies the screen alone. Participant list = remote (by identity)
///   then local — all rendered as equal tiles.
/// - Track events rebuild a value-snapshot (unmodifiable list), so the UI
///   can never render a stale subscription or a ghost participant.
/// - [leave] is idempotent and releases EVERYTHING (room listeners,
///   camera/mic tracks, engine). Rejoining creates a fresh room.

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/api_errors.dart';
import '../api/qolab_api.dart';
import 'meeting_models.dart';

// ── LiveKit abstraction (thin, testable) ─────────────────────────────

/// Observable per-participant snapshot the grid renders.
@immutable
class CallParticipant {
  const CallParticipant({
    required this.identity,
    required this.name,
    this.isLocal = false,
    this.cameraEnabled = false,
    this.micEnabled = false,
    this.hasVideoTrack = false,
  });

  final String identity;
  final String name;
  final bool isLocal;
  final bool cameraEnabled;
  final bool micEnabled;

  /// A remote has an actively subscribed, enabled video track (render a
  /// VideoTrackRenderer); locals render their published camera track when
  /// enabled. Audio-only participants render a name placeholder tile.
  final bool hasVideoTrack;

  CallParticipant copyWith({
    bool? cameraEnabled,
    bool? micEnabled,
    bool? hasVideoTrack,
  }) =>
      CallParticipant(
        identity: identity,
        name: name,
        isLocal: isLocal,
        cameraEnabled: cameraEnabled ?? this.cameraEnabled,
        micEnabled: micEnabled ?? this.micEnabled,
        hasVideoTrack: hasVideoTrack ?? this.hasVideoTrack,
      );
}

/// Call connection lifecycle.
enum CallStatus {
  idle,
  requestingToken,
  connecting,
  connected,
  reconnecting,
  failed,
  ended,
}

/// Minimal LiveKit room surface the controller needs. The real
/// implementation adapts package:livekit_client; tests supply a fake.
abstract class LiveKitRoom {
  /// Stream of coarse room events the UI reacts to.
  Stream<LiveKitEvent> get events;

  /// Current snapshot of remote participants (identity → display name).
  Map<String, String> get remoteParticipants;

  bool get isCameraEnabled;
  bool get isMicEnabled;

  /// Connect to [url] with [token]. Throws on failure.
  Future<void> connect(String url, String token);

  Future<void> setCameraEnabled(bool enabled);
  Future<void> setMicEnabled(bool enabled);

  /// Cycle front/rear camera. No-op/throws when unsupported.
  Future<void> switchCamera();

  /// Full teardown — listeners, tracks, engine. Safe to call twice.
  Future<void> disconnect();
}

/// Coarse events mapped from LiveKit's room/participant/track callbacks.
sealed class LiveKitEvent {
  const LiveKitEvent();
}

/// A participant joined.
class ParticipantJoined extends LiveKitEvent {
  const ParticipantJoined(this.identity, this.name);
  final String identity;
  final String name;
}

/// A participant left — all their tracks and tiles must go.
class ParticipantLeft extends LiveKitEvent {
  const ParticipantLeft(this.identity);
  final String identity;
}

/// A participant's audio/video track was muted/unmuted, subscribed or
/// unsubscribed (covers camera on/off, mic mute, track cleanup).
class TrackChanged extends LiveKitEvent {
  const TrackChanged(this.identity, {required this.cameraEnabled, required this.micEnabled, required this.hasVideoTrack});
  final String identity;
  final bool cameraEnabled;
  final bool micEnabled;
  final bool hasVideoTrack;
}

/// Signal connection state changes (connected / reconnecting / failed).
class ConnectionChanged extends LiveKitEvent {
  const ConnectionChanged(this.status);
  final CallStatus status;
}

// ── Controller ───────────────────────────────────────────────────────

/// Issues the join-token, connects the LiveKit room, and exposes UI state:
/// status, participants (remote-first), local camera/mic, errors.
class MeetingCallController extends ChangeNotifier {
  MeetingCallController({
    required this.api,
    required this.meetingId,
    required this.meetingTitle,
    required this.localName,
    required this.room,
  });

  final QolabApi api;
  final int meetingId;
  final String meetingTitle;
  final String localName;
  final LiveKitRoom room;

  CallStatus status = CallStatus.idle;
  String? error;

  /// Remotes FIRST, local last — the grid renders this order directly so
  /// the local tile can never push remote participants off-screen.
  List<CallParticipant> get participants => List.unmodifiable(_participants);
  final List<CallParticipant> _participants = [];

  bool _disposed = false;
  bool _leaving = false;
  StreamSubscription<LiveKitEvent>? _events;

  /// Join the meeting: request join-token, then connect the LiveKit room.
  /// [audioOnly] skips the camera entirely (permission-denied path).
  /// [directToken]/[directUrl] (Direct Calling C1): skip the join-token
  /// fetch — the caller already minted credentials via /chat/calls.
  Future<void> join(
      {bool audioOnly = false, String? directToken, String? directUrl}) async {
    if (status == CallStatus.requestingToken ||
        status == CallStatus.connecting ||
        status == CallStatus.connected) {
      return; // already joining/joined — never double-connect.
    }
    _setState(CallStatus.requestingToken, error: null);
    _participants
      ..clear()
      ..add(CallParticipant(
        identity: 'local',
        name: localName,
        isLocal: true,
        micEnabled: !audioOnly,
      ));

    final String? connectUrl;
    final String? connectToken;
    if (directToken != null && directToken.isNotEmpty) {
      // Direct call: credentials provided up front.
      connectToken = directToken;
      connectUrl = directUrl;
    } else {
      MeetingJoinToken? token;
      try {
        final body = await api.fetchMeetingJoinToken(meetingId);
        token = MeetingJoinToken.parse(body);
      } on ApiException catch (e) {
        _setState(CallStatus.failed, error: e.message);
        return;
      } catch (_) {
        _setState(CallStatus.failed,
            error: 'Could not reach the meeting service. Please try again.');
        return;
      }
      if (token == null || !token.isUsable) {
        _setState(CallStatus.failed,
            error: 'The meeting service returned an unexpected response.');
        return;
      }
      connectToken = token.token;
      connectUrl = token.url;
    }

    if (connectUrl == null || connectUrl.isEmpty) {
      _setState(CallStatus.failed,
          error: 'The call server address is missing. Please try again.');
      return;
    }

    _setState(CallStatus.connecting);
    _events ??= room.events.listen(_onEvent, onError: (_) {
      // Room-level stream error → surface as connection failure, not crash.
      if (status == CallStatus.connecting || status == CallStatus.reconnecting) {
        _setState(CallStatus.failed,
            error: 'Connection lost. Please rejoin the meeting.');
      }
    });
    // v1.5.64: the join future is RETAINED. Disposing a livekit Room while
    // its connect() future is still in flight leaves the engine's
    // reconnect timers alive after teardown (leaked-Timer assertion in
    // tests; ghost reconnects on device). leave() / dispose() await
    // _settleJoin() first.
    _joinFuture = room.connect(connectUrl, connectToken!);
    try {
      await _joinFuture;
    } catch (_) {
      _setState(CallStatus.failed,
          error: 'Could not connect to the meeting. Please try again.');
      return;
    }
    if (audioOnly) {
      try {
        await room.setCameraEnabled(false);
      } catch (_) {/* camera never started — fine */}
    }
    _syncFromRoom();
    _setState(CallStatus.connected);
  }

  void _onEvent(LiveKitEvent e) {
    if (_disposed || status == CallStatus.ended) return;
    switch (e) {
      case ParticipantJoined(:final identity, :final name):
        _upsert(CallParticipant(
            identity: identity, name: name, micEnabled: true));
      case ParticipantLeft(:final identity):
        _participants.removeWhere((p) => p.identity == identity && !p.isLocal);
      case TrackChanged(
          :final identity,
          :final cameraEnabled,
          :final micEnabled,
          :final hasVideoTrack):
        final i = _participants.indexWhere((p) => p.identity == identity);
        if (i >= 0) {
          _participants[i] = _participants[i].copyWith(
              cameraEnabled: cameraEnabled,
              micEnabled: micEnabled,
              hasVideoTrack: hasVideoTrack);
        } else {
          // Track event before join event (race) — create the participant.
          _upsert(CallParticipant(
              identity: identity,
              name: identity,
              cameraEnabled: cameraEnabled,
              micEnabled: micEnabled,
              hasVideoTrack: hasVideoTrack));
        }
      case ConnectionChanged(:final status):
        if (status == CallStatus.failed) {
          _setState(CallStatus.failed,
              error: 'Connection lost. Please rejoin the meeting.');
          return;
        }
        if (this.status != CallStatus.ended) {
          this.status = status;
        }
    }
    _syncFromRoom();
    // Remote-first invariant: remotes before local in grid order.
    _participants.sort((a, b) => a.isLocal ? 1 : (b.isLocal ? -1 : 0));
    _notify();
  }

  /// Rebuild the snapshot from the room's authoritative maps — guards
  /// against missed events (e.g. reconnect resync).
  void _syncFromRoom() {
    final remotes = room.remoteParticipants;
    // Remove stale remotes no longer in the room.
    _participants.removeWhere(
        (p) => !p.isLocal && !remotes.containsKey(p.identity));
    // Upsert every current remote.
    remotes.forEach((identity, name) {
      final i = _participants.indexWhere((p) => p.identity == identity);
      if (i < 0) {
        _participants.add(CallParticipant(
            identity: identity, name: name, micEnabled: true));
      } else if (_participants[i].name != name) {
        final old = _participants[i];
        _participants[i] = CallParticipant(
          identity: identity,
          name: name,
          cameraEnabled: old.cameraEnabled,
          micEnabled: old.micEnabled,
          hasVideoTrack: old.hasVideoTrack,
        );
      }
    });
    // Local flags mirror the room's device state.
    final li = _participants.indexWhere((p) => p.isLocal);
    if (li >= 0) {
      _participants[li] = _participants[li].copyWith(
          cameraEnabled: room.isCameraEnabled, micEnabled: room.isMicEnabled);
    }
  }

  void _upsert(CallParticipant p) {
    final i = _participants.indexWhere((x) => x.identity == p.identity);
    if (i >= 0) {
      _participants[i] = p;
    } else if (p.isLocal) {
      _participants.add(p); // local is always LAST
    } else {
      // Insert remotes before the local tile → remote-first grid order.
      final li = _participants.indexWhere((x) => x.isLocal);
      if (li < 0) {
        _participants.add(p);
      } else {
        _participants.insert(li, p);
      }
    }
  }

  // ── Local controls ─────────────────────────────────────────────

  Future<void> toggleMic() async {
    final target = !room.isMicEnabled;
    try {
      await room.setMicEnabled(target);
    } catch (_) {
      _notify();
      return;
    }
    _syncFromRoom();
    _notify();
  }

  Future<void> toggleCamera() async {
    final target = !room.isCameraEnabled;
    try {
      await room.setCameraEnabled(target);
    } catch (_) {
      _notify();
      return;
    }
    _syncFromRoom();
    _notify();
  }

  Future<void> switchCamera() async {
    try {
      await room.switchCamera();
    } catch (_) {/* unsupported on this device — no state change */}
    _notify();
  }

  Future<void>? _joinFuture;

  /// v1.5.64: await any in-flight connect() before teardown — disposing a
  /// Room mid-connect leaks the engine's reconnect timers.
  Future<void> _settleJoin() async {
    final f = _joinFuture;
    if (f == null) return;
    try {
      await f;
    } catch (_) {}
  }

  /// Leave: idempotent, full teardown. Order matters — stop tracks/room
  /// first so no ghost participant outlives the local one, then mark ended.
  Future<void> leave() async {
    if (_leaving || status == CallStatus.ended) return;
    _leaving = true;
    await _settleJoin();
    try {
      await room.disconnect();
    } catch (_) {
      // Even on failure the engine must be torn down; disconnect() in the
      // real adapter is designed to be safe to call twice.
      try {
        await room.disconnect();
      } catch (_) {}
    }
    _participants.clear();
    _setState(CallStatus.ended);
    _leaving = false;
  }

  void _setState(CallStatus s, {String? error}) {
    status = s;
    this.error = s == CallStatus.failed ? error : null;
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    if (!_disposed && status != CallStatus.ended) {
      // Controller dropped without leave() (navigation away) — still clean
      // up the room so no session lingers.
      unawaited(leave());
    }
    _disposed = true;
    _events?.cancel();
    super.dispose();
  }
}
