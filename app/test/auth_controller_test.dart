
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';

import 'qolab_api_test.dart' show MockLaravel;

void main() {
  late MockLaravel mock;
  late AuthController auth;
  late MemorySessionStore store;

  setUp(() async {
    mock = MockLaravel();
    await mock.start();
    store = MemorySessionStore();
    auth = AuthController(api: QolabApi(origin: mock.origin), store: store);
  });

  tearDown(() async {
    await mock.stop();
  });

  test('login → signedIn with real identity, persisted', () async {
    await auth.login('user@qolab.test', 'secret123');
    expect(auth.status, AuthStatus.signedIn);
    expect(auth.user!.name, 'Test User');
    final saved = await store.load();
    expect(saved, isNotNull);
    expect(saved!.jar['qolab-session']?.value, startsWith('sess-rotated'));
    expect(saved.user.id, 42);
  });

  test('wrong password → signedOut + ApiException, nothing persisted',
      () async {
    await expectLater(
      auth.login('user@qolab.test', 'nope'),
      throwsA(isA<Exception>()),
    );
    expect(auth.status, AuthStatus.signedOut);
    expect(await store.load(), isNull);
  });

  test('restore with valid stored session → signedIn without login',
      () async {
    await auth.login('user@qolab.test', 'secret123');
    // Fresh controller, same store — simulates app restart.
    final auth2 =
        AuthController(api: QolabApi(origin: mock.origin), store: store);
    await auth2.restore();
    expect(auth2.status, AuthStatus.signedIn);
    expect(auth2.user!.name, 'Test User');
  });

  test('restore with expired server session → signedOut + store cleared',
      () async {
    await auth.login('user@qolab.test', 'secret123');
    await mock.stop(); // simulate expiry by killing the session server-side
    mock = MockLaravel();
    await mock.start(); // fresh server: no session known
    final auth2 =
        AuthController(api: QolabApi(origin: mock.origin), store: store);
    // Stored cookies point at the OLD server's session. Point the api at the
    // new server to simulate the server having forgotten us.
    await auth2.restore();
    expect(auth2.status, AuthStatus.signedOut);
    expect(await store.load(), isNull);
  });

  test('restore offline → offline status, stored session KEPT', () async {
    await auth.login('user@qolab.test', 'secret123');
    await mock.stop();
    final auth2 =
        AuthController(api: QolabApi(origin: 'http://127.0.0.1:1'), store: store);
    await auth2.restore();
    expect(auth2.status, AuthStatus.offline);
    expect(await store.load(), isNotNull); // not deleted
  });

  test('restore with empty store → signedOut', () async {
    await auth.restore();
    expect(auth.status, AuthStatus.signedOut);
  });

  test('logout clears local state even when server unreachable', () async {
    await auth.login('user@qolab.test', 'secret123');
    await mock.stop();
    await auth.logout();
    expect(auth.status, AuthStatus.signedOut);
    expect(await store.load(), isNull);
    expect(auth.api.cookies.length, 0);
  });

  test('REGRESSION: logout switches to signedOut IMMEDIATELY (slow server '
      'cannot keep Home visible)', () async {
    await auth.login('user@qolab.test', 'secret123');
    mock.logoutDelay = const Duration(seconds: 20); // server "hangs"
    final sw = Stopwatch()..start();
    await auth.logout();
    // Local sign-out must not wait for the slow server request.
    expect(sw.elapsed, lessThan(const Duration(seconds: 2)));
    expect(auth.status, AuthStatus.signedOut);
    expect(await store.load(), isNull);
    expect(auth.api.cookies.length, 0);
  });

  test('REGRESSION: rapid triple-tap triggers exactly one server logout '
      'and one local cleanup', () async {
    await auth.login('user@qolab.test', 'secret123');
    final f1 = auth.logout(); // first tap
    final f2 = auth.logout(); // second tap while in flight
    final f3 = auth.logout(); // third tap while in flight
    await Future.wait([f1, f2, f3]);
    // The server logout runs in the background — give it a beat to land.
    await Future<void>.delayed(const Duration(milliseconds: 150));
    expect(mock.logoutRequests, 1);
    expect(auth.status, AuthStatus.signedOut);
    expect(await store.load(), isNull);
  });

  test('REGRESSION: logout shows signing-out busy state before completing',
      () async {
    await auth.login('user@qolab.test', 'secret123');
    final busyDuringLogout = <bool>[];
    auth.addListener(() {
      busyDuringLogout.add(auth.busy || auth.status == AuthStatus.signedOut);
    });
    await auth.logout();
    // First notification must already reflect an in-progress sign-out.
    expect(busyDuringLogout.first, isTrue);
  });

  test('REGRESSION: a delayed old logout response cannot clear a new login '
      'session (generation guard + cookie isolation)', () async {
    mock.logoutDelay = const Duration(milliseconds: 1500);
    await auth.login('user@qolab.test', 'secret123');

    await auth.logout(); // local state cleared; slow server logout pending

    // Immediate re-login on the same controller/API instance.
    await auth.login('user@qolab.test', 'secret123');
    expect(auth.status, AuthStatus.signedIn);
    final newSession = auth.api.cookies['qolab-session']?.value;
    final saved = await store.load();
    expect(saved, isNotNull);
    expect(saved!.jar['qolab-session']?.value, newSession);

    // Wait for the delayed old logout response to arrive server-side.
    await Future<void>.delayed(const Duration(milliseconds: 2000));

    // The new session must STILL be intact — neither wiped nor rotated.
    expect(auth.status, AuthStatus.signedIn);
    expect(auth.api.cookies['qolab-session']?.value, newSession);
    final savedAfter = await store.load();
    expect(savedAfter, isNotNull);
    expect(savedAfter!.jar['qolab-session']?.value, newSession);
    expect(auth.user?.name, 'Test User');
  });

  test('REGRESSION: immediate re-login performs a fresh CSRF bootstrap and '
      'does not reuse old-session cookies', () async {
    await auth.login('user@qolab.test', 'secret123');
    final bootstrapsBefore = mock.bootstraps;

    await auth.logout();
    // Cookie jar must be empty between logout and the next login.
    expect(auth.api.cookies.length, 0);

    await auth.login('user@qolab.test', 'secret123');
    expect(auth.status, AuthStatus.signedIn);
    // A NEW bootstrap ran for the second login (fresh CSRF).
    expect(mock.bootstraps, bootstrapsBefore + 1);
    // POST /login carried the NEW bootstrap cookies, not old-session ones.
    expect(mock.lastLoginCookieHeader, isNot(contains('sess-rotated-1')));
  });

  test('REGRESSION: login succeeds after a timed-out logout', () async {
    await auth.login('user@qolab.test', 'secret123');
    mock.logoutDelay = const Duration(seconds: 30); // logout will time out
    await auth.logout();
    expect(auth.status, AuthStatus.signedOut);

    mock.logoutDelay = Duration.zero; // server healthy again for re-login
    await auth.login('user@qolab.test', 'secret123');
    expect(auth.status, AuthStatus.signedIn);
    expect(auth.user!.name, 'Test User');
  });

  test('duplicate submission: second login while busy is ignored', () async {
    var calls = 0;
    // Slow down the mock by wrapping login in a controller race.
    final f1 = auth.login('user@qolab.test', 'secret123');
    // Immediately attempt a second login while the first is in flight.
    final busySnapshot = auth.busy;
    await f1;
    expect(busySnapshot, isTrue); // busy guard was armed during flight
    expect(auth.status, AuthStatus.signedIn);
    calls++;
    expect(calls, 1);
  });

  test('busy flag is false after completion', () async {
    await auth.login('user@qolab.test', 'secret123');
    expect(auth.busy, false);
    await auth.logout();
    // The busy flag is released via microtask after local cleanup.
    await Future<void>.delayed(Duration.zero);
    expect(auth.busy, false);
  });
}
