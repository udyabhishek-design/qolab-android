import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';
import 'package:qolab_app/chat/chat_models.dart';

/// Fixtures mirror the verified Development backend shapes
/// (ChannelController::index / show, MessageController::index / store).
Map<String, dynamic> chatIndexProps({
  List<Map> dm = const [],
  List<Map> groups = const [],
  List<Map> company = const [],
  List<Map> department = const [],
  List<Map> employees = const [],
}) {
  return {
    'channels': {
      'dm': dm,
      'group': groups,
      'company': company,
      'department': department,
    },
    'available_users': employees,
    'employees': employees,
    'filters': {'search': null},
  };
}

Map<String, dynamic> dmConversation({
  int id = 11,
  String name = 'Priya Sharma',
  String? lastMessageAt = '2026-10-19T10:00:00.000000Z',
  int? unread = 2,
  Map<String, dynamic>? partner,
}) =>
    {
      'id': id,
      'name': name,
      'type': 'dm',
      'description': null,
      'avatar_path': null,
      'is_official': false,
      'is_room': false,
      'room_type': null,
      'room_slug': null,
      'room_theme': null,
      'cover_path': null,
      'visibility': null,
      'status': 'active',
      'department_id': null,
      'department_name': null,
      'last_message_at': lastMessageAt,
      'members_count': 2,
      'is_member': true,
      'dm_partner': partner ??
          {
            'id': 7,
            'name': name,
            'avatar_path': null,
            'job_title': 'Designer',
            'department_name': 'Design',
          },
      'unread_count': unread,
    };

Map<String, dynamic> groupConversation({
  int id = 21,
  String name = 'Launch Squad',
  String type = 'group',
  int members = 5,
  int? unread = 0,
}) =>
    {
      'id': id,
      'name': name,
      'type': type,
      'description': 'Launch coordination',
      'avatar_path': null,
      'is_official': false,
      'is_room': false,
      'room_type': null,
      'room_slug': null,
      'room_theme': null,
      'cover_path': null,
      'visibility': null,
      'status': 'active',
      'department_id': 3,
      'department_name': 'Marketing',
      'last_message_at': '2026-10-18T09:30:00.000000Z',
      'members_count': members,
      'is_member': true,
      'dm_partner': null,
      'unread_count': unread,
    };

Map<String, dynamic> messageJson({
  int id = 101,
  int channelId = 11,
  int userId = 7,
  String body = 'Morning!',
  String createdAt = '2026-10-19T10:00:00.000000Z',
}) =>
    {
      'id': id,
      'channel_id': channelId,
      'user_id': userId,
      'body': body,
      'type': 'text',
      'parent_message_id': null,
      'parent': null,
      'edited_at': null,
      'created_at': createdAt,
      'user': {
        'id': userId,
        'name': userId == 7 ? 'Priya Sharma' : 'Test User',
        'avatar_path': null,
        'job_title': 'Designer',
        'department': {'id': 2, 'name': 'Design'},
        'shift': 'day',
        'timezone': 'UTC',
      },
      'attachments': [],
      'replies': [],
    };

InertiaPage pageWith(Map<String, dynamic> props) =>
    InertiaPage(component: 'Chat/Index', props: props);

