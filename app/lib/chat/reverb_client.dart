import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

/// Minimal Laravel Reverb (Pusher protocol 7) client over dart:io WebSocket.
///
/// Handles: connection_established (socket_id capture), subscribe (with
/// auth for private- channels), subscription_succeeded/failed, server ping →
/// pong, bounded exponential reconnect with jitter, and clean disposal.
///
/// Security: the Reverb APP KEY is public (it ships in the web client
/// bundle); authorizing private channels requires the Laravel session which
/// is supplied by [BroadcastAuth] — nothing secret lives in this class.
class ReverbClient {
  ReverbClient({
    required this.wsUrl,
    required this.appKey,
    required this.auth,
    WebSocketFactory? socketFactory,
    Random? random,
    this.reconnectBase = const Duration(seconds: 1),
    this.reconnectMax = const Duration(seconds: 30),
  })  : _socketFactory = socketFactory ?? WebSocket.connect,
        _random = random ?? Random();

  /// e.g. wss://host/app/KEY?protocol=7&client=flutter
  final String wsUrl;
  final String appKey;
  final BroadcastAuth auth;
  final WebSocketFactory _socketFactory;
  final Random _random;

  final Duration reconnectBase;
  final Duration reconnectMax;

  /// The active socket as a WebSocket (dart:io WebSocket in production; a
  /// compatible double under test — accessed dynamically).
  Object? _socket;
  String? socketId;
  ReverbConnectionState state = ReverbConnectionState.disconnected;

  final _events = StreamController<BroadcastEvent>.broadcast();
  final _stateChanges = StreamController<ReverbConnectionState>.broadcast();
  final _subscribed = <String>{};

  /// Channels with a confirmed subscription on the current socket.
  /// Read-only view for callers (e.g. integration checks).
  Set<String> get subscribedChannels => Set.unmodifiable(_subscribed);
  /// Channels whose authorization failed — never retried automatically.
  final Set<String> authFailed = {};

  /// Subscriptions to restore after a reconnect.
  final List<String> _desiredSubscriptions = [];

  Timer? _reconnectTimer;
  int _attempt = 0;
  bool _disposed = false;
  DateTime? _connectedAt;

  /// Fired once per successful reconnect so callers can run REST catch-up.
  final _resubscribed = StreamController<void>.broadcast();

  Stream<BroadcastEvent> get events => _events.stream;
  Stream<ReverbConnectionState> get stateChanges => _stateChanges.stream;
  Stream<void> get resubscribed => _resubscribed.stream;
  bool get isConnected => state == ReverbConnectionState.connected;
  bool get hasEverConnected => _connectedAt != null;

  void _setState(ReverbConnectionState s) {
    state = s;
    if (!_disposed) _stateChanges.add(s);
  }

  // ── Connection ──────────────────────────────────────────────────────

  Future<void> connect() async {
    if (_disposed) return;
    if (isConnected || state == ReverbConnectionState.connecting) return;
    _setState(ReverbConnectionState.connecting);
    try {
      final socket = await _socketFactory(
        '$wsUrl/app/$appKey?protocol=7&client=flutter',
      );
      // Duck-typed WebSocket surface (listen/add/close): the production
      // factory returns dart:io WebSocket; tests return a compatible
      // in-memory double. A dynamic dispatch keeps both working without
      // forcing test doubles to implement every WebSocket member.
      final ws = socket as dynamic;
      if (_disposed) {
        await ws.close();
        return;
      }
      _socket = socket;
      ws.listen(
        _onMessage,
        onDone: _onDisconnected,
        onError: (_) => _onDisconnected(),
        cancelOnError: true,
      );
    } catch (_) {
      _onDisconnected();
    }
  }

  void disconnect() {
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _attempt = 0;
    _clearAuthRetryState();
    (_socket as dynamic)?.close();
    _socket = null;
    socketId = null;
    _subscribed.clear();
    _setState(ReverbConnectionState.disconnected);
  }

  void dispose() {
    _disposed = true;
    disconnect();
    _events.close();
    _stateChanges.close();
    _resubscribed.close();
  }

  void _onDisconnected() {
    if (_disposed) return;
    _socket = null;
    socketId = null;
    _subscribed.clear();
    _clearAuthRetryState();
    _setState(ReverbConnectionState.disconnected);
    _scheduleReconnect();
  }

  /// Fresh retry budget AND fresh blacklist on every new connection: a
  /// channel that exhausted retries on a dead socket is retried once the
  /// socket is re-established (permanent-403 channels are already removed
  /// from desired subscriptions and are not re-subscribed).
  void _clearAuthRetryState() {
    for (final t in _authRetryTimers.values) {
      t.cancel();
    }
    _authRetryTimers.clear();
    _authRetries.clear();
    authFailed.clear();
  }

  void _scheduleReconnect() {
    if (_reconnectTimer != null || _desiredSubscriptions.isEmpty) return;
    if (_attempt > 0) {
      // Exponential backoff with ±25% jitter, capped.
      final exp = min(reconnectBase.inMilliseconds * (1 << min(_attempt, 6)),
          reconnectMax.inMilliseconds);
      final jitter = (exp * 0.25 * (_random.nextDouble() * 2 - 1)).round();
      final delay = Duration(milliseconds: (exp + jitter).clamp(
          reconnectBase.inMilliseconds, reconnectMax.inMilliseconds));
      _reconnectTimer = Timer(delay, () {
        _reconnectTimer = null;
        connect();
      });
    } else {
      _attempt = 1;
      _scheduleReconnect();
    }
  }

  // ── Messaging ───────────────────────────────────────────────────────

  void _send(Map<String, Object?> data) {
    final s = _socket;
    if (s == null) return;
    try {
      (s as dynamic).add(jsonEncode(data));
    } on StateError {
      // Socket closed between the null check and the write.
    }
  }

