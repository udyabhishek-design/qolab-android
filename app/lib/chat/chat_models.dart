import '../api/inertia.dart';

/// Typed Chat models + parsers for the Qolab Development backend payloads.
///
/// Sources (verified against Laravel source, Development):
/// - GET /chat  → ChannelController::index — Inertia HTML with props
///   channels map with company/department/group/dm lists, plus
///    available_users / employees lists and filters.search.
///   Channel item: id/name/type/description/avatar_path/is_official/is_room/
///   room_type/room_slug/room_theme/cover_path/visibility/status, plus
///   department_id/department_name/last_message_at/members_count/is_member/
///   dm_partner(User|null)/unread_count. DM names are pre-resolved to the
///   partner's display name by the backend.
/// - GET /chat/channels/{channel} → ChannelController::show — Inertia HTML
///   a channel object (id/name/type/description/avatar_path/is_official,
///   department/creator/members/members_count/my_role/dm_partner,
///   unread_count/created_at), plus grouped channels and employees.
/// - GET /chat/channels/{channel}/messages → MessageController::index —
///   JSON with a data list of formatted messages and meta pagination
///   (current_page/last_page/per_page/total). Messages are NEWEST-FIRST.
///   Message fields: id/channel_id/user_id/body/type/parent_message_id,
///   parent/edited_at/created_at, a user object with id/name/avatar_path,
///   job_title, department, shift, timezone; attachments and replies lists.
/// - POST /chat/channels/{channel}/messages {body: string ≤10000} → 201
///   {message: formatMessage}. Empty body+no attachments → 422.
/// - POST /chat/dm/{user} → 302 redirect to /chat/{channel} (web route
///   name chat.show) or back()->with('error') on restriction.
/// - GET /chat/unread-counts → JSON with a data map of channel id to count.
/// - PATCH /chat/channels/{channel}/read with last_read_message_id →
///   success true; message must belong to the channel.
///
/// Parsers NEVER throw: missing/invalid fields become nulls; sections become
/// empty. Nothing is fabricated.

// ─────────────────────────────────────────────────────────────────────
// Users (chat directory entries)
// ─────────────────────────────────────────────────────────────────────

class ChatUser {
  const ChatUser({
    required this.id,
    required this.name,
    this.avatarPath,
    this.jobTitle,
    this.departmentName,
  });

  final int id;
  final String name;
  final String? avatarPath;
  final String? jobTitle;
  final String? departmentName;

  static ChatUser? fromJson(Object? v) {
    if (v is! Map) return null;
    final id = v['id'];
    final name = v['name'];
    if (name is! String || name.isEmpty) return null;
    return ChatUser(
      id: id is int ? id : int.tryParse(id?.toString() ?? '') ?? 0,
      name: name,
      avatarPath: _str(v['avatar_path']),
      jobTitle: _str(v['job_title']),
      departmentName: _str(v['department_name']),
    );
  }

  static String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;
}

// ─────────────────────────────────────────────────────────────────────
// Conversations
// ─────────────────────────────────────────────────────────────────────

/// A chat conversation = any non-room channel: dm | group | company |
/// department messaging channel.
class Conversation {
  const Conversation({
    required this.id,
    required this.name,
    required this.type,
    this.description,
    this.avatarPath,
    this.lastMessageAt,
    this.membersCount,
    this.unreadCount,
    this.dmPartner,
    this.isOfficial = false,
  });

  final int id;
  final String name;
  final String type; // dm | group | company | department
  final String? description;
  final String? avatarPath;
  final DateTime? lastMessageAt;
  final int? membersCount;
  final int? unreadCount; // null = unknown, never fabricated
  final ChatUser? dmPartner;

  final bool isOfficial;

  bool get isDm => type == 'dm';
  bool get isGroupLike =>
      type == 'group' || type == 'company' || type == 'department';

  static Conversation? fromJson(Object? v) {
    if (v is! Map) return null;
    final id = v['id'];
    final name = v['name'];
    final type = v['type'];
    if (name is! String || name.isEmpty || type is! String) return null;
    return Conversation(
      id: id is int ? id : int.tryParse(id?.toString() ?? '') ?? 0,
      name: name,
      type: type,
      description: _str(v['description']),
      avatarPath: _str(v['avatar_path']),
      lastMessageAt: _date(v['last_message_at']),
      membersCount: _int(v['members_count']),
      unreadCount: _intOrNull(v['unread_count']),
      dmPartner: ChatUser.fromJson(v['dm_partner']),
      isOfficial: v['is_official'] == true,
    );
  }
}

/// Parsed GET /chat page: grouped conversations + eligible users.
class ChatIndex {
  const ChatIndex({
    required this.direct,
    required this.groups,
    required this.employees,
  });

  final List<Conversation> direct; // dm
  final List<Conversation> groups; // group + company + department channels
  final List<ChatUser> employees; // active, same-company, excluding self

