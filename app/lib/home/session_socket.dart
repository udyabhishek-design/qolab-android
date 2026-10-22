import '../api/qolab_api.dart';
import '../chat/chat_socket.dart';

/// App-level chat socket bound to the Qolab Development deployment.
///
/// The Reverb app key is public (it ships in the web client bundle) and the
/// WSS endpoint is same-origin behind Caddy TLS. Private-channel
/// authorization uses the existing Laravel session via [QolabApi].
class QolabChatSocket {
  QolabChatSocket({required QolabApi api, required String origin})
      : _origin = origin,
        _api = api;

  final QolabApi _api;
  final String _origin;

  // Verified public key of the Development Reverb instance.
  static const _reverbAppKey = 'E6bKH3QNfxtlNpk7ByiX';

  ChatSocketService? _service;
  ChatSocketService? get service => _service;

  ChatSocketService ensure() => _service ??= ChatSocketService(
        api: _api,
        appKey: _reverbAppKey,
        // dart:io WebSocket needs the wss:// scheme, not the https:// origin.
        wsOrigin: _origin.replaceFirst('https://', 'wss://'),
      );

  void start() => ensure().start();
  void stop() => _service?.stop();
  void dispose() => _service?.dispose();

  /// Foreground-resume synchronization: reconnect immediately if the socket
  /// is down, and ALWAYS fire a REST catch-up (onResync) — safe even when
  /// the socket is healthy because consumers dedupe by id.
  void resume() => _service?.resume();
}
