import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';
import 'package:qolab_app/home/home_models.dart';

/// Builds an Inertia data-page HTML document around [props].
String dashboardHtml(Map<String, dynamic> props,
    {Map<String, dynamic> auth = const {}}) {
  final page = {
    'component': 'Dashboard/Index',
    'props': {'auth': auth, ...props},
    'url': '/dashboard',
    'version': 'test',
  };
  return '<html><body><div id="app"></div>'
      '<script id="page" data-page="test" type="application/json">'
      '${jsonEncode(page)}</script></body></html>';
}

String roomsHtml(Map<String, dynamic> channels) {
  final page = {
    'component': 'Rooms/Index',
    'props': {'channels': channels},
    'url': '/rooms',
    'version': 'test',
  };
  return '<html><body><div id="app"></div>'
      '<script id="page" data-page="test" type="application/json">'
      '${jsonEncode(page)}</script></body></html>';
}

const employeeAuth = {
  'user': {
    'id': 42,
    'name': 'Test User',
    'email': 'user@qolab.test',
    'system_role': 'member',
    'timezone': 'UTC',
    'department_id': 7,
  },
  'permissions': [],
};

void main() {
  group('parseDashboard — employee', () {
    test('parses next meeting from my_meetings', () {
      final html = dashboardHtml({
        'my_meetings': [
          {
            'id': 11,
            'title': 'Sprint Review',
            'status': 'scheduled',
            'scheduled_at': '2026-10-20T10:00:00+00:00',
            'meeting_type': 'group',
            'participants_count': 4,
          }
        ],
        'priority_tasks': [
          {
            'id': 1,
            'title': 'Fix login bug',
            'status': 'in-progress',
            'priority': 'urgent',
            'due_date': '2026-10-19',
          }
        ],
        'available_rooms': [
          {
            'id': 1,
            'name': 'The Lobby',
            'room_slug': 'the-lobby',
            'room_type': 'social',
            'room_theme': 'lobby',
            'members_count': 23,
          },
          {
            'id': 2,
            'name': 'Music Lounge',
            'room_type': 'social',
            'room_theme': 'music',
            'members_count': 5,
          },
          {
            'id': 9,
            'name': 'Engineering',
            'room_type': 'department',
            'members_count': 9,
          },
        ],
      }, auth: employeeAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.userId, 42);
      expect(d.departmentId, 7);
      expect(d.timezone, 'UTC');
      expect(d.nextMeeting!.id, 11);
      expect(d.nextMeeting!.title, 'Sprint Review');
      expect(d.nextMeeting!.participantsCount, 4);
      // Department room entries in available_rooms are NOT social rooms.
      expect(d.socialRooms.length, 2);
      expect(d.socialRooms.first.name, 'The Lobby');
      expect(d.continueTasks.single.title, 'Fix login bug');
      expect(d.continueTasks.single.dueDate, '2026-10-19');
    });

    test('cancelled meetings are excluded from next-meeting selection',
        () {
      final html = dashboardHtml({
        'my_meetings': [
          {
            'id': 11,
            'title': 'CANCELLED EARLIER',
            'status': 'cancelled',
            'scheduled_at': '2026-10-19T08:00:00+00:00',
          },
          {
            'id': 12,
            'title': 'ACTIVE LATER',
            'status': 'scheduled',
            'scheduled_at': '2026-10-21T09:00:00+00:00',
          }
        ],
      }, auth: employeeAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.nextMeeting!.id, 12);
    });

    test('a live meeting is preferred over an earlier scheduled one', () {
      final html = dashboardHtml({
        'my_meetings': [
          {
            'id': 20,
            'title': 'Scheduled Early',
            'status': 'scheduled',
            'scheduled_at': '2026-10-19T08:00:00+00:00',
          },
          {
            'id': 21,
            'title': 'Live Now',
            'status': 'live',
            'scheduled_at': '2026-10-19T12:00:00+00:00',
          }
        ],
      }, auth: employeeAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.nextMeeting!.id, 21);
      expect(d.nextMeeting!.isLive, isTrue);
    });

    test('empty meeting collections → null next meeting', () {
      final html = dashboardHtml({
        'my_meetings': [],
        'today_meetings': [],
        'upcoming_meetings': [],
      }, auth: employeeAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.nextMeeting, isNull);
      expect(d.socialRooms, isEmpty);
      expect(d.continueTasks, isEmpty);
    });

    test('missing optional props entirely (minimal page)', () {
      final html = dashboardHtml({}, auth: employeeAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.nextMeeting, isNull);
      expect(d.socialRooms, isEmpty);
      expect(d.continueTasks, isEmpty);
    });

    test('missing auth.user → null (not a crash)', () {
      final html = dashboardHtml({}, auth: {});
      expect(parseDashboard(InertiaPage.parse(html)), isNull);
    });

    test('invalid scheduled_at is tolerated (null date, title kept)', () {
      final html = dashboardHtml({
        'my_meetings': [
          {'id': 1, 'title': 'Odd Date', 'status': 'scheduled',
           'scheduled_at': 'not-a-date'}
        ],
      }, auth: employeeAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.nextMeeting!.title, 'Odd Date');
      expect(d.nextMeeting!.scheduledAt, isNull);
    });
  });

  group('parseDashboard — admin', () {
    const adminAuth = {
      'user': {
        'id': 1,
        'name': 'Admin User',
        'system_role': 'admin',
        'timezone': 'UTC',
        'department_id': null,
      },
      'permissions': [],
    };

    test('parses live_meetings + live_rooms; no department id', () {
      final html = dashboardHtml({
        'live_meetings': [
          {
            'id': 30,
            'title': 'All Hands',
            'status': 'live',
            'scheduled_at': '2026-10-19T13:00:00+00:00',
            'participants_count': 12,
          }
        ],
        'today_meetings': [
          {
            'id': 31,
            'title': 'Later Today',
            'status': 'scheduled',
            'scheduled_at': '2026-10-19T18:00:00+00:00',
          }
        ],
        'live_rooms': [
          {
            'id': 3,
            'name': 'Coffee Lounge',
            'room_type': 'casual',
            'room_theme': 'coffee',
            'members_count': 4,
          }
        ],
      }, auth: adminAuth);
      final d = parseDashboard(InertiaPage.parse(html))!;
      expect(d.departmentId, isNull); // admin: no department
      expect(d.nextMeeting!.id, 30); // live preferred
      expect(d.nextMeeting!.isLive, isTrue);
      expect(d.socialRooms.single.name, 'Coffee Lounge');
      expect(d.socialRooms.single.themeColorValue, 0xFF8A5A2B);
    });
  });

  group('parseRooms — department matching', () {
    test('matches the user department among channels.department[]', () {
      final html = roomsHtml({
        'department': [
          {
            'id': 50,
            'name': 'Engineering Room',
            'room_type': 'department',
            'room_slug': 'engineering',
            'members_count': 12,
            'department_id': 7,
          },
          {
            'id': 51,
            'name': 'Design Room',
            'room_type': 'department',
            'members_count': 6,
            'department_id': 8,
          },
        ],
        'group': [
          {
            'id': 60,
            'name': 'The Lobby',
            'room_type': 'social',
            'department_id': null,
          }
        ],
      });
      final r = parseRooms(InertiaPage.parse(html), 7)!;
      expect(r.departmentRoom!.id, 50);
      expect(r.departmentRoom!.name, 'Engineering Room');
    });

    test('user with no department → no room (never fabricated)', () {
      final html = roomsHtml({
        'department': [
          {
            'id': 50,
            'name': 'Engineering Room',
            'room_type': 'department',
            'department_id': 7,
          }
        ],
      });
      final r = parseRooms(InertiaPage.parse(html), null)!;
      expect(r.departmentRoom, isNull);
    });

    test('no matching department → null room', () {
      final html = roomsHtml({
        'department': [
          {
            'id': 51,
            'name': 'Design Room',
            'room_type': 'department',
            'department_id': 8,
          }
        ],
      });
      final r = parseRooms(InertiaPage.parse(html), 7)!;
      expect(r.departmentRoom, isNull);
    });
  });

  group('unread-count payload', () {
    test('valid and invalid JSON shapes', () {
      // Pure decode check of the contract the controller asserts.
      Map<String, dynamic> ok = jsonDecode('{"unread_count": 4}');
      expect(ok['unread_count'], 4);
      Map<String, dynamic> zero = jsonDecode('{"unread_count": 0}');
      expect(zero['unread_count'], 0);
      expect(() => jsonDecode('not json'), throwsFormatException);
    });
  });

  group('null-tolerance', () {
    test('parseDashboard(null) and parseRooms(null) are null-safe', () {
      expect(parseDashboard(null), isNull);
      expect(parseRooms(null, 7), isNull);
    });
  });
}
