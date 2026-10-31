/// Direct Calling (Phase C1) — data models + wire parsing.
///
/// Wire payload from GET/POST /chat/calls (Laravel Development backend).
/// Tokens NEVER travel in broadcast payloads — only via HTTP responses
/// for the requesting authorized participant.
library;

import 'dart:convert';

/// Parsed `call` payload (DirectCall::toCallPayload()).
class DirectCallData {
  const DirectCallData({
    required this.callId,
    required this.status,
    required this.callType,
    this.channelId,
    this.caller,
    this.recipient,
    this.createdAt,
    this.endReason,
  });

  final String callId;
  final String status; // ringing|accepted|declined|cancelled|missed|ended
  final String callType; // audio|video
  final int? channelId;
  final DirectCallUser? caller;
  final DirectCallUser? recipient;
  final DateTime? createdAt;
  final String? endReason;

  bool get isRinging => status == 'ringing';
  bool get isActive => status == 'accepted';
  bool get isVideo => callType == 'video';

  static DirectCallData? fromJson(Object? v) {
    if (v is! Map) return null;
    final callId = v['call_id'];
    final status = v['status'];
    final callType = v['call_type'];
    if (callId is! String || status is! String || callType is! String) {
      return null;
    }
    final channelId = v['channel_id'];
    final createdAt = v['created_at'];
    final endReason = v['end_reason'];
    return DirectCallData(
      callId: callId,
      status: status,
      callType: callType,
      channelId: channelId is int ? channelId : null,
      caller: DirectCallUser.fromJson(v['caller']),
      recipient: DirectCallUser.fromJson(v['recipient']),
      createdAt: createdAt is String ? DateTime.tryParse(createdAt) : null,
      endReason: endReason is String ? endReason : null,
    );
  }
}

class DirectCallUser {
  const DirectCallUser({
    required this.id,
    required this.name,
    this.avatarPath,
    this.jobTitle,
  });

  final int id;
  final String name;
  final String? avatarPath;
  final String? jobTitle;

  static DirectCallUser? fromJson(Object? v) {
    if (v is! Map) return null;
    final id = v['id'];
    final name = v['name'];
    if (id is! int || name is! String) return null;
    final avatarPath = v['avatar_path'];
    final jobTitle = v['job_title'];
    return DirectCallUser(
      id: id,
      name: name,
      avatarPath: avatarPath is String ? avatarPath : null,
      jobTitle: jobTitle is String ? jobTitle : null,
    );
  }
}

/// LiveKit credentials from POST /chat/calls/{id}/token or /accept.
class DirectCallToken {
  const DirectCallToken({required this.token, this.url});

  final String token;
  final String? url;

  bool get isUsable => token.isNotEmpty;

  /// Parses the raw JSON body string of a token response.
  static DirectCallToken? parse(String body) {
    Object? decoded;
    try {
      decoded = jsonDecode(body);
    } catch (_) {
      return null;
    }
    if (decoded is! Map) return null;
    final token = decoded['token'];
    if (token is! String || token.isEmpty) return null;
    final url = decoded['url'];
    return DirectCallToken(token: token, url: url is String ? url : null);
  }
}
