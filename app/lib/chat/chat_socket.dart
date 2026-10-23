import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/qolab_api.dart';
import 'broadcast_auth.dart';
import 'reverb_client.dart';

/// Owns the Reverb connection lifecycle for the Chat feature.
///
/// One socket per authenticated session. Connect after login, dispose on
/// logout/session expiry. Subscriptions are ref-counted so two listeners on
/// the same channel share one subscription; the last unsubscribe tears it
/// down. A generation token invalidates events from a previous session.
class ChatSocketService {
  ChatSocketService({
    required QolabApi? api,
    required this.appKey,
    this.wsOrigin,
    ReverbClient? client,
  })  : api = api, // ignore: prefer_initializing_formals — nullable for tests
        _client = client ??
            ReverbClient(
              wsUrl: wsOrigin ?? '',
              appKey: appKey,
              auth: SessionBroadcastAuth(api!),
            );

  /// Nullable only when a fully-injected [client] is provided (tests).
  /// Production always passes the authenticated API client.
  // ignore: unused_field — retained for debugging/inspection
  final QolabApi? api;
  final String appKey;

  /// Override for the WSS origin (defaults to api.origin). The live dev
  /// endpoint is same-origin: wss://host/app/KEY.
  final String? wsOrigin;
  final ReverbClient _client;

  bool _disposed = false;
  final Map<String, int> _refCounts = {};
  StreamSubscription<BroadcastEvent>? _eventSub;
  StreamSubscription<void>? _resyncSub;

  final Map<String, List<void Function(BroadcastEvent)>> _listeners = {};

  /// Called when the socket reconnects after a drop — triggers REST catch-up.
  final _onResync = StreamController<void>.broadcast();

  Stream<void> get onResync => _onResync.stream;
  ReverbConnectionState get state => _client.state;
  bool get isConnected => _client.isConnected;
  Set<String> get authFailedChannels => Set.of(_client.authFailed);

  /// Session generation. Bumped on start/stop so consumers can ignore
  /// stale events from a prior session.
  int get generation => _generation;
  int _generation = 0;

  /// Start (or restart) the socket for a NEW authenticated session.
  /// Bumps generation so stale events from a prior session are ignored.
  void start() {
    if (_disposed) return;
    _generation++;
    _eventSub ??= _client.events.listen(_dispatch);
    _resyncSub ??= _client.resubscribed.listen((_) {
      if (!_disposed) _onResync.add(null);
    });
    _client.connect();
  }

  /// Full teardown on logout / session expiry. Future [start] calls create
  /// a fresh connection; old listeners receive nothing further.
  void stop() {
    _generation++;
    _refCounts.clear();
    for (final channel in List.of(_listeners.keys)) {
      _client.untrackSubscription('private-$channel');
    }
    _listeners.clear();
    _client.disconnect();
  }

  /// Foreground-resume synchronization. If the socket is down AND the app
  /// still wants subscriptions, reconnect immediately (bypasses the
  /// reconnect backoff timer). ALWAYS fire onResync so consumers run a REST
  /// catch-up (id-dedupe safe, covers events missed while the process was
  /// backgrounded and the connection was frozen) — even when no consumer is
  /// currently subscribed (4A.2.2: the Chat tab may not be mounted yet, but
  /// controllers listening on onResync alone still catch up).
  ///
  /// No WebSocket is opened when nothing is desired: with zero tracked
  /// subscriptions a connection would be pure overhead.
  void resume() {
    if (_disposed) return;
    if (!isConnected && _client.hasDesiredSubscriptions) {
      _eventSub ??= _client.events.listen(_dispatch);
      _client.connect(); // desired subscriptions auto-restore on connect
    }
    _onResync.add(null);
  }

  void dispose() {
    _disposed = true;
    stop();
    _eventSub?.cancel();
    _eventSub = null;
    _onResync.close();
    _client.dispose();
  }

  /// Subscribe to a channel (e.g. 'channel.49', 'user.5'). Channel names
  /// here are WITHOUT the private- prefix. Returns a cancel function.
  VoidCallback subscribe(
      String channel, void Function(BroadcastEvent) onEvent) {
    if (_disposed) return () {};
    _listeners.putIfAbsent(channel, () => []).add(onEvent);
    final newCount = _refCounts[channel] = (_refCounts[channel] ?? 0) + 1;
    if (newCount == 1) {
      _client.trackSubscription('private-$channel');
    }
    return () => unsubscribe(channel, onEvent);
  }

  void unsubscribe(String channel, void Function(BroadcastEvent) onEvent) {
    final list = _listeners[channel];
    if (list == null) return;
    list.remove(onEvent);
    if (list.isEmpty) {
      _listeners.remove(channel);
      final count = (_refCounts[channel] ?? 1) - 1;
      if (count <= 0) {
        _refCounts.remove(channel);
        _client.untrackSubscription('private-$channel');
      } else {
        _refCounts[channel] = count;
      }
    }
  }

  void _dispatch(BroadcastEvent event) {
    // Strip private- prefix to match subscribe() naming.
    var channel = event.channel;
    if (channel.startsWith('private-')) {
      channel = channel.substring(8);
    }
    final listeners = _listeners[channel];
    if (listeners == null) return;
    for (final l in List.of(listeners)) {
      l(event);
    }
  }

  /// Wire reconnect notifications to catch-up logic.
  StreamSubscription<void> listenResync(void Function() onResync) =>
      _onResync.stream.listen((_) {
        if (!_disposed) onResync();
      });

  /// Channels currently subscribed (no private- prefix). Test/inspection.
  Set<String> get subscribedChannels => Set.of(_listeners.keys);

  /// Inject an event as if received from the wire. Test-only.
  @visibleForTesting
  void debugEmit(BroadcastEvent event) => _dispatch(event);
}

typedef VoidCallback = void Function();
