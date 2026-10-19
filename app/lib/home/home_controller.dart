import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/api_errors.dart';
import '../api/inertia.dart';
import '../api/qolab_api.dart';
import 'home_models.dart';

enum SectionState { loading, loaded, empty, error }

/// One section's lifecycle: loading → loaded/empty, or error with retry.
/// Preserves last-good data across refresh failures.
class Section<T> {
  Section(this._value);
  T? _value;
  SectionState state = SectionState.loading;
  bool hasData = false;

  T? get value => _value;
  void setLoaded(T? v) {
    _value = v;
    hasData = v != null;
    state = v == null ? SectionState.empty : SectionState.loaded;
  }

  /// Refresh failed: keep whatever was loaded before; only first failure
  /// (no cached data) shows an error state.
  void setFailed() {
    if (!hasData) {
      state = SectionState.error;
    }
  }
}

/// Dedicated Home data controller — separate from [AuthController]; does
/// NOT maintain its own auth state machine. Session expiry is reported up
/// via [onSessionExpired] for the existing auth flow to handle.
class HomeController extends ChangeNotifier {
  HomeController({
    required this.api,
    this.onSessionExpired,
  });

  final QolabApi api;
  final void Function()? onSessionExpired;

  final Section<DashboardData> dashboard = Section<DashboardData>(null);
  final Section<RoomsData> rooms = Section<RoomsData>(null);
  int? unreadCount; // null = unknown / unavailable
  bool _refreshing = false;
  int _generation = 0;

  bool get refreshing => _refreshing;

  /// Parallel fetch of /dashboard + /rooms; unread count runs
  /// independently and never blocks the primary sections. Each section
  /// updates listeners as soon as it resolves — no all-or-nothing gate.
  Future<void> refresh() async {
    if (_refreshing) return; // no overlapping refreshes
    _refreshing = true;
    final gen = ++_generation;
    dashboard.state =
        dashboard.hasData ? dashboard.state : SectionState.loading;
    rooms.state = rooms.hasData ? rooms.state : SectionState.loading;
    notifyListeners();

    _loadUnread(gen); // fire-and-forget

    await Future.wait([
      _loadDashboard(gen),
      _loadRooms(gen),
    ]);

    if (gen != _generation) return;
    _refreshing = false;
    notifyListeners();
  }

  /// Retry a single failed section.
  Future<void> retrySection(String key) async {
    final gen = ++_generation;
    _refreshing = false;
    switch (key) {
      case 'dashboard':
        dashboard.state = SectionState.loading;
        notifyListeners();
        await _loadDashboard(gen);
      case 'rooms':
        rooms.state = SectionState.loading;
        notifyListeners();
        await _loadRooms(gen);
    }
    notifyListeners();
  }

  Future<void> _loadDashboard(int gen) async {
    try {
      final html = await api.fetchDashboardHtml();
      if (gen != _generation) return;
      dashboard.setLoaded(parseDashboard(InertiaPage.parse(html)));
      // The Department Room match needs the user's department id — if the
      // rooms page arrived first, re-derive it now from the parked HTML.
      _rederiveDepartmentRoom(gen);
    } on ApiException catch (e) {
      if (gen != _generation) return;
      if (e.kind == ApiErrorKind.unauthenticated) {
        _sessionExpired();
        return;
      }
      dashboard.setFailed();
    } catch (_) {
      if (gen != _generation) return;
      dashboard.setFailed();
    }
    notifyListeners();
  }

  String? _roomsHtml; // parked rooms HTML for the dept re-derive

  void _rederiveDepartmentRoom(int gen) {
    if (gen != _generation) return;
    final deptId = dashboard.value?.departmentId;
    final html = _roomsHtml;
    if (html == null || deptId == null) return;
    final roomsData = parseRooms(InertiaPage.parse(html), deptId);
    if (roomsData?.departmentRoom != null) {
      rooms.setLoaded(roomsData);
    }
  }

  Future<void> _loadRooms(int gen) async {
    try {
      final html = await api.fetchRoomsHtml();
      if (gen != _generation) return;
      _roomsHtml = html; // park for the dashboard-arrives-later re-derive
      rooms.setLoaded(
          parseRooms(InertiaPage.parse(html), dashboard.value?.departmentId));
    } on ApiException catch (e) {
      if (gen != _generation) return;
      if (e.kind == ApiErrorKind.unauthenticated) {
        _sessionExpired();
        return;
      }
      rooms.setFailed();
    } catch (_) {
      if (gen != _generation) return;
      rooms.setFailed();
    }
    notifyListeners();
  }

  Future<void> _loadUnread(int gen) async {
    try {
      final n = await api.fetchUnreadCount();
      if (gen != _generation) return; // stale — drop
      unreadCount = n;
    } catch (_) {
      if (gen != _generation) return;
      unreadCount = null; // unknown — badge hidden
    }
    notifyListeners();
  }

  void _sessionExpired() {
    _generation++; // invalidate everything in flight
    _refreshing = false;
    onSessionExpired?.call();
  }

  @override
  void dispose() {
    _generation++; // pending completions become no-ops
    super.dispose();
  }
}
