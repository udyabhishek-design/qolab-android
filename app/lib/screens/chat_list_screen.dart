import 'package:flutter/material.dart';

import '../api/qolab_api.dart';
import '../auth/auth_controller.dart';
import '../chat/chat_controller.dart';
import '../chat/chat_models.dart';
import '../theme.dart';
import 'conversation_screen.dart';
import 'new_chat_screen.dart';

/// Phase 4A.1 Chat tab: conversation list (DMs + group/channel chats) with
/// search, unread badges, and new-conversation flow. Warm-light Qolab
/// design matching Home.
class ChatListScreen extends StatefulWidget {
  const ChatListScreen({
    super.key,
    required this.auth,
    required this.api,
    this.controller,
    this.onOpenConversation,
  });

  final AuthController auth;
  final QolabApi api;

  /// Optional injected controller (tests seed state directly).
  final ChatListController? controller;

  /// Optional navigation hook (tests can capture); defaults to pushing
  /// ConversationScreen.
  final void Function(BuildContext, Conversation)? onOpenConversation;

  @override
  State<ChatListScreen> createState() => _ChatListScreenState();
}

class _ChatListScreenState extends State<ChatListScreen> {
  late final ChatListController chat;
  String _query = '';

  @override
  void initState() {
    super.initState();
    chat = widget.controller ?? ChatListController(api: widget.api);
    chat.addListener(_onChanged);
    chat.refresh();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    chat.removeListener(_onChanged);
    if (widget.controller == null) chat.dispose();
    super.dispose();
  }

  void _openConversation(BuildContext context, Conversation c) {
    if (widget.onOpenConversation != null) {
      widget.onOpenConversation!(context, c);
      return;
    }
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => ConversationScreen(
        api: widget.api,
        channelId: c.id,
        fallbackName: c.name,
        myUserId: widget.auth.user?.id ?? 0,
      ),
    ));
  }

  Future<void> _openNewChat() async {
    final idx = chat.index.value;
    final employees = idx?.employees ?? const <ChatUser>[];
    final channelId = await Navigator.of(context).push<int>(
      MaterialPageRoute(
        builder: (_) => NewChatScreen(
          api: widget.api,
          chat: chat,
          employees: employees,
        ),
      ),
    );
    // Refresh conversation data when returning (covers DM just created).
    if (mounted && channelId != null) {
      chat.refresh();
      final updated = chat.index.value;
      final Conversation? conv =
          updated == null ? null : _findById(updated, channelId);
      if (mounted) {
        _openConversation(
            context,
            conv ??
                Conversation(id: channelId, name: 'Conversation', type: 'dm'));
      }
    }
  }

  Conversation? _findById(ChatIndex idx, int id) {
    for (final c in idx.direct) {
      if (c.id == id) return c;
    }
    for (final c in idx.groups) {
      if (c.id == id) return c;
    }
    return null;
  }

  List<Conversation> _filter(List<Conversation> list) {
    if (_query.isEmpty) return list;
    final q = _query.toLowerCase();
    return list
        .where((c) =>
            c.name.toLowerCase().contains(q) ||
            (c.description?.toLowerCase().contains(q) ?? false))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: QolabTheme.canvas,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Chat', style: QolabTheme.headline),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          onChanged: (v) => setState(() => _query = v),
                          decoration: QolabTheme.field(
                            label: 'Search conversations',
                          ).copyWith(
                            prefixIcon: const Icon(Icons.search,
                                size: 20, color: QolabTheme.textSoft),
                            isDense: true,
                            contentPadding: const EdgeInsets.symmetric(
                                vertical: 10),
                          ),
                          style: const TextStyle(fontSize: 14),
                        ),
                      ),
                      const SizedBox(width: 8),
                      _NewChatButton(onTap: _openNewChat),
                    ],
                  ),
                ],
              ),
            ),
            Expanded(child: _body()),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    final s = chat.index;
    if (s.isLoading) {
      return const Center(
        child: SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(
              strokeWidth: 2.2, color: QolabTheme.wineFaded),
        ),
      );
    }
    if (s.state == SectionState.error && !s.hasValue) {
      return _ErrorRetry(message: s.error ?? 'Chat is unavailable.', onRetry: chat.retry);
    }
    if (s.state == SectionState.empty || s.value == null) {
      return const _EmptyChat();
    }
    final data = s.value!;
    final dm = _filter(data.direct);
    final groups = _filter(data.groups);
    if (dm.isEmpty && groups.isEmpty) {
      return const _EmptyChat(searching: true);
    }
    return RefreshIndicator(
      color: QolabTheme.wine,
      onRefresh: chat.refresh,
      child: ListView(
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          if (dm.isNotEmpty) ...[
            _sectionLabel('Direct messages'),
            ...dm.map((c) => _ConversationTile(
                  conversation: c,
                  unread: chat.unread?[c.id] ?? c.unreadCount,
                  onTap: () => _openConversation(context, c),
                )),
          ],
          if (groups.isNotEmpty) ...[
            _sectionLabel('Groups & channels'),
            ...groups.map((c) => _ConversationTile(
                  conversation: c,
                  unread: chat.unread?[c.id] ?? c.unreadCount,
                  onTap: () => _openConversation(context, c),
                )),
          ],
        ],
      ),
    );
  }

  Widget _sectionLabel(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 14, 20, 6),
        child: Text(text,
            style: const TextStyle(
              color: QolabTheme.textSoft,
              fontSize: 12,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.4,
            )),
      );
}

