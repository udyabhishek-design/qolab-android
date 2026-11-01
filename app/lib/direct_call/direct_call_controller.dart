/// Direct Calling (Phase C1) — signaling state machine.
///
/// Subscribes to the user's private channel (user.{id}) via the existing
/// ChatSocketService and owns the call lifecycle:
///
///   outgoing: start() → Dialing → (call.accepted → Connecting → Active)
///             (call.declined/cancelled/missed → Ended)
///   incoming: call.incoming → Ringing → accept() → Active
///             decline() → Idle
///   recovery: recover() on mount/reconnect asks the backend for truth.
///
/// Media (LiveKit) is intentionally NOT in this controller — C1 delivers
/// signaling; the Active screen reuses the meetings call screen.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../api/api_errors.dart';
import '../api/qolab_api.dart';
import '../chat/chat_socket.dart';
import '../chat/reverb_client.dart';
import 'direct_call_models.dart';

enum DirectCallPhase { idle, dialing, ringing, connecting, active, ended }

class DirectCallState {
  const DirectCallState({
    this.phase = DirectCallPhase.idle,
    this.call,
    this.isOutgoing = false,
    this.token,
    this.url,
    this.error,
    this.endedReason,
  });

  final DirectCallPhase phase;
  final DirectCallData? call;
  final bool isOutgoing;

  /// LiveKit credentials for the ACTIVE call (accept or token response).
  final String? token;
  final String? url;
  final String? error;
  final String? endedReason;

  DirectCallState copyWith({
    DirectCallPhase? phase,
    DirectCallData? call,
    bool? isOutgoing,
    String? token,
    Object? clearToken = false,
    String? url,
    Object? clearUrl = false,
    String? error,
    Object? clearError = false,
    String? endedReason,
  }) =>
      DirectCallState(
        phase: phase ?? this.phase,
        call: call ?? this.call,
        isOutgoing: isOutgoing ?? this.isOutgoing,
        token: clearToken == true ? null : (token ?? this.token),
        url: clearUrl == true ? null : (url ?? this.url),
        error: clearError == true ? null : (error ?? this.error),
        endedReason: endedReason ?? this.endedReason,
      );
}

/// Broadcast event names on the wire (leading dot matches chat's usage).
const _kEventIncoming = '.call.incoming';
const _kEventAccepted = '.call.accepted';
const _kEventDeclined = '.call.declined';
const _kEventCancelled = '.call.cancelled';
const _kEventEnded = '.call.ended';
const _kEventMissed = '.call.missed';

class DirectCallController extends ChangeNotifier {
  DirectCallController({
    required this.api,
    required this.socket,
    required this.myUserId,
  });

  final QolabApi api;
  final ChatSocketService? socket;
  final int myUserId;

  DirectCallState _state = const DirectCallState();
  DirectCallState get state => _state;

  VoidCallback? _cancelSubscription;
  StreamSubscription<void>? _resyncSub;
  bool _disposed = false;
  Timer? _ringTimeout;

  /// Attach the user-channel listener (idempotent).
  void attach() {
    if (_disposed || socket == null || _cancelSubscription != null) return;
    _cancelSubscription = socket!.subscribe('user.$myUserId', _onEvent);
    _resyncSub = socket!.onResync.listen((_) {
      // Socket reconnected — ask the backend for authoritative state.
      if (!_disposed) recover();
    });
  }

  void _onEvent(BroadcastEvent e) {
    if (_disposed) return;
    switch (e.name) {
      case _kEventIncoming:
        _onIncoming(e.data);
      case _kEventAccepted:
        _onAccepted(e.data);
      case _kEventDeclined:
        _onTerminal(e.data, 'declined');
      case _kEventCancelled:
        _onTerminal(e.data, 'cancelled');
      case _kEventEnded:
        _onTerminal(e.data, 'ended');
      case _kEventMissed:
        _onTerminal(e.data, 'missed');
    }
  }

  // ── Events ───────────────────────────────────────────────────────

