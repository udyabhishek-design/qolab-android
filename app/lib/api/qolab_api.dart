import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'api_errors.dart';
import 'cookie_jar.dart';
import 'inertia.dart';

/// Result of an API request, with redirect destination and response
/// headers for auth/Inertia-protocol detection.
class ApiResult {
  ApiResult({
    required this.status,
    required this.body,
    this.location,
    this.headers = const {},
  });

  final int status;
  final String body;
  final String? location;

  /// Response headers (lowercased names) — used to distinguish Inertia's
  /// 409 version-mismatch signal (`x-inertia-location`) from unrelated
  /// 409 conflicts.
  final Map<String, String> headers;

  bool get redirectedToLogin =>
      (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) &&
      (location == '/login' || location?.startsWith('/login') == true);
}

/// HTTP + CSRF + session client for the Qolab Laravel backend (Option A:
/// Flutter talks to Laravel directly; Go is not in this path).
class QolabApi {
  QolabApi({required this.origin, HttpClient? client})
      : _client = client ?? HttpClient() {
    _client.connectionTimeout = const Duration(seconds: 15);
    // HTTPS only — never bypass certificate validation.
  }

  /// e.g. https://workpulse-gbpedt.drytis.dev (no trailing slash).
  final String origin;
  final HttpClient _client;
  final CookieJar cookies = CookieJar();

  Duration requestTimeout = const Duration(seconds: 30);

  /// Overall bound for the logout request ONLY — deliberately short so a
  /// slow or unreachable backend cannot stall sign-out. Login and other
  /// requests keep the generous [requestTimeout] untouched.
  Duration logoutTimeout = const Duration(seconds: 8);

  /// URL-decode the XSRF-TOKEN cookie value for use in X-XSRF-TOKEN.
  String? get csrfToken {
    final xsrf = cookies['XSRF-TOKEN'];
    if (xsrf == null || xsrf.value.isEmpty) return null;
    // The cookie value is percent-encoded; Laravel expects the decoded
    // value echoed in the header.
    return Uri.decodeComponent(xsrf.value);
  }

  /// 1–4: bootstrap the session (GET /login) — captures the Laravel session
  /// cookie and XSRF-TOKEN.
  Future<void> bootstrapSession() async {
    await _request('GET', '/login', followRedirects: false);
  }

  /// 6: submit credentials. On success the session cookie is rotated by the
  /// server and captured by the jar automatically.
  Future<void> login({required String email, required String password}) async {
    final r = await _request(
      'POST',
      '/login',
      body: _jsonBody({'email': email, 'password': password}),
      isMutation: true,
      followRedirects: false,
      expectJson: true,
    );
    if (r.status >= 200 && r.status < 300) return;
    throw apiErrorFromStatus(r.status,
        body: r.body, redirectedToLogin: r.redirectedToLogin);
  }

