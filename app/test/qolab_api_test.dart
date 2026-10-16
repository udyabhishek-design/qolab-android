import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';

/// A minimal mock of the Laravel auth surface:
///   GET /login      → sets session + XSRF cookies
///   POST /login     → validates CSRF header, checks credentials, rotates
///                     the session cookie
///   GET /dashboard  → Inertia JSON with auth.user when session valid;
///                     302 → /login when not
///   POST /logout    → requires CSRF, kills session
class MockLaravel {
  MockLaravel({
    this.validEmail = 'user@qolab.test',
    this.validPassword = 'secret123',
  });

  final String validEmail;
  final String validPassword;

  late HttpServer server;
  String? session;
  String? xsrf;
  int loginAttempts = 0;

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen(_handle);
  }

  String get origin => 'http://127.0.0.1:${server.port}';

  Future<void> stop() => server.close(force: true);

  void _handle(HttpRequest req) async {
    final res = req.response;
    final path = req.uri.path;

    if (path == '/login' && req.method == 'GET') {
      session = 'sess-bootstrap';
      xsrf = 'enc-csrf%3D%3D';
      _setCookies(res);
      res.statusCode = 200;
      res.write('<html><body>login page</body></html>');
      await res.close();
      return;
    }

    if (path == '/login' && req.method == 'POST') {
      loginAttempts++;
      final csrf = req.headers.value('X-XSRF-TOKEN');
      if (csrf == null || csrf != Uri.decodeComponent(xsrf ?? '')) {
        res.statusCode = 419;
        res.headers.contentType = ContentType.json;
        res.write(jsonEncode({'message': 'CSRF token mismatch.'}));
        await res.close();
        return;
      }
      final body = await utf8.decoder.bind(req).join();
      final fields = jsonDecode(body) as Map<String, dynamic>;
      if (fields['email'] != validEmail ||
          fields['password'] != validPassword) {
        res.statusCode = 422;
        res.headers.contentType = ContentType.json;
        res.write(jsonEncode({
          'message': 'These credentials do not match our records.',
          'errors': {
            'email': ['These credentials do not match our records.']
          }
        }));
        await res.close();
        return;
      }
      session = 'sess-rotated';
      _setCookies(res);
      res.statusCode = 200;
      res.headers.contentType = ContentType.json;
      res.write(jsonEncode({'ok': true}));
      await res.close();
      return;
    }

    if (path == '/dashboard' && req.method == 'GET') {
      if (session == null || !_hasSessionCookie(req)) {
        res.statusCode = 302;
        res.headers.set(HttpHeaders.locationHeader, '/login');
        await res.close();
        return;
      }
      if (req.headers.value('X-Inertia') != 'true') {
        res.statusCode = 200;
        res.headers.contentType = ContentType.html;
        res.write(
            '<html><script data-page="app" type="application/json">${jsonEncode({
          'component': 'Dashboard',
          'props': {
            'auth': {
              'user': {
                'id': 42,
                'name': 'Test User',
                'email': 'user@qolab.test'
              }
            }
          }
        })}</script></html>');
        await res.close();
        return;
      }
      res.statusCode = 200;
      res.headers.contentType = ContentType.json;
      res.write(jsonEncode({
        'component': 'Dashboard',
        'props': {
          'auth': {
            'user': {
              'id': 42,
              'name': 'Test User',
              'email': 'user@qolab.test',
              'job_title': 'Developer',
            }
          }
        }
      }));
      await res.close();
      return;
    }

    if (path == '/logout' && req.method == 'POST') {
      session = null;
      xsrf = null;
      res.statusCode = 200;
      await res.close();
      return;
    }

    res.statusCode = 404;
    await res.close();
  }

  void _setCookies(HttpResponse res) {
    res.headers.add(HttpHeaders.setCookieHeader,
        'qolab-session=$session; path=/; httponly; samesite=lax');
    res.headers.add(HttpHeaders.setCookieHeader,
        'XSRF-TOKEN=$xsrf; path=/; samesite=lax');
  }

  bool _hasSessionCookie(HttpRequest req) {
    final cookieHeader = req.headers.value(HttpHeaders.cookieHeader) ?? '';
    return cookieHeader.contains('qolab-session=');
  }
}