  /// Phase C3A — FCM push payload (backgrounded/killed/locked). The FCM
  /// data payload is intentionally minimal; we reconcile against the
  /// backend's authoritative state before ringing so stale pushes (call
  /// already answered/declined/expired) never show a dead ring screen.
  Future<void> handleIncomingPush({
    String? callId,
    String? callType,
    int? callerId,
    String? callerName,
  }) async {
    if (_disposed || callId == null) return;
    final current = _state;
    if (current.phase == DirectCallPhase.ringing ||
        current.phase == DirectCallPhase.dialing ||
        current.phase == DirectCallPhase.connecting ||
        current.phase == DirectCallPhase.active) {
      return; // already tracked (Reverb or an earlier push)
    }
    // Verify server-side that the call is still ringing for me.
    try {
      final json = await api.fetchActiveDirectCalls();
      final data = _findRingingForMe(json, callId);
      if (data == null) return; // stale — expired/answered elsewhere
      _onIncoming(data);
    } catch (_) {
      // Network failed: fall back to the push payload alone.
      _onIncoming({
        'call_id': callId,
        'call_type': callType ?? 'audio',
        'caller': {'id': callerId ?? 0, 'name': callerName ?? 'Qolab Member'},
        'status': 'ringing',
      });
    }
  }

  /// FCM call.update — dismiss ringing/notifications on any transition.
  void handleUpdatePush({String? callId, String? status}) {
    if (callId == null) return;
    final reason = switch (status) {
      'declined' => 'declined',
      'cancelled' => 'cancelled',
      'missed' => 'missed',
      'ended' => 'ended',
      'accepted' => 'accepted',
      _ => null,
    };
    if (reason == null) return;
    if (reason == 'accepted') {
      // Handled by _onAccepted via the call data; nothing extra needed for
      // the accepter (they drove it) — the caller gets call.accepted.
      return;
    }
    _onTerminal({
      'call_id': callId,
      'status': status,
      // fromJson requires call_type; the ringing state holds the real value.
      'call_type': _state.call?.callId == callId
          ? _state.call!.callType
          : 'audio',
    }, reason);
  }

  /// Native notification Accept button (app cold-started or live).
  Future<void> acceptFromNotification(String? callId) async {
    if (callId == null) return;
    // Ensure the state machine is ringing for THIS call, then accept via
    // the authenticated HTTP path (server validates).
    if (_state.call?.callId != callId) {
      await handleIncomingPush(callId: callId);
    }
    if (_state.phase == DirectCallPhase.ringing) {
      await accept();
    }
  }

  /// Native notification Decline button.
  Future<void> declineFromNotification(String? callId) async {
    if (callId == null) return;
    if (_state.call?.callId == callId &&
        _state.phase == DirectCallPhase.ringing) {
      await decline();
    } else {
      // Stale notification: the server may already have transitioned. POST
      // decline anyway — the 409 response is harmless and confirms state.
      try {
        await api.directCallAction(callId, 'decline');
      } catch (_) {}
      _onTerminal({
        'call_id': callId,
        'status': 'declined',
        'call_type': _state.call?.callType ?? 'audio',
      }, 'declined');
    }
  }

  /// Find the ringing call with [callId] addressed to me in the
  /// GET /chat/calls index response.
  Object? _findRingingForMe(String json, String callId) {
    try {
      final decoded = jsonDecode(json);
      final calls = decoded is Map ? decoded['calls'] ?? decoded['data'] : decoded;
      if (calls is! List) return null;
      for (final raw in calls) {
        if (raw is Map &&
            raw['call_id'] == callId &&
            raw['status'] == 'ringing' &&
            raw['recipient']?['id'] == myUserId) {
          return raw;
        }
      }
    } catch (_) {}
    return null;
  }

