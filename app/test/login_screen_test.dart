import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/api_errors.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';
import 'package:qolab_app/screens/login_screen.dart';
import 'package:qolab_app/theme.dart';

void main() {
  Widget wrap(Widget child) => MaterialApp(
        theme: ThemeData(scaffoldBackgroundColor: QolabTheme.canvas),
        home: child,
      );

  testWidgets('empty form shows required-field validation',
      (tester) async {
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    await tester.pumpWidget(wrap(LoginScreen(auth: auth)));
    await tester.tap(find.text('Sign in'));
    await tester.pump();
    expect(find.text('Email is required.'), findsOneWidget);
    expect(find.text('Password is required.'), findsOneWidget);
  });

  testWidgets('invalid email format is rejected', (tester) async {
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    await tester.pumpWidget(wrap(LoginScreen(auth: auth)));
    await tester.enterText(
        find.byType(TextFormField).at(0), 'not-an-email');
    await tester.enterText(find.byType(TextFormField).at(1), 'pw123');
    await tester.tap(find.text('Sign in'));
    await tester.pump();
    expect(find.text('Enter a valid email address.'), findsOneWidget);
  });

  testWidgets('show/hide password toggle works', (tester) async {
    final auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    await tester.pumpWidget(wrap(LoginScreen(auth: auth)));
    await tester.enterText(find.byType(TextFormField).at(1), 'secret');
    final field = tester.widget<TextField>(find.descendant(
        of: find.byType(TextFormField).at(1),
        matching: find.byType(TextField)).first);
    expect(field.obscureText, true);
    await tester.tap(find.byIcon(Icons.visibility_outlined));
    await tester.pump();
    final field2 = tester.widget<TextField>(find.descendant(
        of: find.byType(TextFormField).at(1),
        matching: find.byType(TextField)).first);
    expect(field2.obscureText, false);
  });

  testWidgets('in-flight request disables the button and shows a spinner',
      (tester) async {
    final controller = NeverLoginController();
    await tester.pumpWidget(wrap(LoginScreen(auth: controller)));
    await tester.enterText(
        find.byType(TextFormField).at(0), 'a@b.co');
    await tester.enterText(find.byType(TextFormField).at(1), 'password');
    await tester.tap(find.text('Sign in'));
    await tester.pump();
    expect(controller.loginCalled, 1);
    // Button now shows progress and is disabled.
    expect(controller.status, AuthStatus.signingIn);
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    final button = tester.widget<ElevatedButton>(
        find.byType(ElevatedButton).first);
    expect(button.onPressed, isNull);
    // Tapping again must NOT trigger another login.
    await tester.tap(find.byType(ElevatedButton).first, warnIfMissed: false);
    await tester.pump(const Duration(milliseconds: 100));
    expect(controller.loginCalled, 1);
  });

  testWidgets('credential error shows a banner, not a raw exception',
      (tester) async {
    final controller = FailLoginController();
    await tester.pumpWidget(wrap(LoginScreen(auth: controller)));
    await tester.enterText(
        find.byType(TextFormField).at(0), 'a@b.co');
    await tester.enterText(find.byType(TextFormField).at(1), 'badpass');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    expect(
        find.text('These credentials do not match our records.'),
        findsOneWidget);
  });
}

/// Controller whose login never resolves — lets us observe the busy state.
class NeverLoginController extends AuthController {
  NeverLoginController()
      : super(
            api: QolabApi(origin: 'http://127.0.0.1:9'),
            store: MemorySessionStore());

  int loginCalled = 0;

  @override
  Future<void> login(String email, String password) {
    loginCalled++;
    status = AuthStatus.signingIn;
    notifyListeners();
    return ComplepletedNever().future;
  }
}

class ComplepletedNever {
  final Completer<void> _c = Completer<void>();
  Future<void> get future => _c.future;
}

class FailLoginController extends AuthController {
  FailLoginController()
      : super(
            api: QolabApi(origin: 'http://127.0.0.1:9'),
            store: MemorySessionStore());

  @override
  Future<void> login(String email, String password) async {
    status = AuthStatus.signingIn;
    notifyListeners();
    await Future<void>.delayed(Duration.zero);
    status = AuthStatus.signedOut;
    notifyListeners();
    throw const ApiException(
        ApiErrorKind.validation,
        'These credentials do not match our records.');
  }
}
