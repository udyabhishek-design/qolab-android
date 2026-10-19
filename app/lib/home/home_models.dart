import '../api/inertia.dart';

/// Typed Home data models + parsers for the Qolab Inertia payloads.
///
/// Sources (verified against the Development Laravel backend):
/// - GET /dashboard  → DashboardController::index (employee: my_meetings,
///   priority_tasks, my_tasks, available_rooms; admin: live_meetings,
///   today_meetings, upcoming_meetings, live_rooms). All Inertia HTML.
/// - GET /rooms      → RoomsController::index (channels grouped by type;
///   group/department entries carry room metadata). Inertia HTML.
/// - GET /notifications/unread-count → JSON {unread_count}.
///
/// Parsers NEVER throw: missing/invalid fields become nulls; whole sections
/// become empty. Nothing is fabricated.
class HomeMeeting {
  const HomeMeeting({
    required this.id,
    required this.title,
    this.status,
    this.scheduledAt,
    this.meetingType,
    this.participantsCount,
  });

  final int id;
  final String title;
  final String? status; // scheduled | live | completed | cancelled …
  final DateTime? scheduledAt;
  final String? meetingType;
  final int? participantsCount;

  bool get isLive => status == 'live';
}

class HomeRoom {
  const HomeRoom({
    required this.id,
    required this.name,
    this.description,
    this.roomSlug,
    this.roomType,
    this.roomTheme, // lobby | music | coffee | null
    this.membersCount,
  });

  final int id;
  final String name;
  final String? description;
  final String? roomSlug;
  final String? roomType; // social | casual | department
  final String? roomTheme;
  final int? membersCount;

  bool get isSocial =>
      roomType == 'social' || roomType == 'casual';

  /// Accent for the distinct colored Social Room cards.
  int get themeColorValue {
    switch (roomTheme) {
      case 'music':
        return 0xFF5B4B8A; // muted violet — Music Lounge
      case 'coffee':
        return 0xFF8A5A2B; // warm brown — Coffee Lounge
      case 'lobby':
      default:
        return 0xFF8B1E2D; // Qolab wine — The Lobby
    }
  }
}

class HomeTask {
  const HomeTask({
    required this.id,
    required this.title,
    this.status,
    this.priority,
    this.dueDate, // YYYY-MM-DD string from the backend
  });

  final int id;
  final String title;
  final String? status;
  final String? priority;
  final String? dueDate;
}

/// Everything the Home screen needs from GET /dashboard.
class DashboardData {
  const DashboardData({
    required this.userId,
    required this.userName,
    this.departmentId,
    this.timezone,
    this.nextMeeting,
    this.socialRooms = const [],
    this.continueTasks = const [],
  });

  final int userId;
  final String userName;
  final int? departmentId;
  final String? timezone;
  final HomeMeeting? nextMeeting;
  final List<HomeRoom> socialRooms;
  final List<HomeTask> continueTasks;
}

/// Department Room from GET /rooms (channels.department[]).
class RoomsData {
  const RoomsData({this.departmentRoom});

  final HomeRoom? departmentRoom;
}

// ─────────────────────────────────────────────────────────────────────
// Parsers — pure functions, no widgets, no exceptions on bad input.
// ─────────────────────────────────────────────────────────────────────

/// Parse the dashboard Inertia page into [DashboardData]. Role-dependent
/// props are all optional: employee uses my_meetings/priority_tasks/
/// available_rooms, admin uses live_meetings/upcoming_meetings/live_rooms.
DashboardData? parseDashboard(InertiaPage? page) {
  if (page == null) return null;
  final props = page.props;

  final authUser = page.authUser;
  if (authUser == null) return null;
  // department_id / timezone live on the raw auth.user map.
  final authMap = _asMap(props['auth']);
  final userMap = _asMap(authMap?['user']);
  final departmentId = _asInt(userMap?['department_id']);
  final timezone = _asString(userMap?['timezone']);

  // Next meeting — candidates from every prop that exists for the role.
  // Prefer a LIVE meeting; otherwise the earliest scheduled non-cancelled.
  final candidates = <Map<String, dynamic>>[];
  _collect(_asMapList(props['live_meetings']), candidates);
  _collect(_asMapList(props['my_meetings']), candidates);
  _collect(_asMapList(props['today_meetings']), candidates);
  _collect(_asMapList(props['upcoming_meetings']), candidates);
  final meetings =
      candidates.map(_parseMeeting).whereType<HomeMeeting>().toList();
  final nextMeeting = _pickNextMeeting(meetings);

  // Social Rooms — employee prop first, admin prop as fallback.
  var roomList = _asMapList(props['available_rooms']);
  roomList ??= _asMapList(props['live_rooms']);
  final socialRooms = (roomList ?? const [])
      .map(_parseRoom)
      .whereType<HomeRoom>()
      .where((r) => r.isSocial)
      .toList();

  // Continue Working — priority_tasks first, my_tasks as fallback.
  var taskList = _asMapList(props['priority_tasks']);
  var tasks = (taskList ?? const [])
      .map(_parseTask)
      .whereType<HomeTask>()
      .toList();
  if (tasks.isEmpty) {
    taskList = _asMapList(props['my_tasks']);
    tasks = (taskList ?? const [])
        .map(_parseTask)
        .whereType<HomeTask>()
        .take(3)
        .toList();
  }

  return DashboardData(
    userId: authUser.id,
    userName: authUser.name,
    departmentId: departmentId,
    timezone: timezone,
    nextMeeting: nextMeeting,
    socialRooms: socialRooms,
    continueTasks: tasks,
  );
}

