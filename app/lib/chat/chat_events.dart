import 'chat_models.dart';
import 'reverb_client.dart';

/// Parses broadcast events into typed chat mutations.
///
/// Verified against the Laravel Development source:
/// - `message.sent` on private-channel.{id} — payload identical to
///   formatMessage() (ChatMessage.fromJson parses it).
/// - `message.edited` / `message.deleted` on private-channel.{id} — payload
///   is the formatted message (message.deleted carries the tombstone with
///   body '[deleted]'-style state; ChatMessage.fromJson keeps what's there).
/// - `notification.received` on private-user.{id} — the serialized Laravel
///   notification; chat message notifications carry channel_id and
///   message_id fields.
///
/// Every parse returns null on unknown/unrelated shapes — never throws,
/// never fabricates.
class ChatEventParser {
  const ChatEventParser();

  ChatMessage? incomingMessage(BroadcastEvent e) {
    if (!e.name.endsWith('message.sent')) return null;
    final channelId = _int(e.channel, 'private-channel.');
    if (channelId == null) return null;
    final msg = ChatMessage.fromJson(e.data);
    if (msg == null) return null;
    // Trust the channel over the payload for channel identity.
    return ChatMessage(
      id: msg.id,
      channelId: channelId,
      userId: msg.userId,
      body: msg.body,
      type: msg.type,
      editedAt: msg.editedAt,
      createdAt: msg.createdAt,
      senderName: msg.senderName,
      senderAvatarPath: msg.senderAvatarPath,
    );
  }

  /// Returns the edited message, or the tombstone for message.deleted.
  ChatMessage? messageChanged(BroadcastEvent e) {
    if (!e.name.endsWith('message.edited') && !e.name.endsWith('message.deleted')) {
      return null;
    }
    final channelId = _int(e.channel, 'private-channel.');
    if (channelId == null) return null;
    final msg = ChatMessage.fromJson(e.data);
    if (msg == null) return null;
    return ChatMessage(
      id: msg.id,
      channelId: channelId,
      userId: msg.userId,
      body: msg.body,
      type: msg.type,
      editedAt: msg.editedAt,
      createdAt: msg.createdAt,
      senderName: msg.senderName,
      senderAvatarPath: msg.senderAvatarPath,
    );
  }

  /// Extracts the conversation a notification.received belongs to.
  /// Returns null for notifications that are not chat-message notifications
  /// (meetings, tasks, other channels) or malformed payloads.
  ///
  /// Laravel's NotificationReceived::broadcastWith() nests the notification
  /// payload: {id, type, title, body, data: {channel_id, message_id, ...},
  /// read_at, created_at}. The channel id lives INSIDE `data` — the
  /// top-level fallback keeps compatibility with flat payloads.
  ChatNotification? chatNotification(BroadcastEvent e) {
    if (!e.name.endsWith('notification.received')) return null;
    var channelId = _intFromAny(e.data['channel_id']);
    channelId ??= e.data['data'] is Map
        ? _intFromAny((e.data['data'] as Map)['channel_id'])
        : null;
    final messageId = _intFromAny(e.data['message_id']);
    final nestedMessageId = e.data['data'] is Map
        ? _intFromAny((e.data['data'] as Map)['message_id'])
        : null;
    if (channelId == null || channelId <= 0) return null;
    return ChatNotification(
      channelId: channelId,
      messageId: messageId ?? nestedMessageId,
    );
  }
}

class ChatNotification {
  const ChatNotification({required this.channelId, this.messageId});

  final int channelId;
  final int? messageId;
}

int? _int(String channel, String prefix) {
  if (!channel.startsWith(prefix)) return null;
  return int.tryParse(channel.substring(prefix.length));
}

int? _intFromAny(Object? v) =>
    v is int ? v : (v is num ? v.toInt() : int.tryParse(v?.toString() ?? ''));
