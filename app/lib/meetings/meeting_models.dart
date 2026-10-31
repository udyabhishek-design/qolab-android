library;

/// Meeting + LiveKit join-token models for Phase B calling.
///
/// Pure data + parsing — no Flutter imports, unit-testable in plain test()
/// blocks. Server contract (verified live against Development):
///   POST /meetings/{id}/join-token → {"success": true, "token": "...",
///   "url": "wss://addy-uuxwzign.livekit.cloud"}  (url is the WSS endpoint;
///   the web client feeds url+token to livekit-client's Room.connect).

import 'dart:convert';

/// Parsed join-token response.
class MeetingJoinToken {
  const MeetingJoinToken({required this.success, this.token, this.url});

  final bool success;
  final String? token;
  final String? url;

  /// Ready to connect only when the server confirmed success AND both the
  /// token and the WSS url are present. Anything else is a protocol error
  /// the caller must surface as a connection problem, never silently retry.
  bool get isUsable => success && (token?.isNotEmpty ?? false) && (url?.isNotEmpty ?? false);

  /// Parse the JSON body of POST /meetings/{id}/join-token.
  /// Returns null for malformed shapes (caller shows a generic error).
  static MeetingJoinToken? parse(String body) {
    dynamic decoded;
    try {
      decoded = jsonDecode(body);
    } catch (_) {
      return null;
    }
    if (decoded is! Map<String, dynamic>) return null;
    final success = decoded['success'];
    final token = decoded['token'];
    final url = decoded['url'];
    return MeetingJoinToken(
      success: success is bool ? success : false,
      token: token is String ? token : null,
      url: url is String ? url : null,
    );
  }
}

/// A meeting entry from GET /meetings props (upcoming/past/all).
class MeetingEntry {
  const MeetingEntry({
    required this.id,
    required this.title,
    this.status,
    this.sessionKey,
    this.scheduledAt,
    this.participantsCount,
  });

  final int id;
  final String title;
  final String? status;
  final String? sessionKey;
  final DateTime? scheduledAt;
  final int? participantsCount;

  bool get isLive => status == 'live' || status == 'in_progress';

  /// Parse one raw meeting map (never throws).
  static MeetingEntry? fromJson(Map<String, dynamic>? raw) {
    if (raw == null) return null;
    final id = raw['id'];
    final title = raw['title'];
    if (id is! int || title is! String || title.isEmpty) return null;
    final scheduledAt = raw['scheduled_at'];
    final participantsCount = raw['participants_count'];
    return MeetingEntry(
      id: id,
      title: title,
      status: raw['status'] is String ? raw['status'] as String : null,
      sessionKey:
          raw['session_key'] is String ? raw['session_key'] as String : null,
      scheduledAt: scheduledAt is String ? DateTime.tryParse(scheduledAt) : null,
      participantsCount:
          participantsCount is int ? participantsCount : null,
    );
  }
}