  void _onIncoming(Object? data) {
    final call = DirectCallData.fromJson(data);
    if (call == null || call.caller?.id == myUserId) return; // own outgoing
    final current = _state;
    // Duplicate events must not duplicate ringing.
    if (current.phase == DirectCallPhase.ringing ||
        current.phase == DirectCallPhase.dialing ||
        current.phase == DirectCallPhase.connecting ||
        current.phase == DirectCallPhase.active) {
      return;
    }
    _state = DirectCallState(
      phase: DirectCallPhase.ringing,
      call: call,
      isOutgoing: false,
    );
    _armRingTimeout(call.callId);
    _notify();
  }

  void _onAccepted(Object? data) {
    final call = DirectCallData.fromJson(data);
    if (call == null) return;
    final current = _state;
    if (current.phase != DirectCallPhase.dialing ||
        current.call?.callId != call.callId) {
      return; // accepted for another call / we're the accepter
    }
    _ringTimeout?.cancel();
    _state = current.copyWith(
        phase: DirectCallPhase.connecting, call: call, isOutgoing: true);
    _notify();
    // The caller fetches its own token for the accepted call.
    unawaited(_fetchTokenForAccepted(call.callId));
  }

  void _onTerminal(Object? data, String reason) {
    final call = DirectCallData.fromJson(data);
    final current = _state;
    if (call == null || current.call?.callId != call.callId) return;
    if (current.phase == DirectCallPhase.idle) return;
    _ringTimeout?.cancel();
    _state = current.copyWith(
        phase: DirectCallPhase.ended, call: call, endedReason: reason);
    _notify();
  }

  void _armRingTimeout(String callId) {
    _ringTimeout?.cancel();
    _ringTimeout = Timer(const Duration(seconds: 35), () {
      // Server expiry is authoritative (scheduler + opportunistic read);
      // this local timer is only a UI safety net — re-check state.
      if (_disposed) return;
      if (_state.phase == DirectCallPhase.ringing &&
          _state.call?.callId == callId) {
        recover();
      }
    });
  }

  // ── Actions ──────────────────────────────────────────────────────

  /// Start an outgoing call. Returns an error string, or null on success.
  Future<String?> start(int recipientId, String callType,
      {int? channelId}) async {
    if (_state.phase != DirectCallPhase.idle &&
        _state.phase != DirectCallPhase.ended) {
      return 'You already have a call in progress.';
    }
    try {
      final body = await api.startDirectCall(recipientId, callType,
          channelId: channelId);
      final decoded = _decode(body);
      final call = DirectCallData.fromJson(decoded?['call']);
      if (decoded?['success'] != true || call == null) {
        final err = decoded?['error'];
        return err is String ? err : 'Could not start the call.';
      }
      _state = DirectCallState(
        phase: DirectCallPhase.dialing,
        call: call,
        isOutgoing: true,
        token: decoded?['token'] is String ? decoded!['token'] as String : null,
        url: decoded?['url'] is String ? decoded!['url'] as String : null,
      );
      _armRingTimeout(call.callId);
      _notify();
      return null;
    } on ApiException catch (e) {
      return e.message;
    } catch (_) {
      return 'Could not reach the calling service. Please try again.';
    }
  }

  /// Recipient accepts; mints their token and goes Active.
  Future<String?> accept() async {
    final call = _state.call;
    if (call == null || _state.phase != DirectCallPhase.ringing) {
      return 'This call is no longer available.';
    }
    try {
      final body = await api.directCallAction(call.callId, 'accept');
      final decoded = _decode(body);
      final updated = DirectCallData.fromJson(decoded?['call']);
      if (decoded?['success'] != true || updated == null) {
        final err = decoded?['error'];
        return err is String ? err : 'Could not accept the call.';
      }
      _ringTimeout?.cancel();
      _state = _state.copyWith(
        phase: DirectCallPhase.active,
        call: updated,
        token: decoded?['token'] is String ? decoded!['token'] as String : null,
        url: decoded?['url'] is String ? decoded!['url'] as String : null,
      );
      _notify();
      return null;
    } on ApiException catch (e) {
      // 409 race — the call moved on. Re-sync from the server.
      if (e.kind == ApiErrorKind.conflict) {
        await recover();
        return 'The call was no longer ringing.';
      }
      return e.message;
    } catch (_) {
      return 'Could not accept the call. Please try again.';
    }
  }

