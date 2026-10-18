import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';
import 'package:qolab_app/auth_gate.dart';
import 'package:qolab_app/main.dart';

void main() {
  testWidgets('app boots to the auth gate with restoring splash',
      (tester) async {
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    await tester.pumpWidget(QolabApp(auth: auth));
    await tester.pump();
    // Before restore resolves we show the splash (or login) — never blank.
    expect(find.byType(Scaffold), findsOneWidget);
  });

  testWidgets('auth gate shows splash while restoring', (tester) async {
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    auth.status = AuthStatus.restoring;
    await tester.pumpWidget(
        MaterialApp(home: AuthGate(auth: auth)));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets('auth gate shows login when signed out', (tester) async {
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    auth.status = AuthStatus.signedOut;
    await tester.pumpWidget(
        MaterialApp(home: AuthGate(auth: auth)));
    expect(find.text('Sign in'), findsOneWidget);
  });

  testWidgets('REGRESSION: tapping Sign out shows signing-out feedback '
      'immediately and reaches Login', (tester) async {
    // Dead port: connection refused fails fast, exercising the local-only
    // sign-out path. (The slow-server case is covered by the controller
    // test with a hanging mock.)
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'), store: MemorySessionStore());
    auth.status = AuthStatus.signedIn;
    auth.user = const QolabUser(id: 42, name: 'Test User');

    await tester.pumpWidget(MaterialApp(home: AuthGate(auth: auth)));
    await tester.pump();

    // Home visible before logout.
    expect(find.text('Sign out'), findsOneWidget);

    await tester.tap(find.text('Sign out'));
    await tester.pump(); // first frame after the tap

    // Immediate: the sign-out button is gone at once — no second tap
    // possible, no lingering Home while the network is contacted. (The
    // busy spinner is covered by the controller test; in the widget zone
    // local cleanup resolves within microtasks so the first frame is
    // already the Login screen.)
    expect(find.text('Sign out'), findsNothing);

    await tester.pump(const Duration(milliseconds: 50));
    expect(auth.status, AuthStatus.signedOut);
    expect(find.text('Sign in'), findsOneWidget);
  });
}
