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
      req.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
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

  String _jsonBody(Map<String, String> fields) => jsonEncode(fields);

  ApiException _timeoutException() => const ApiException(
      ApiErrorKind.timeout, 'The connection timed out. Please try again.');

  void dispose() => _client.close(force: true);
}
