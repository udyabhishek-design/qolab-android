import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'api_errors.dart';
import 'cookie_jar.dart';
import 'inertia.dart';

/// Result of an API request, with redirect destination for auth detection.
class ApiResult {
  ApiResult({
    required this.status,
    required this.body,
    this.location,
    this.setContentTypeJson = false,
  });

  final int status;
  final String body;
  final String? location;
  final bool setContentTypeJson;

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
  Future<QolabUser> fetchCurrentUser() async {
    final r = await _request('GET', '/dashboard',
        followRedirects: false, xInertia: true);
    if (r.redirectedToLogin || r.status == 401) {
      throw apiErrorFromStatus(401, redirectedToLogin: r.redirectedToLogin);
    }
    if (r.status != 200) {
      throw apiErrorFromStatus(r.status, body: r.body);
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
  Future<void> logout() async {
    try {
      await _request('POST', '/logout',
          isMutation: true,
          followRedirects: false,
          expectJson: true,
          tolerateFailure: true);
    } finally {
      // Local clearing happens in the auth controller regardless of the
      // server response — a failed network logout must still sign the user
      // out of this device.
    }
  }

  Future<ApiResult> _request(
    String method,
    String path, {
    String? body,
    bool isMutation = false,
    bool followRedirects = true,
    bool xInertia = false,
    bool expectJson = false,
    bool tolerateFailure = false,
  }) async {
    final uri = Uri.parse('$origin$path');
    HttpClientRequest req;
    try {
      req = await _client.openUrl(method, uri).timeout(requestTimeout,
          onTimeout: () => throw _timeoutException());
    } on SocketException catch (e) {
      throw apiErrorFromNetwork(e);
    } on TimeoutException {
      throw _timeoutException();
    }
    req.followRedirects = followRedirects;
    req.maxRedirects = 0;

    final cookieHeader = cookies.cookieHeader();
    if (cookieHeader != null) req.headers.set(HttpHeaders.cookieHeader, cookieHeader);
    req.headers.set(HttpHeaders.acceptHeader,
        expectJson ? 'application/json' : 'text/html,application/json');
    if (expectJson) {
      req.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
    }
    if (xInertia) {
      req.headers.set('X-Inertia', 'true');
      req.headers.set('X-Requested-With', 'XMLHttpRequest');
    }
    if (isMutation) {
      final token = csrfToken;
      if (token != null) req.headers.set('X-XSRF-TOKEN', token);
      req.headers.set('X-Requested-With', 'XMLHttpRequest');
    }
    if (body != null) {
      req.headers.contentLength = utf8.encode(body).length;
      req.write(body);
    }

    HttpClientResponse resp;
    try {
      resp = await req.close().timeout(requestTimeout,
          onTimeout: () => throw _timeoutException());
    } on SocketException catch (e) {
      throw apiErrorFromNetwork(e);
    } on TimeoutException {
      throw _timeoutException();
    } on HttpException catch (e) {
      throw apiErrorFromNetwork(e);
    }

    // 7: capture Set-Cookie — including rotated session cookies.
    final setCookies = resp.headers[HttpHeaders.setCookieHeader] ?? const [];
    if (setCookies.isNotEmpty) {
      cookies.storeFrom(setCookies);
    }

    final text = await resp
        .transform(utf8.decoder)
        .join()
        .timeout(requestTimeout, onTimeout: () => throw _timeoutException());

    if (tolerateFailure) {
      return ApiResult(status: resp.statusCode, body: text);
    }
    return ApiResult(
      status: resp.statusCode,
      body: text,
      location: resp.headers.value(HttpHeaders.locationHeader),
    );
  }

  String _jsonBody(Map<String, String> fields) => jsonEncode(fields);

  ApiException _timeoutException() => const ApiException(
      ApiErrorKind.timeout, 'The connection timed out. Please try again.');

  void dispose() => _client.close(force: true);
}