  /// 8–9: fetch authenticated identity from an Inertia endpoint.
  ///
  /// Plain authenticated GET (NO `X-Inertia: true` header): an X-Inertia
  /// request without a matching X-Inertia-Version triggers Laravel's
  /// version-mismatch protocol (HTTP 409 + X-Inertia-Location), so we
  /// request the ordinary HTML document and parse the embedded
  /// `data-page` JSON payload instead.
  Future<QolabUser> fetchCurrentUser() async {
    final r = await _request('GET', '/dashboard', followRedirects: false);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status,
          body: r.body,
          inertiaLocation: r.headers['x-inertia-location']);
    }
    final page = InertiaPage.parse(r.body);
    final user = page?.authUser;
    if (user == null) {
      throw const ApiException(ApiErrorKind.unknown,
          'Could not read your account details.');
    }
    return user;
  }

  /// Validate a restored session: returns the user when still authenticated.
  Future<QolabUser> validateSession() => fetchCurrentUser();

  /// Fetch the dashboard Inertia page (HTML, plain GET — same protocol as
  /// [fetchCurrentUser]; never send X-Inertia without version negotiation).
  /// Caller parses props via InertiaPage.parse().
  Future<String> fetchDashboardHtml() async {
    final r = await _request('GET', '/dashboard', followRedirects: false);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status,
          body: r.body,
          inertiaLocation: r.headers['x-inertia-location']);
    }
    return r.body;
  }

  /// POST /broadcasting/auth — Reverb private-channel authorization.
  /// [formBody] must be form-encoded `channel_name=…&socket_id=…` (a JSON
  /// body is rejected 403 — verified against the dev backend).
  Future<String> broadcastingAuth(String formBody) async {    final r = await _request('POST', '/broadcasting/auth',
        body: formBody,
        isMutation: true,
        followRedirects: false,
        expectJson: true,
        formEncoded: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status, body: r.body);
    }
    return r.body;
  }

  /// Fetch the rooms Inertia page (HTML, plain GET).
  Future<String> fetchRoomsHtml() async {    final r = await _request('GET', '/rooms', followRedirects: false);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status,
          body: r.body,
          inertiaLocation: r.headers['x-inertia-location']);
    }
    return r.body;
  }

  /// JSON unread-notification count. Independent of Home primary content —
  /// failures must never block rendering (callers treat as "unknown").
  Future<int> fetchUnreadCount() async {
    final r = await _request('GET', '/notifications/unread-count',
        followRedirects: false, expectJson: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status, body: r.body);
    }
    final decoded = jsonDecode(r.body);
    if (decoded is Map<String, dynamic>) {
      final count = decoded['unread_count'];
      if (count is int) return count;
      if (count is num) return count.toInt();
    }
    throw const ApiException(ApiErrorKind.unknown,
        'Unread count response was not readable.');
  }

  // ── Phase 4A.1: Chat endpoints ────────────────────────────────────

  /// GET /chat — Inertia HTML (plain GET, same protocol as /dashboard).
  Future<String> fetchChatIndexHtml() async {
    final r = await _request('GET', '/chat', followRedirects: false);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status,
          body: r.body, inertiaLocation: r.headers['x-inertia-location']);
    }
    return r.body;
  }

  /// GET /chat/channels/{channel} — Inertia HTML channel detail.
  Future<String> fetchChannelDetailHtml(int channelId) async {
    final r = await _request('GET', '/chat/channels/$channelId',
        followRedirects: false);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status == 301 || r.status == 302) {
      // A room channel redirects to the Room experience — not a chat
      // destination; surface a conflict rather than following it.
      throw const ApiException(
          ApiErrorKind.conflict, 'This conversation is not available in Chat.');
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status,
          body: r.body, inertiaLocation: r.headers['x-inertia-location']);
    }
    return r.body;
  }

  /// GET /chat/channels/{channel}/messages?page=N — JSON, newest-first.
  Future<String> fetchMessagesJson(int channelId,
      {int page = 1, int perPage = 50}) async {
    final r = await _request(
        'GET', '/chat/channels/$channelId/messages?page=$page&per_page=$perPage',
        followRedirects: false, expectJson: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status, body: r.body);
    }
    return r.body;
  }

  /// POST /chat/channels/{channel}/messages — send a text message.
  /// Returns the raw JSON body ({"message": formatMessage}).
  Future<String> sendMessageJson(int channelId, String body) async {
    final r = await _request('POST', '/chat/channels/$channelId/messages',
        body: _jsonBody({'body': body}),
        isMutation: true,
        followRedirects: false,
        expectJson: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 201 && r.status != 200) {
      throw apiErrorFromStatus(r.status, body: r.body);
    }
    return r.body;
  }

  /// POST /chat/dm/{user} — start or reopen a DM. Returns the channel id
  /// parsed from the redirect Location (`/chat/{id}`).
  Future<int> startDm(int userId) async {
    final r = await _request('POST', '/chat/dm/$userId',
        isMutation: true, followRedirects: false);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status == 301 || r.status == 302 || r.status == 303) {
      final loc = r.location ?? '';
      // chat.show route redirects to /chat/channels/{channel} (current) or
      // the legacy /chat/{channel} — accept both.
      final match =
          RegExp(r'^/chat/channels/(\d+)$').firstMatch(loc) ??
              RegExp(r'^/chat/(\d+)$').firstMatch(loc);
      if (match != null) {
        return int.parse(match.group(1)!);
      }
      // Redirect back with an error (self-DM / inactive / cross-company).
      throw const ApiException(ApiErrorKind.conflict,
          'This conversation cannot be started.');
    }
    if (r.status == 403) {
      throw const ApiException(ApiErrorKind.conflict,
          'You can only message users in your company.');
    }
    throw apiErrorFromStatus(r.status, body: r.body);
  }

  /// PATCH /chat/channels/{channel}/read — mark read up to a message.
  Future<void> markRead(int channelId, int lastReadMessageId) async {
    final r = await _request('PATCH', '/chat/channels/$channelId/read',
        body: _jsonBody({'last_read_message_id': lastReadMessageId}),
        isMutation: true,
        followRedirects: false,
        expectJson: true,
        tolerateFailure: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
  }

  /// GET /chat/unread-counts — JSON {data: {channelId: count}}.
  Future<Map<int, int>> fetchChatUnreadCounts() async {
    final r = await _request('GET', '/chat/unread-counts',
        followRedirects: false, expectJson: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status, body: r.body);
    }
    final decoded = jsonDecode(r.body);
    if (decoded is Map<String, dynamic>) {
      final data = decoded['data'];
      if (data is Map) {
        final out = <int, int>{};
        data.forEach((k, v) {
          final id = int.tryParse(k.toString());
          final n = v is int ? v : (v is num ? v.toInt() : null);
          if (id != null && n != null) out[id] = n;
        });
        return out;
      }
    }
    throw const ApiException(ApiErrorKind.unknown,
        'Unread counts response was not readable.');
  }

  /// 12: server-side logout; caller clears local state afterwards.
  ///
  /// Bounded and ISOLATED from the shared cookie jar: the request uses a
  /// snapshot of the current cookies taken at call time, so a late response
  /// can never rotate/delete cookies of a session started afterwards
  /// (immediate re-login after logout). If the server cannot be reached in
  /// time, the request is aborted — but the caller has already cleared the
  /// local session, so the old one is never silently reused. NOTE: when
  /// this throws (timeout/network/5xx) the server-side session may still be
  /// alive until its natural expiry; only the device-side session is
  /// guaranteed gone.
  Future<void> logout() async {
    final snapshot = cookies.cookieHeader();
    final token = csrfToken; // captured BEFORE any local clearing
    // Dedicated, short-lived client: closing it (in the finally below or on
    // timeout) tears the logout connection down completely — no zombie
    // request can keep the server-side session lock busy.
    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 15);
    try {
      await _request(
        'POST',
        '/logout',
        isMutation: true,
        followRedirects: false,
        expectJson: true,
        tolerateFailure: true,
        cookieHeaderOverride: snapshot,
        csrfTokenOverride: token,
        overallTimeout: logoutTimeout,
        isolatedClient: client,
      );
    } finally {
      client.close(force: true);
    }
  }

  Future<ApiResult> _request(
    String method,
    String path, {
    String? body,
    bool isMutation = false,
    bool followRedirects = true,
    bool expectJson = false,
    bool tolerateFailure = false,
    String? cookieHeaderOverride,
    String? csrfTokenOverride,
    Duration? overallTimeout,
    HttpClient? isolatedClient,
    bool formEncoded = false,
  }) async {
    // Overall timeout bounds the WHOLE request (connect + headers + body).
    // On timeout the isolated client (when present) is force-closed so the
    // socket is aborted, not abandoned. For shared-client requests the
    // await simply stops waiting; the per-request connection is left to
    // the HttpClient's own idle handling.
    final bound = overallTimeout ?? requestTimeout;
    final reqFuture = _doRequest(
      method,
      path,
      body: body,
      isMutation: isMutation,
      followRedirects: followRedirects,
      expectJson: expectJson,
      tolerateFailure: tolerateFailure,
      cookieHeaderOverride: cookieHeaderOverride,
      csrfTokenOverride: csrfTokenOverride,
      isolatedClient: isolatedClient,
      formEncoded: formEncoded,
    );
    try {
      return await reqFuture.timeout(bound, onTimeout: () {
        if (isolatedClient != null) {
          isolatedClient.close(force: true);
        }
        throw _timeoutException();
      });
    } on TimeoutException {
      throw _timeoutException();
    }
  }

  Future<ApiResult> _doRequest(
    String method,
    String path, {
    String? body,
    bool isMutation = false,
    bool followRedirects = true,
    bool expectJson = false,
    bool tolerateFailure = false,
    String? cookieHeaderOverride,
    String? csrfTokenOverride,
    HttpClient? isolatedClient,
    bool formEncoded = false,
  }) async {
    final uri = Uri.parse('$origin$path');
    final client = isolatedClient ?? _client;
    HttpClientRequest req;
    try {
      req = await client.openUrl(method, uri);
    } on SocketException catch (e) {
      throw apiErrorFromNetwork(e);
    }
    req.followRedirects = followRedirects;
    req.maxRedirects = 0;

    final cookieHeader =
        cookieHeaderOverride ?? cookies.cookieHeader();
    if (cookieHeader != null) {
      req.headers.set(HttpHeaders.cookieHeader, cookieHeader);
    }
    req.headers.set(HttpHeaders.acceptHeader,
        expectJson ? 'application/json' : 'text/html,application/json');
    if (expectJson) {
      req.headers.set(HttpHeaders.contentTypeHeader,
          formEncoded ? 'application/x-www-form-urlencoded' : 'application/json');
    }
    if (isMutation) {
      final token = csrfTokenOverride ?? csrfToken;
      if (token != null) req.headers.set('X-XSRF-TOKEN', token);
      req.headers.set('X-Requested-With', 'XMLHttpRequest');
    }
    if (body != null) {
      req.headers.contentLength = utf8.encode(body).length;
      req.write(body);
    }

    HttpClientResponse resp;
    try {
      resp = await req.close();
    } on SocketException catch (e) {
      throw apiErrorFromNetwork(e);
    } on HttpException catch (e) {
      throw apiErrorFromNetwork(e);
    }

    // 7: capture Set-Cookie — including rotated session cookies. Requests
    // using a cookie OVERRIDE (logout) deliberately do NOT merge responses
    // back into the shared jar: a late logout response must never
    // overwrite/delete cookies belonging to a newer session.
    if (cookieHeaderOverride == null) {
      final setCookies = resp.headers[HttpHeaders.setCookieHeader] ?? const [];
      if (setCookies.isNotEmpty) {
        cookies.storeFrom(setCookies);
      }
    }

    final text = await resp.transform(utf8.decoder).join();

    if (tolerateFailure) {
      return ApiResult(status: resp.statusCode, body: text);
    }
    final Map<String, String> respHeaders = {};
    resp.headers.forEach((name, values) {
      if (values.isNotEmpty) respHeaders[name.toLowerCase()] = values.first;
    });
    return ApiResult(
      status: resp.statusCode,
      body: text,
      location: resp.headers.value(HttpHeaders.locationHeader),
      headers: respHeaders,
    );
  }

  String _jsonBody(Map<String, Object> fields) => jsonEncode(fields);

  ApiException _timeoutException() => const ApiException(
      ApiErrorKind.timeout, 'The connection timed out. Please try again.');

  void dispose() => _client.close(force: true);
}