  /// Reverb/Pusher serialize the payload as a JSON string; decode it to a
  /// Map when possible, otherwise return an empty (immutable) map.
  Map<String, Object?> _normalizeData(Object? data) {
    if (data is Map) return data.cast<String, Object?>();
    if (data is String && data.isNotEmpty) {
      try {
        final decoded = jsonDecode(data);
        if (decoded is Map) return decoded.cast<String, Object?>();
      } catch (_) {
        // Not JSON — fall through to the empty map.
      }
    }
    return const {};
  }

  void _onMessage(Object? raw) {
    if (raw is! String) return;
    Map<String, Object?> msg;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return;
      msg = decoded.cast<String, Object?>();
    } catch (_) {
      return;
    }
    final event = msg['event'];
    final channel = msg['channel'];
    // Reverb/Pusher serialize the payload as a JSON STRING (not a nested
    // object): {"event":"...","data":"{\"socket_id\":…}"}. Normalize it
    // once here so every handler below can treat data as a Map.
    final data = _normalizeData(msg['data']);
    switch (event) {
      case 'pusher:connection_established':
        final d = data;
        socketId = d['socket_id'] is String ? d['socket_id'] as String : null;
        _connectedAt = DateTime.now();
        final wasReconnect = _attempt > 0;
        _attempt = 0;
        _setState(ReverbConnectionState.connected);
        // Restore desired subscriptions (auth + subscribe) on the new socket.
        _restoreSubscriptions();
        if (wasReconnect) _resubscribed.add(null);
        break;
      case 'pusher:ping':
        _send({'event': 'pusher:pong'});
        break;
      case 'pusher:pong':
        break;
      case 'pusher_internal:subscription_succeeded':
        // Server confirmed the subscribe: mark it live (the poll-based
        // integration check and tests read subscribedChannels).
        if (channel is String) _subscribed.add(channel);
        break;
      case 'pusher_internal:member_added':
      case 'pusher_internal:member_removed':
        break;
      default:
        if (event is String && channel is String) {
          _subscribed.add(channel);
          _events.add(BroadcastEvent(
            channel: channel,
            name: event,
            data: data,
          ));
        }
    }
  }

  void _restoreSubscriptions() {
    for (final channel in List.of(_desiredSubscriptions)) {
      _subscribeOnSocket(channel);
    }
  }

  /// Track what the app WANTS subscribed (survives reconnects).
  void trackSubscription(String channel) {
    _desiredSubscriptions.add(channel);
    authFailed.remove(channel);
    if (isConnected) _subscribeOnSocket(channel);
  }

  void untrackSubscription(String channel) {
    _desiredSubscriptions.remove(channel);
    authFailed.remove(channel);
    if (isConnected && _subscribed.contains(channel)) {
      _send({'event': 'pusher:unsubscribe', 'data': {'channel': channel}});
    }
    _subscribed.remove(channel);
  }

  Future<void> _subscribeOnSocket(String channel) async {
    if (_disposed || socketId == null) return;
    final Map<String, Object?> data = {'channel': channel};
    if (channel.startsWith('private-')) {
      final sid = socketId;
      if (sid == null) return;
      try {
        final authResult = await auth.authorize(channel: channel, socketId: sid);
        data['auth'] = authResult;
      } on BroadcastAuthException catch (e) {
        if (_disposed) return;
        // Permanent authorization failures (403-style) never retry; TRANSIENT
        // failures (419 CSRF rotation, 5xx, network) retry a bounded number
        // of times on THIS connection, and always retry once on the next
        // reconnect — a single hiccup must not blacklist a channel forever.
        final permanent = e.status != null && e.status! < 500 && e.status != 419;
        if (permanent) {
          authFailed.add(channel);
          _desiredSubscriptions.remove(channel);
          _stateChanges.add(state); // notify listeners to re-read authFailed
          return;
        }
        final attempts = _authRetries.update(channel, (v) => v + 1,
            ifAbsent: () => 1);
        if (attempts > maxAuthRetries) {
          authFailed.add(channel);
          _stateChanges.add(state);
          return; // stays in desired: retried on next reconnect
        }
        final delay = Duration(seconds: attempts); // 1s, 2s, 3s…
        _authRetryTimers[channel]?.cancel();
        _authRetryTimers[channel] = Timer(delay, () {
          _authRetryTimers.remove(channel);
          if (!_disposed && _desiredSubscriptions.contains(channel)) {
            _subscribeOnSocket(channel);
          }
        });
        return;
      }
      _authRetries.remove(channel);
    }
    _send({'event': 'pusher:subscribe', 'data': data});
  }

  /// Bounded per-connection retries for TRANSIENT auth failures.
  static const maxAuthRetries = 3;
  final Map<String, int> _authRetries = {};
  final Map<String, Timer> _authRetryTimers = {};
}

enum ReverbConnectionState { disconnected, connecting, connected }

class BroadcastEvent {
  const BroadcastEvent({
    required this.channel,
    required this.name,
    required this.data,
  });

  final String channel;
  final String name;
  final Map<String, Object?> data;
}

typedef WebSocketFactory = Future<Object> Function(String url);

/// Authorizes a private-channel subscription against POST /broadcasting/auth.
abstract class BroadcastAuth {
  /// Returns the `auth` string ("key:signature") on success. Throws
  /// [BroadcastAuthException] on 401/403/419/network failure.
  Future<String> authorize({
    required String channel,
    required String socketId,
  });
}

class BroadcastAuthException implements Exception {
  const BroadcastAuthException(this.status, this.reason);

  final int? status; // null = network failure
  final String reason;

  @override
  String toString() => 'BroadcastAuthException($status: $reason)';
}