// ─────────────────────────────────────────────────────────────────────

class _NewChatButton extends StatelessWidget {
  const _NewChatButton({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: QolabTheme.wine,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: const Padding(
          padding: EdgeInsets.all(11),
          child: Icon(Icons.edit_outlined, size: 20, color: Colors.white),
        ),
      ),
    );
  }
}

class _ConversationTile extends StatelessWidget {
  const _ConversationTile({
    required this.conversation,
    required this.onTap,
    this.unread,
  });

  final Conversation conversation;
  final VoidCallback onTap;
  final int? unread;

  String _initials(String name) {
    final parts =
        name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty);
    if (parts.isEmpty) return '?';
    if (parts.length == 1) return parts.first[0].toUpperCase();
    return (parts.first[0] + parts.last[0]).toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    final c = conversation;
    final hasUnread = (unread ?? 0) > 0;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 9),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: c.isDm ? QolabTheme.wine : QolabTheme.wineFaded,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Center(
                child: Text(
                  _initials(c.name),
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w700,
                    fontSize: 15,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          c.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: hasUnread
                                ? FontWeight.w700
                                : FontWeight.w600,
                            color: QolabTheme.ink,
                          ),
                        ),
                      ),
                      if (hasUnread)
                        Container(
                          margin: const EdgeInsets.only(left: 8),
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: QolabTheme.wine,
                            borderRadius: BorderRadius.circular(999),
                          ),
                            child: Text(
                            '$unread',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(
                    c.isDm
                        ? (c.dmPartner?.departmentName ??
                            c.description ??
                            'Direct message')
                        : c.description ??
                            '${c.membersCount ?? 0} members',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: QolabTheme.textSoft,
                      fontSize: 13,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmptyChat extends StatelessWidget {
  const _EmptyChat({this.searching = false});

  final bool searching;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.chat_bubble_outline_rounded,
                size: 40, color: QolabTheme.wineFaded),
            const SizedBox(height: 12),
            Text(
              searching ? 'No conversations match' : 'No conversations yet',
              style: QolabTheme.title,
            ),
            const SizedBox(height: 6),
            const Text(
              'Start a new conversation with the pencil button.',
              textAlign: TextAlign.center,
              style: TextStyle(color: QolabTheme.textSoft, fontSize: 13.5),
            ),
          ],
        ),
      ),
    );
  }
}

class _ErrorRetry extends StatelessWidget {
  const _ErrorRetry({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.cloud_off_outlined,
                size: 40, color: QolabTheme.wineFaded),
            const SizedBox(height: 12),
            Text(message,
                textAlign: TextAlign.center,
                style: const TextStyle(color: QolabTheme.textSoft)),
            const SizedBox(height: 14),
            FilledButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }
}