void main() {
  group('ChatIndex parsing', () {
    test('parses DMs, groups, company and department channels', () {
      final data = ChatIndex.fromPage(pageWith(chatIndexProps(
        dm: [dmConversation()],
        groups: [groupConversation()],
        company: [groupConversation(id: 31, name: 'All Company', type: 'company')],
        department: [
          groupConversation(id: 41, name: 'Engineering', type: 'department')
        ],
        employees: [
          {
            'id': 7,
            'name': 'Priya Sharma',
            'avatar_path': null,
            'job_title': 'Designer',
            'department_name': 'Design',
          }
        ],
      )));

      expect(data.direct, hasLength(1));
      expect(data.groups, hasLength(3)); // group + company + department
      expect(data.direct.first.name, 'Priya Sharma');
      expect(data.direct.first.isDm, isTrue);
      expect(data.direct.first.dmPartner?.jobTitle, 'Designer');
      expect(data.groups.map((c) => c.type).toSet(),
          {'group', 'company', 'department'});
      expect(data.employees, hasLength(1));
      expect(data.employees.first.id, 7);
    });

    test('missing/invalid entries are skipped, never fabricated', () {
      final data = ChatIndex.fromPage(pageWith(chatIndexProps(
        dm: [
          dmConversation(),
          {'id': 'x'}, // invalid
        ],
      )));
      expect(data.direct, hasLength(1));
      expect(data.groups, isEmpty);
    });

    test('empty page → empty lists', () {
      final data = ChatIndex.fromPage(pageWith(chatIndexProps()));
      expect(data.direct, isEmpty);
      expect(data.groups, isEmpty);
      expect(data.employees, isEmpty);
    });
  });

  group('ChannelDetail parsing', () {
    test('parses channel with my_role, members_count, unread', () {
      final props = {
        'channel': {
          ...dmConversation(unread: 0),
          'members': [],
          'my_role': 'member',
          'created_at': '2026-06-01T00:00:00.000000Z',
        },
        'channels': const {},
        'employees': const [],
      };
      final d = ChannelDetail.fromPage(pageWith(props));
      expect(d, isNotNull);
      expect(d!.conversation.id, 11);
      expect(d.conversation.name, 'Priya Sharma');
      expect(d.myRole, 'member');
      expect(d.unreadCount, 0);
      expect(d.createdAt, isNotNull);
    });

    test('missing channel prop → null (never fabricated)', () {
      expect(ChannelDetail.fromPage(pageWith({})), isNull);
    });
  });

  group('Message parsing', () {
    test('parses message with sender identity', () {
      final m = ChatMessage.fromJson(messageJson())!;
      expect(m.id, 101);
      expect(m.channelId, 11);
      expect(m.userId, 7);
      expect(m.body, 'Morning!');
      expect(m.senderName, 'Priya Sharma');
      expect(m.isEdited, isFalse);
    });

    test('edited flag reflects edited_at', () {
      final m = ChatMessage.fromJson(
          {...messageJson(), 'edited_at': '2026-10-19T11:00:00.000000Z'})!;
      expect(m.isEdited, isTrue);
    });

    test('invalid messages → null', () {
      expect(ChatMessage.fromJson(null), isNull);
      expect(ChatMessage.fromJson('x'), isNull);
      expect(ChatMessage.fromJson({'id': 1}), isNull); // no created_at
    });
  });

  group('MessagesPage parsing', () {
    test('parses data + meta; endpoint order is newest-first', () {
      final older = messageJson(id: 100, createdAt: '2026-10-19T09:00:00.000000Z');
      final newer = messageJson(id: 101);
      final pageData = MessagesPage.fromJson({
        'data': [newer, older], // newest first (backend order)
        'meta': {
          'current_page': 1,
          'last_page': 3,
          'per_page': 50,
          'total': 130,
        },
      });
      expect(pageData.messages, hasLength(2));
      expect(pageData.currentPage, 1);
      expect(pageData.lastPage, 3);
      expect(pageData.total, 130);
      // chronological reverses to reading order
      expect(pageData.chronological.first.id, 100);
      expect(pageData.chronological.last.id, 101);
    });

    test('garbage JSON → empty page, no throw', () {
      final p = MessagesPage.fromJson('nonsense');
      expect(p.messages, isEmpty);
      expect(p.lastPage, 1);
    });
  });

  group('Conversation helpers', () {
    test('isGroupLike / isDm classification', () {
      expect(dmConversation()['type'], 'dm');
      final g = Conversation.fromJson(groupConversation())!;
      expect(g.isGroupLike, isTrue);
      expect(g.isDm, isFalse);
      final d = Conversation.fromJson(dmConversation())!;
      expect(d.isDm, isTrue);
      expect(d.isGroupLike, isFalse);
    });

    test('unread_count null is preserved as null (never fabricated)', () {
      final c = Conversation.fromJson(dmConversation(unread: null))!;
      expect(c.unreadCount, isNull);
    });
  });
}
