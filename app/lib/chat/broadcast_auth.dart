import 'dart:async';

import '../api/api_errors.dart';
import '../api/qolab_api.dart';
import 'reverb_client.dart';

/// BroadcastAuth implementation on top of the existing QolabApi request
/// infrastructure: session cookies + CSRF travel with the shared cookie jar.
///
/// VERIFIED (dev probe 2026-10-19): /broadcasting/auth requires a
/// FORM-ENCODED body (`channel_name=…&socket_id=…`). A JSON body is
/// rejected with 403 before channel authorization even runs.
///
/// Security: cookies/CSRF are applied by the API layer; the auth signature
/// returned by the server is only ever used in the subscribe frame — never
/// logged, never placed in a URL.
class SessionBroadcastAuth implements BroadcastAuth {
  SessionBroadcastAuth(this.api);

  final QolabApi api;

  @override
  Future<String> authorize({
    required String channel,
    required String socketId,
  }) async {
    final body =
        'channel_name=${Uri.encodeQueryComponent(channel)}'
        '&socket_id=${Uri.encodeQueryComponent(socketId)}';
    try {
      final response = await api.broadcastingAuth(body);
      // Response JSON: {"auth": "key:signature"}
      final start = response.indexOf('"auth"');
      if (start < 0) {
        throw const BroadcastAuthException(null, 'Malformed auth response');
      }
      final colon = response.indexOf(':', start + 6);
      if (colon < 0) {
        throw const BroadcastAuthException(null, 'Malformed auth response');
      }
      // Extract the JSON string value without a jsonDecode round-trip
      // (keeps this allocation-light); values are always simple strings.
      var rest = response.substring(colon + 1).trimLeft();
      if (rest.startsWith('"')) {
        final end = rest.indexOf('"', 1);
        if (end < 1) {
          throw const BroadcastAuthException(null, 'Malformed auth response');
        }
        return rest.substring(1, end);
      }
      throw const BroadcastAuthException(null, 'Malformed auth response');
    } on ApiException catch (e) {
      throw BroadcastAuthException(
          _statusOf(e), e.message.isEmpty ? 'authorization failed' : e.message);
    }
  }

  int? _statusOf(ApiException e) => switch (e.kind) {
        ApiErrorKind.unauthenticated => 401,
        ApiErrorKind.csrfExpired => 419,
        _ => null,
      };
}
