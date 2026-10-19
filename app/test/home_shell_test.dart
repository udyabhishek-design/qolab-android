import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';
import 'package:qolab_app/api/qolab_api.dart';
import 'package:qolab_app/auth/auth_controller.dart';
import 'package:qolab_app/auth/session_store.dart';
import 'package:qolab_app/auth_gate.dart';
import 'package:qolab_app/home/home_controller.dart';
import 'package:qolab_app/home/home_models.dart';
import 'package:qolab_app/screens/home_shell.dart';

import 'home_controller_test.dart' show employeeDashboardProps;

/// Parse the shared employee props fixture into DashboardData using the
/// REAL parser (no HTTP, no fake constructors).
DashboardData dashFromProps({
  List<Map<String, dynamic>>? meetings,
  List<Map<String, dynamic>>? rooms,
  List<Map<String, dynamic>>? tasks,
}) =>
    parseDashboard(InertiaPage(
      component: 'Dashboard/Index',
      props: employeeDashboardProps(
          meetings: meetings, rooms: rooms, tasks: tasks),
    ))!;

void main() {
  late AuthController auth;

  setUp(() {
    auth = AuthController(
        api: QolabApi(origin: 'http://127.0.0.1:9'),
        store: MemorySessionStore());
    // Bypass the network login path — widget tests can't do real HTTP in
    // the fake-async zone; state is seeded directly.
    auth.status = AuthStatus.signedIn;
    auth.user = const QolabUser(
        id: 42,
        name: 'Test User',
        email: 'user@qolab.test',
        jobTitle: 'Developer',
        departmentName: 'Engineering');
  });

  Widget wrap(Widget child) => MaterialApp(home: child);

  testWidgets('Home renders greeting; failed loads show retry, not blank',
      (tester) async {
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api)));
    await tester.pump();

    expect(find.text('Qolab'), findsOneWidget);
    expect(find.textContaining('Test'), findsWidgets); // greeting/name
    expect(find.text('Next Meeting'), findsOneWidget);
    expect(find.text('Sign out'), findsOneWidget);
    // Dead-port API (widget zone blocks real HTTP) → sections fail fast
    // with the error state — retry affordance visible, never blank.
    expect(find.text("Couldn't load this section"), findsWidgets);
    expect(find.text('Retry'), findsWidgets);
  });

  testWidgets('loaded state shows meeting, rooms, tasks', (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps());
    h.rooms.setLoaded(const RoomsData(departmentRoom: null));
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api, homeController: h)));
    await tester.pump();

    expect(find.text('Sprint Review'), findsOneWidget);
    expect(find.text('Social Rooms'), findsOneWidget);
    expect(find.text('The Lobby'), findsOneWidget);
    expect(find.text('Music Lounge'), findsOneWidget);
    expect(find.text('Continue Working'), findsOneWidget);
    expect(find.text('Fix login bug'), findsOneWidget);
  });

  testWidgets('empty meeting state shows a friendly message', (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps(meetings: []));
    h.rooms.setLoaded(const RoomsData(departmentRoom: null));
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api, homeController: h)));
    await tester.pump();
    expect(find.text('No meetings scheduled'), findsOneWidget);
  });

  testWidgets('caught-up Continue Working state', (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps(tasks: [], rooms: []));
    h.rooms.setLoaded(const RoomsData(departmentRoom: null));
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api, homeController: h)));
    await tester.pump();
    expect(find.text("You're all caught up"), findsOneWidget);
    expect(find.text('No Social Rooms available right now'),
        findsOneWidget);
  });

  testWidgets('Department Room card shows when authorized', (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps());
    h.rooms.setLoaded(const RoomsData(
        departmentRoom: HomeRoom(
            id: 50,
            name: 'Engineering Room',
            roomType: 'department',
            membersCount: 12)));
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api, homeController: h)));
    await tester.pump();
    expect(find.text('Department Room'), findsOneWidget);
    expect(find.text('Engineering Room'), findsOneWidget);
  });

  testWidgets('Department Room section hidden when user has no department',
      (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps());
    h.rooms.setLoaded(const RoomsData(departmentRoom: null));
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api, homeController: h)));
    await tester.pump();
    expect(find.text('Department Room'), findsNothing);
  });

  testWidgets('bottom navigation: Home | Chat | Ask Drytis, placeholders '
      'open and return, Home data preserved', (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps());
    h.rooms.setLoaded(const RoomsData(departmentRoom: null));
    await tester.pumpWidget(wrap(HomeShell(auth: auth, api: auth.api, homeController: h)));
    await tester.pump();

    // Chat placeholder
    await tester.tap(find.text('Chat'));
    await tester.pumpAndSettle();
    expect(find.text('Coming soon'), findsOneWidget);

    // Back to Home — data still there (IndexedStack keeps state).
    await tester.tap(find.text('Home'));
    await tester.pumpAndSettle();
    expect(find.text('Sprint Review'), findsOneWidget);

    // Ask Drytis placeholder
    await tester.tap(find.text('Ask Drytis'));
    await tester.pumpAndSettle();
    expect(find.text('Coming soon'), findsOneWidget);

    await tester.tap(find.text('Home'));
    await tester.pumpAndSettle();
    expect(find.text('Next Meeting'), findsOneWidget);
  });

  testWidgets('REGRESSION: logout stays single-tap and leaves Home',
      (tester) async {
    final h = HomeController(api: auth.api);
    h.dashboard.setLoaded(dashFromProps());
    h.rooms.setLoaded(const RoomsData(departmentRoom: null));
    await tester.pumpWidget(
        MaterialApp(home: AuthGate(auth: auth, api: auth.api)));
    await tester.pump();

    expect(find.text('Sign out'), findsOneWidget);
    await tester.tap(find.text('Sign out'));
    await tester.pump();
    // Sign-out button gone at once; signed out locally (dead-port API so
    // the background server logout fails harmlessly).
    expect(find.text('Sign out'), findsNothing);
    expect(auth.status, AuthStatus.signedOut);
  });
}
