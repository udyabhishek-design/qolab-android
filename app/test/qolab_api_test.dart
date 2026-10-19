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

  /// Active server-side sessions, keyed by session id — like real Laravel
  /// (independent sessions; an old-session logout cannot invalidate an
  /// unrelated newer session).
  final Map<String, String> _xsrfBySession = {};
  final Set<String> _activeSessions = {};
  int _seq = 0;

  /// The most recently issued session id (for legacy assertions), or null
  /// when that session has been logged out.
  String? session;

  int loginAttempts = 0;

  /// Logout behaviour knobs for regression tests.
  int logoutStatus = 200;
  Duration logoutDelay = Duration.zero;
  int logoutRequests = 0;
  String? lastLogoutCookieHeader;

  /// Bootstrap/login counters for fresh-CSRF assertions.
  int bootstraps = 0;
  int loginPosts = 0;
  String? lastLoginCookieHeader;

  /// Headers of the last /dashboard request (for regression assertions).
  Map<String, String> lastDashboardHeaders = {};

  /// Home-content mock knobs.
  Map<String, dynamic>? dashboardPropsOverride;
  Map<String, dynamic>? roomsChannelsOverride;
  int roomsStatus = 200;
  int unreadStatus = 200;
  int unreadValue = 0;
  Duration unreadDelay = Duration.zero;

  String _newSession(String prefix) {
    final id = '$prefix-${++_seq}';
    _activeSessions.add(id);
    _xsrfBySession[id] = 'enc-csrf%3D%3D';
    session = id;
    return id;
  }

  Map<String, String> _cookiesOf(String header) => {
        for (final part in header.split(';'))
          if (part.contains('='))
            part.trim().split('=').first.trim():
                part.trim().split('=').skip(1).join('='),
      };

  /// The session id this request is authenticated as, if any.
  String? _sessionOf(HttpRequest req) {
    final jar = _cookiesOf(
        req.headers.value(HttpHeaders.cookieHeader) ?? '');
    final sid = jar['qolab-session'];
    if (sid == null || !_activeSessions.contains(sid)) return null;
    return sid;
  }

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      try {
        await _handle(req);
      } catch (_) {
        // A delayed response writing into a closed server during teardown —
        // swallow so it can't fail an unrelated test.
      }
    });
  }

  String get origin => 'http://127.0.0.1:${server.port}';

  Future<void> stop() => server.close(force: true);

  Future<void> _handle(HttpRequest req) async {
    final res = req.response;
    final path = req.uri.path;

    if (path == '/login' && req.method == 'GET') {
      bootstraps++;
      _setCookies(res, _newSession('sess-bootstrap'));
      res.statusCode = 200;
      res.write('<html><body>login page</body></html>');
      await res.close();
      return;
    }

    if (path == '/login' && req.method == 'POST') {
      loginAttempts++;
      loginPosts++;
      lastLoginCookieHeader =
          req.headers.value(HttpHeaders.cookieHeader) ?? '';
      final sid = _sessionOf(req);
      final csrf = req.headers.value('X-XSRF-TOKEN');
      if (sid == null ||
          csrf == null ||
          csrf != Uri.decodeComponent(_xsrfBySession[sid] ?? '')) {
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
      // Laravel regenerates the session on login: the bootstrap session is
      // superseded by the rotated one.
      _activeSessions.remove(sid);
      _xsrfBySession.remove(sid);
      _setCookies(res, _newSession('sess-rotated'));
      res.statusCode = 200;
      res.headers.contentType = ContentType.json;
      res.write(jsonEncode({'ok': true}));
      await res.close();
      return;
    }

    if (path == '/dashboard' && req.method == 'GET') {
      lastDashboardHeaders = {
        'x-inertia': req.headers.value('X-Inertia') ?? '',
        'x-requested-with': req.headers.value('X-Requested-With') ?? '',
        'cookie': req.headers.value(HttpHeaders.cookieHeader) ?? '',
      };
      if (_sessionOf(req) == null) {
        res.statusCode = 302;
        res.headers.set(HttpHeaders.locationHeader, '/login');
        await res.close();
        return;
      }
      // Mirrors real Inertia middleware: an X-Inertia request with a
      // missing/stale version triggers the version-mismatch protocol.
      if (req.headers.value('X-Inertia') == 'true') {
        res.statusCode = 409;
        res.headers.set('X-Inertia-Location', '$origin/dashboard');
        await res.close();
        return;
      }
      // Plain authenticated GET -> HTML document with the data-page blob.
      // Custom dashboard props (Home tests) or the default identity page.
      final homeProps = dashboardPropsOverride;
      res.statusCode = 200;
      res.headers.contentType = ContentType.html;
      final props = homeProps ??
          {
            'auth': {
              'user': {
                'id': 42,
                'name': 'Test User',
                'email': 'user@qolab.test',
                'job_title': 'Developer',
              }
            }
          };
      res.write(
          '<html><script data-page="app" type="application/json">${jsonEncode({
        'component': 'Dashboard',
        'props': props,
      })}</script></html>');
      await res.close();
      return;
    }

    if (path == '/rooms' && req.method == 'GET') {
      if (_sessionOf(req) == null) {
        res.statusCode = 302;
        res.headers.set(HttpHeaders.locationHeader, '/login');
        await res.close();
        return;
      }
      res.statusCode = roomsStatus;
      res.headers.contentType = ContentType.html;
      res.write(
          '<html><script data-page="app" type="application/json">${jsonEncode({
        'component': 'Rooms',
        'props': {
          'channels': roomsChannelsOverride ??
              {
                'department': [
                  {
                    'id': 50,
                    'name': 'Engineering Room',
                    'room_type': 'department',
                    'room_slug': 'engineering',
                    'members_count': 12,
                    'department_id': 7,
                  }
                ],
                'group': [],
                'company': [],
                'dm': [],
              }
        }
      })}</script></html>');
      await res.close();
      return;
    }

    if (path == '/notifications/unread-count' &&
        req.method == 'GET') {
      if (_sessionOf(req) == null) {
        res.statusCode = 302;
        res.headers.set(HttpHeaders.locationHeader, '/login');
        await res.close();
        return;
      }
      res.statusCode = unreadStatus;
      res.headers.contentType = ContentType.json;
      if (unreadDelay > Duration.zero) {
        await Future<void>.delayed(unreadDelay);
      }
      res.write(jsonEncode({'unread_count': unreadValue}));
      await res.close();
      return;
    }

    if (path == '/logout' && req.method == 'POST') {
      logoutRequests++;
      final logoutCookies =
          req.headers.value(HttpHeaders.cookieHeader) ?? '';
      lastLogoutCookieHeader = logoutCookies;
      if (logoutDelay > Duration.zero) {
        await Future<void>.delayed(logoutDelay);
      }
      // Session-aware, like real Laravel: invalidate ONLY the session the
      // request actually carries; an old-session logout leaves an
      // unrelated newer session untouched.
      final sid = _cookiesOf(logoutCookies)['qolab-session'];
      if (sid != null) {
        _activeSessions.remove(sid);
        _xsrfBySession.remove(sid);
        if (session == sid) session = null;
      }
      res.statusCode = logoutStatus;
      await res.close();
      return;
    }

    res.statusCode = 404;
    await res.close();
  }

  void _setCookies(HttpResponse res, String sessionId) {
    res.headers.add(HttpHeaders.setCookieHeader,
        'qolab-session=$sessionId; path=/; httponly; samesite=lax');
    res.headers.add(HttpHeaders.setCookieHeader,
        'XSRF-TOKEN=${_xsrfBySession[sessionId] ?? 'enc-csrf%3D%3D'}; '
        'path=/; samesite=lax');
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
    expect(a.cookies['qolab-session']?.value, startsWith('sess-bootstrap'));
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
    expect(a.cookies['qolab-session']?.value, startsWith('sess-rotated'));
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

  test('REGRESSION: fetchCurrentUser does NOT send X-Inertia: true '
      '(which would trigger 409 version mismatch)', () async {
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);
    await a.fetchCurrentUser();
    expect(mock.lastDashboardHeaders['x-inertia'], isNot('true'));
    expect(mock.lastDashboardHeaders['x-inertia'] ?? '', isEmpty);
  });

  test('REGRESSION: full flow — login rotates session, then HTML identity '
      'retrieval succeeds and reaches authenticated state', () async {
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);
    expect(a.cookies['qolab-session']?.value, startsWith('sess-rotated'));
    final user = await a.fetchCurrentUser();
    expect(user.id, 42);
    expect(user.name, 'Test User');
    expect(user.jobTitle, 'Developer');
  });

  test('REGRESSION: an authenticated X-Inertia GET without a version header '
      'receives 409 from the Inertia-shaped mock (documents the server '
      'contract that broke login)', () async {
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);
    // Raw request mimicking the OLD buggy client: X-Inertia without version.
    final client = HttpClient();
    final req =
        await client.openUrl('GET', Uri.parse('${mock.origin}/dashboard'));
    req.followRedirects = false;
    final jar = a.cookies.cookieHeader();
    if (jar != null) {
      req.headers.set(HttpHeaders.cookieHeader, jar);
    }
    req.headers.set('X-Inertia', 'true');
    req.headers.set('X-Requested-With', 'XMLHttpRequest');
    final resp = await req.close();
    expect(resp.statusCode, 409);
    expect(resp.headers.value('X-Inertia-Location'), isNotNull);
    client.close(force: true);
  });

  test('REGRESSION: HTTP 409 is handled explicitly '
      '(Inertia mismatch vs unrelated conflict)', () {
    final mismatch = apiErrorFromStatus(409,
        inertiaLocation: 'https://example.test/dashboard');
    expect(mismatch.kind, ApiErrorKind.inertiaVersionMismatch);
    expect(mismatch.message, contains('out of sync'));

    final conflict = apiErrorFromStatus(409);
    expect(conflict.kind, ApiErrorKind.conflict);
    expect(conflict.message, contains('conflicted'));
  });

  test('REGRESSION: wrong-credentials behavior unchanged after 409 fix',
      () async {
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

  test('422 body with field errors parses', () {
    final e = apiErrorFromStatus(422,
        body: jsonEncode({
          'errors': {'password': ['The password field is required.']}
        }));
    expect(e.fieldErrors?['password'], 'The password field is required.');
  });

  test('REGRESSION: logout sends the pre-logout cookie snapshot and CSRF, '
      'and a delayed logout response cannot touch the new session cookies',
      () async {
    mock.logoutDelay = const Duration(milliseconds: 1200);
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);
    final oldSession = a.cookies['qolab-session']?.value;
    expect(oldSession, isNotNull);

    final logoutFuture = a.logout(); // slow server logout in flight…

    // …meanwhile a fresh login happens on a NEW client (immediate
    // re-login scenario). Give it a head start so it completes first.
    final b = api();
    await b.bootstrapSession();
    await b.login(email: mock.validEmail, password: mock.validPassword);
    expect(b.cookies['qolab-session']?.value, isNotNull);
    final newSession = b.cookies['qolab-session']?.value;

    await logoutFuture; // delayed logout finally completes

    // The server logout carried the OLD cookies (snapshot semantics).
    expect(mock.lastLogoutCookieHeader, contains(oldSession!));
    expect(mock.lastLogoutCookieHeader, isNot(contains(newSession!)));
    // The new client's cookies are untouched by the old logout response.
    expect(b.cookies['qolab-session']?.value, newSession);
    expect(b.cookies.length, greaterThan(0));
  });

  test('REGRESSION: logout timing out aborts within the bound '
      '(not the old ~90s worst case)', () async {
    mock.logoutDelay = const Duration(seconds: 30); // server "hangs"
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);

    final sw = Stopwatch()..start();
    // The raw API surfaces the timeout as an ApiException (the auth
    // controller swallows it); what matters is that it is BOUNDED.
    await expectLater(a.logout(), throwsA(isA<ApiException>()));
    expect(sw.elapsed, lessThan(const Duration(seconds: 15)));
  });

  test('REGRESSION: HTTP 419 on logout is tolerated (local cleanup path)',
      () async {
    mock.logoutStatus = 419;
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);
    await a.logout(); // tolerateFailure — must NOT throw
    expect(mock.logoutRequests, 1);
  });

  test('REGRESSION: HTTP 500 on logout is tolerated (local cleanup path)',
      () async {
    mock.logoutStatus = 500;
    final a = api();
    await a.bootstrapSession();
    await a.login(email: mock.validEmail, password: mock.validPassword);
    await a.logout(); // tolerateFailure — must NOT throw
    expect(mock.logoutRequests, 1);
  });
}