/// Parse the rooms Inertia page: channels.department[] matched against the
/// user's department id. Admins / users without a department get null.
RoomsData? parseRooms(InertiaPage? page, int? userDepartmentId) {
  if (page == null) return null;
  final channels = _asMap(page.props['channels']);
  if (channels == null) return null;

  final departments = _asMapList(channels['department']) ?? const [];
  for (final raw in departments) {
    final room = _parseRoom(raw);
    if (room == null) continue;
    final deptId = _asInt(raw['department_id']);
    // Exact match when the user has a department; otherwise no match —
    // never fabricate a Department Room for a department-less user.
    if (userDepartmentId != null && deptId == userDepartmentId) {
      return RoomsData(departmentRoom: room);
    }
  }
  return const RoomsData(departmentRoom: null);
}

// ─────────────────────────────────────────────────────────────────────
// Internals
// ─────────────────────────────────────────────────────────────────────

HomeMeeting? _pickNextMeeting(List<HomeMeeting> meetings) {
  final live =
      meetings.where((m) => m.isLive).toList();
  if (live.isNotEmpty) {
    live.sort((a, b) =>
        (a.scheduledAt ?? DateTime(9999))
            .compareTo(b.scheduledAt ?? DateTime(9999)));
    return live.first;
  }
  final upcoming = meetings
      .where((m) => m.status != 'cancelled' && m.scheduledAt != null)
      .toList();
  upcoming.sort((a, b) => a.scheduledAt!.compareTo(b.scheduledAt!));
  if (upcoming.isEmpty) {
    // No dated meetings — fall back to any non-cancelled entry (title-only).
    final any = meetings.where((m) => m.status != 'cancelled').toList();
    return any.isEmpty ? null : any.first;
  }
  return upcoming.first;
}

HomeMeeting? _parseMeeting(Map<String, dynamic>? raw) {
  if (raw == null) return null;
  final id = _asInt(raw['id']);
  final title = _asString(raw['title']);
  if (id == null || title == null) return null;
  return HomeMeeting(
    id: id,
    title: title,
    status: _asString(raw['status']),
    scheduledAt: _asDate(raw['scheduled_at']),
    meetingType: _asString(raw['meeting_type']),
    participantsCount: _asInt(raw['participants_count']),
  );
}

HomeRoom? _parseRoom(Map<String, dynamic>? raw) {
  if (raw == null) return null;
  final id = _asInt(raw['id']);
  final name = _asString(raw['name']);
  if (id == null || name == null) return null;
  return HomeRoom(
    id: id,
    name: name,
    description: _asString(raw['description']),
    roomSlug: _asString(raw['room_slug']),
    roomType: _asString(raw['room_type']),
    roomTheme: _asString(raw['room_theme']),
    membersCount: _asInt(raw['members_count']),
  );
}

HomeTask? _parseTask(Map<String, dynamic>? raw) {
  if (raw == null) return null;
  final id = _asInt(raw['id']);
  final title = _asString(raw['title']);
  if (id == null || title == null) return null;
  return HomeTask(
    id: id,
    title: title,
    status: _asString(raw['status']),
    priority: _asString(raw['priority']),
    dueDate: _asString(raw['due_date']),
  );
}

void _collect(List<Map<String, dynamic>>? src,
    List<Map<String, dynamic>> dst) {
  if (src != null) dst.addAll(src);
}

Map<String, dynamic>? _asMap(Object? v) =>
    v is Map<String, dynamic> ? v : null;

List<Map<String, dynamic>>? _asMapList(Object? v) {
  if (v is! List) return null;
  return v.whereType<Map<String, dynamic>>().toList();
}

int? _asInt(Object? v) {
  if (v is int) return v;
  if (v is num) return v.toInt();
  return int.tryParse(v?.toString() ?? '');
}

String? _asString(Object? v) =>
    v is String && v.isNotEmpty ? v : null;

DateTime? _asDate(Object? v) =>
    v is String ? DateTime.tryParse(v) : null;