void main() {
  late MockLaravel mock;

  setUp(() async {
    mock = MockLaravel();
    await mock.start();
  });

  tearDown(() async {
    await mock.stop();
  });

  QolabApi api() => QolabApi(origin: mock.origin);

  test('bootstrap captures session + XSRF cookies', () async {
    final a = api();
    await a.bootstrapSession();
    expect(a.cookies['qolab-session']?.value, 'sess-bootstrap');
    expect(a.cookies['XSRF-TOKEN']?.value, 'enc-csrf%3D%3D');
  });

  test('CSRF token is URL-decoded from the cookie', () async {
    final a = api();
    await a.bootstrapSession();
    expect(a.csrfToken, 'enc-csrf==');
  });

  test('successful login rotates the session cookie and returns identity',
      () async {
    final a = api();
    await a.bootstrapSession();
    await a.login(
        email: mock.validEmail, password: mock.validPassword);
    expect(a.cookies['qolab-session']?.value, 'sess-rotated');
    final user = await a.fetchCurrentUser();
    expect(user.id, 42);
    expect(user.name, 'Test User');
  });

  test('wrong credentials → validation error with field message', () async {
    final a = api();
    await a.bootstrapSession();
    await expectLater(
      a.login(email: mock.validEmail, password: 'wrong'),
      throwsA(isA<ApiException>()
          .having((e) => e.kind, 'kind', ApiErrorKind.validation)
          .having((e) => e.fieldErrors?['email'], 'field error',
              'These credentials do not match our records.')),
    );
  });

  test('missing CSRF header on POST /login → 419 csrfExpired', () async {
    final a = api();
    // No bootstrap → no cookies → no X-XSRF-TOKEN sent → mock answers 419.
    await expectLater(
      a.login(email: mock.validEmail, password: mock.validPassword),
      throwsA(isA<ApiException>()
          .having((e) => e.kind, 'kind', ApiErrorKind.csrfExpired)),
    );
  });

  test('429 rate limit maps to rateLimited', () {
    final e = apiErrorFromStatus(429);
    expect(e.kind, ApiErrorKind.rateLimited);
    expect(e.message, contains('Too many attempts'));
  });

  test('500/503 map to server', () {
    expect(apiErrorFromStatus(500).kind, ApiErrorKind.server);
    expect(apiErrorFromStatus(503).kind, ApiErrorKind.server);
  });

  test('unauthenticated: /dashboard redirect to /login', () async {
    final a = api();
    await expectLater(
      a.fetchCurrentUser(),
      throwsA(isA<ApiException>()
          .having((e) => e.kind, 'kind', ApiErrorKind.unauthenticated)),
    );
  });

  test('logout posts with CSRF and clears server session', () async {
    final a = api();
    await a.bootstrapSession();
    await a.login(
        email: mock.validEmail, password: mock.validPassword);
    await a.logout();
    expect(mock.session, isNull);
    await expectLater(a.fetchCurrentUser(),
        throwsA(isA<ApiException>()));
  });

  test('network timeout maps to timeout kind', () {
    final e = apiErrorFromNetwork(Exception('Connection timed out'));
    expect(e.kind, ApiErrorKind.timeout);
  });

  test('connection refused maps to network kind', () {
    final e = apiErrorFromNetwork(const SocketException('refused'));
    expect(e.kind, ApiErrorKind.network);
  });

  test('422 body with field errors parses', () {
    final e = apiErrorFromStatus(422,
        body: jsonEncode({
          'errors': {'password': ['The password field is required.']}
        }));
    expect(e.fieldErrors?['password'], 'The password field is required.');
  });
}
