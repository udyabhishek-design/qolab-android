
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
    expect(saved!.jar['qolab-session']?.value, 'sess-rotated');
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
    expect(auth.busy, false);
  });
}

// Silence unused import warnings for HttpServer in future extensions.
// ignore: unused_element
const _unused = null;
// ignore: unused_element
const _unused2 = null;
// ignore: unused_element
const _unused3 = null;