  static ChatIndex fromPage(InertiaPage page) {
    final channels = page.props['channels'];
    final dm = <Conversation>[];
    final groups = <Conversation>[];
    if (channels is Map) {
      _collect(channels['dm'], dm);
      _collect(channels['group'], groups);
      _collect(channels['company'], groups);
      _collect(channels['department'], groups);
    }
    final employees = <ChatUser>[];
    if (page.props['employees'] is List) {
      for (final e in page.props['employees'] as List) {
        final u = ChatUser.fromJson(e);
        if (u != null) employees.add(u);
      }
    }
    return ChatIndex(direct: dm, groups: groups, employees: employees);
  }

  static void _collect(Object? list, List<Conversation> out) {
    if (list is! List) return;
    for (final item in list) {
      final c = Conversation.fromJson(item);
      // Rooms never appear as Chat destinations; guard anyway.
      if (c != null && c.id > 0) out.add(c);
    }
  }
}

/// Parsed GET /chat/channels/{channel} page.
class ChannelDetail {
  const ChannelDetail({
    required this.conversation,
    this.myRole,
    this.membersCount,
    this.unreadCount,
    this.createdAt,
  });

  final Conversation conversation;
  final String? myRole;
  final int? membersCount;
  final int? unreadCount;
  final DateTime? createdAt;

  static ChannelDetail? fromPage(InertiaPage page) {
    final c = Conversation.fromJson(page.props['channel']);
    if (c == null) return null;
    return ChannelDetail(
      conversation: c,
      myRole: _str(page.props['channel'] is Map
          ? (page.props['channel'] as Map)['my_role']
          : null),
      membersCount: _int(
          page.props['channel'] is Map
              ? (page.props['channel'] as Map)['members_count']
              : null),
      unreadCount: _intOrNull(
          page.props['channel'] is Map
              ? (page.props['channel'] as Map)['unread_count']
              : null),
      createdAt: _date(page.props['channel'] is Map
          ? (page.props['channel'] as Map)['created_at']
          : null),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────
// Messages
// ─────────────────────────────────────────────────────────────────────

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.channelId,
    required this.userId,
    required this.body,
    this.type,
    this.editedAt,
    required this.createdAt,
    this.senderName,
    this.senderAvatarPath,
  });

  final int id;
  final int channelId;
  final int userId;
  final String body;
  final String? type;
  final DateTime? editedAt;
  final DateTime createdAt;
  final String? senderName;
  final String? senderAvatarPath;

  bool get isEdited => editedAt != null;

  static ChatMessage? fromJson(Object? v) {
    if (v is! Map) return null;
    final id = v['id'];
    final userId = v['user_id'];
    final body = v['body'] ?? '';
    final createdAt = v['created_at'];
    if (id == null || userId == null || createdAt is! String) return null;
    final user = v['user'];
    String? senderName;
    String? senderAvatar;
    if (user is Map) {
      final n = user['name'];
      if (n is String && n.isNotEmpty) senderName = n;
      senderAvatar = _str(user['avatar_path']);
    }
    return ChatMessage(
      id: id is int ? id : int.tryParse(id.toString()) ?? 0,
      channelId: _int(v['channel_id']) ?? 0,
      userId: userId is int ? userId : int.tryParse(userId.toString()) ?? 0,
      body: body is String ? body : '',
      type: _str(v['type']),
      editedAt: _date(v['edited_at']),
      createdAt: DateTime.tryParse(createdAt) ?? DateTime.fromMillisecondsSinceEpoch(0),
      senderName: senderName,
      senderAvatarPath: senderAvatar,
    );
  }
}

/// Paginated message history (page from the newest-first endpoint).
class MessagesPage {
  const MessagesPage({
    required this.messages,
    required this.currentPage,
    required this.lastPage,
    required this.total,
  });

  /// Messages in ENDPOINT ORDER (newest-first). Callers reverse for display.
  final List<ChatMessage> messages;
  final int currentPage;
  final int lastPage;
  final int total;

  static MessagesPage fromJson(Object? v) {
    if (v is! Map) {
      return const MessagesPage(
          messages: [], currentPage: 1, lastPage: 1, total: 0);
    }
    final data = v['data'];
    final meta = v['meta'];
    final msgs = <ChatMessage>[];
    if (data is List) {
      for (final m in data) {
        final parsed = ChatMessage.fromJson(m);
        if (parsed != null) msgs.add(parsed);
      }
    }
    var currentPage = 1;
    var lastPage = 1;
    var total = 0;
    if (meta is Map) {
      currentPage = _int(meta['current_page']) ?? 1;
      lastPage = _int(meta['last_page']) ?? 1;
      total = _int(meta['total']) ?? 0;
    }
    return MessagesPage(
      messages: msgs,
      currentPage: currentPage,
      lastPage: lastPage,
      total: total,
    );
  }

  /// Chronological (oldest→newest) view of this page.
  List<ChatMessage> get chronological => List.of(messages.reversed);
}

// ─────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────

String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;
int? _int(Object? v) =>
    v is int ? v : (v is num ? v.toInt() : int.tryParse(v?.toString() ?? ''));
int? _intOrNull(Object? v) => v == null ? null : _int(v);
DateTime? _date(Object? v) => v is String ? DateTime.tryParse(v) : null;
