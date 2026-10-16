import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
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
}