  Future<void> decline() async {
    final call = _state.call;
    if (call == null) return;
    _ringTimeout?.cancel();
    _state = const DirectCallState();
    _notify();
    try {
      await api.directCallAction(call.callId, 'decline');
    } catch (_) {/* UI already reset; server state is authoritative on next recover */ }
  }

  Future<void> cancelOutgoing() async {
    final call = _state.call;
    if (call == null) return;
    _ringTimeout?.cancel();
    _state = const DirectCallState();
    _notify();
    try {
      await api.directCallAction(call.callId, 'cancel');
    } catch (_) {/* best-effort; server expiry covers the rest */ }
  }

  /// Caller-side: fetch the LiveKit token for a call that just got
  /// accepted (connecting → active).
  Future<void> _fetchTokenForAccepted(String callId) async {
    try {
      final body = await api.fetchDirectCallToken(callId);
      final token = DirectCallToken.parse(body);
      if (token == null || !token.isUsable) {
        _failActive('Could not get call credentials.');
        return;
      }
      if (_disposed ||
          _state.phase != DirectCallPhase.connecting ||
          _state.call?.callId != callId) {
        return; // state moved on
      }
      _state = _state.copyWith(
          phase: DirectCallPhase.active, token: token.token, url: token.url);
      _notify();
    } on ApiException catch (e) {
      _failActive(e.message);
    } catch (_) {
      _failActive('Could not get call credentials.');
    }
  }

  void _failActive(String message) {
    if (_disposed || _state.phase != DirectCallPhase.connecting) return;
    _state = _state.copyWith(
        phase: DirectCallPhase.ended,
        error: message,
        endedReason: 'failed');
    _notify();
  }

  /// End an active call (either participant).
  Future<void> hangUp() async {
    final call = _state.call;
    if (call == null) return;
    _state = const DirectCallState();
    _notify();
    try {
      await api.directCallAction(call.callId, 'end');
    } catch (_) {/* server expiry + partner's call.ended cover it */ }
  }

  /// Dismiss the ended/error banner back to idle.
  void dismiss() {
    if (_disposed) return;
    _state = const DirectCallState();
    _notify();
  }

  /// Ask the backend for the truth (mount + reconnect + ring timeout).
  Future<void> recover() async {
    if (_disposed) return;
    try {
      final body = await api.fetchActiveDirectCalls();
      final decoded = _decode(body);
      final calls = decoded?['calls'];
      if (decoded?['success'] != true || calls is! List) return;
      final current = _state;
      // Never clobber a live in-progress UI state with a snapshot.
      if (current.phase != DirectCallPhase.idle &&
          current.phase != DirectCallPhase.ended) {
        return;
      }
      for (final raw in calls) {
        final call = DirectCallData.fromJson(raw);
        if (call == null) continue;
        if (call.isRinging) {
          final outgoing = call.caller?.id == myUserId;
          _state = DirectCallState(
            phase: outgoing
                ? DirectCallPhase.dialing
                : DirectCallPhase.ringing,
            call: call,
            isOutgoing: outgoing,
          );
          _armRingTimeout(call.callId);
          _notify();
          return;
        }
        if (call.isActive) {
          final outgoing = call.caller?.id == myUserId;
          _state = DirectCallState(
            phase: DirectCallPhase.connecting,
            call: call,
            isOutgoing: outgoing,
          );
          _notify();
          unawaited(_fetchTokenForAccepted(call.callId));
          return;
        }
      }
    } catch (_) {/* offline — keep current state */ }
  }

  static Map? _decode(String body) {
    try {
      final v = jsonDecode(body);
      return v is Map ? v : null;
    } catch (_) {
      return null;
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _ringTimeout?.cancel();
    _cancelSubscription?.call();
    _resyncSub?.cancel();
    super.dispose();
  }
}
