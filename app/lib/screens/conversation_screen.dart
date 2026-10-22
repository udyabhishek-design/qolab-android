import 'package:flutter/material.dart';

import '../api/api_errors.dart';
import '../api/qolab_api.dart';
import '../chat/chat_controller.dart';
import '../chat/chat_models.dart';
import '../chat/chat_socket.dart';
import '../theme.dart';

/// A single conversation: message history (chronological, paginated),
/// composer, send. No call buttons (calling is a later phase).
///
/// Phase 4A.2: with [socket] provided, subscribes to the conversation's
/// private channel — message.sent arrives in real time (deduped against
/// the REST send response by id), message.edited/deleted update history,
/// and a reconnect triggers a REST catch-up. Without a socket the screen
/// behaves exactly like Phase 4A.1 REST chat.
class ConversationScreen extends StatefulWidget {
  const ConversationScreen({
    super.key,
    required this.api,
    required this.channelId,
    required this.fallbackName,
    required this.myUserId,
    this.socket,
    this.controller,
    this.onSessionExpired,
  });

  final QolabApi api;
  final int channelId;
  final String fallbackName;
  final int myUserId;

  /// Realtime socket (Phase 4A.2). Optional — null keeps pure-REST.
  final ChatSocketService? socket;

  final ConversationController? controller;
  final void Function()? onSessionExpired;

  @override
  State<ConversationScreen> createState() => _ConversationScreenState();
}

class _ConversationScreenState extends State<ConversationScreen> {
  late final ConversationController chat;
  final TextEditingController _composer = TextEditingController();
  final ScrollController _scroll = ScrollController();
  String? _sendError;
  int _lastSeenCount = 0;

  @override
  void initState() {
    super.initState();
    chat = widget.controller ??
        ConversationController(
          api: widget.api,
          channelId: widget.channelId,
          myUserId: widget.myUserId,
          socket: widget.socket,
          onSessionExpired: widget.onSessionExpired,
        );
    chat.addListener(_onChanged);
    _scroll.addListener(_onScroll);
    _lastSeenCount = chat.messages.length;
    chat.load();
  }

  void _onChanged() {
    // Realtime message arrived (or send appended) → follow the tail if the
    // user is already at/near the bottom; otherwise preserve their
    // reading position (no jump).
    if (chat.messages.length > _lastSeenCount && _scroll.hasClients) {
      final atBottom = _scroll.position.pixels >=
          _scroll.position.maxScrollExtent - 120;
      if (atBottom) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && _scroll.hasClients) {
            _scroll.animateTo(
              _scroll.position.maxScrollExtent,
              duration: const Duration(milliseconds: 200),
              curve: Curves.easeOut,
            );
          }
        });
      }
    }
    _lastSeenCount = chat.messages.length;
    if (mounted) setState(() {});
  }

  void _onScroll() {
    // Near the top → load older messages (guard inside controller).
    if (_scroll.hasClients &&
        _scroll.position.pixels <= 80 &&
        chat.hasMore &&
        !chat.loadingOlder) {
      chat.loadOlder();
    }
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    chat.removeListener(_onChanged);
    _composer.dispose();
    _scroll.dispose();
    if (widget.controller == null) chat.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // After the first layout with history, land at the newest message.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _scroll.hasClients) {
        _scroll.jumpTo(_scroll.position.maxScrollExtent);
      }
    });
  }

  Future<void> _send() async {
    final text = _composer.text;
    if (text.trim().isEmpty || chat.sending) return;
    setState(() => _sendError = null);
    try {
      await chat.send(text);
      _composer.clear();
    } on ApiException catch (e) {
      // Message stays in the composer so the user can retry.
      if (mounted) setState(() => _sendError = e.message);
    } catch (_) {
      if (mounted) {
        setState(() => _sendError = 'Message not sent. Please try again.');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final name = chat.conversation?.name ?? widget.fallbackName;
    return Scaffold(
      backgroundColor: QolabTheme.canvas,
      appBar: AppBar(
        backgroundColor: QolabTheme.canvas,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: const BackButton(color: QolabTheme.ink),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color: QolabTheme.ink,
                    fontSize: 16.5,
                    fontWeight: FontWeight.w700)),
            if (isGroupLike)
              Text(
                '${chat.conversation?.membersCount ?? ''} members',
                style: const TextStyle(
                    color: QolabTheme.textSoft, fontSize: 12),
              ),
          ],
        ),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(child: _history()),
            if (_sendError != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 6, 20, 0),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(_sendError!,
                          style: const TextStyle(
                              color: QolabTheme.wine, fontSize: 12.5)),
                    ),
                  ],
                ),
              ),
            _composerBar(),
          ],
        ),
      ),
    );
  }

  bool get isGroupLike => chat.conversation?.isGroupLike ?? false;

  Widget _history() {
    final d = chat.detail;
    if (d.isLoading && chat.messages.isEmpty) {
      return const Center(
        child: SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(
              strokeWidth: 2.2, color: QolabTheme.wineFaded),
        ),
      );
    }
    if (d.state == SectionState.error && chat.messages.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.cloud_off_outlined,
                  size: 40, color: QolabTheme.wineFaded),
              const SizedBox(height: 10),
              Text(d.error ?? 'Conversation unavailable.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: QolabTheme.textSoft)),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: chat.refresh,
                icon: const Icon(Icons.refresh_rounded, size: 18),
                label: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }
    if (chat.messages.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                chat.conversation?.isDm ?? false
                    ? Icons.person_outline_rounded
                    : Icons.chat_bubble_outline_rounded,
                size: 40,
                color: QolabTheme.wineFaded,
              ),
              const SizedBox(height: 10),
              Text('No messages yet',
                  style: QolabTheme.title.copyWith(fontSize: 16)),
              const SizedBox(height: 4),
              const Text('Say hello — messages appear here.',
                  style: TextStyle(color: QolabTheme.textSoft, fontSize: 13.5)),
            ],
          ),
        ),
      );
    }
    return RefreshIndicator(
      // Merge-refresh: refreshMessages() merges by id and never replaces the
      // list, so the scroll position is preserved by construction. Only
      // auto-snap to the new bottom when the user was already there.
      onRefresh: () async {
        final wasAtBottom = _isAtBottom();
        await chat.refreshMessages();
        if (wasAtBottom && _scroll.hasClients) {
          _jumpToBottom();
        }
      },
      child: ListView.builder(
      controller: _scroll,
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      itemCount: chat.messages.length + (chat.loadingOlder ? 1 : 0),
      itemBuilder: (context, i) {
        if (chat.loadingOlder && i == 0) {
          return const Padding(
            padding: EdgeInsets.all(10),
            child: Center(
              child: SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                    strokeWidth: 2, color: QolabTheme.wineFaded),
              ),
            ),
          );
        }
        final idx = chat.loadingOlder ? i - 1 : i;
        final m = chat.messages[idx];
        return _MessageBubble(
          message: m,
          mine: m.userId == widget.myUserId,
          showSender: isGroupLike && m.userId != widget.myUserId,
        );
      },
    ),
    );
  }

  bool _isAtBottom() {
    if (!_scroll.hasClients) return true;
    final pos = _scroll.position;
    return pos.maxScrollExtent - pos.pixels <= 160;
  }

  void _jumpToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.position.jumpTo(_scroll.position.maxScrollExtent);
      }
    });
  }

  Widget _composerBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 8, 12, 10),
      decoration: const BoxDecoration(
        color: QolabTheme.surface,
        border: Border(top: BorderSide(color: QolabTheme.line)),
      ),
      child: SafeArea(
        top: false,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: TextField(
                controller: _composer,
                enabled: chat.detail.hasValue || chat.messages.isNotEmpty,
                maxLines: 4,
                minLines: 1,
                textInputAction: TextInputAction.newline,
                decoration: QolabTheme.field(label: 'Message').copyWith(
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 10),
                ),
                style: const TextStyle(fontSize: 14.5),
              ),
            ),
            const SizedBox(width: 8),
            chat.sending
                ? const Padding(
                    padding: EdgeInsets.all(9),
                    child: SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: QolabTheme.wine),
                    ),
                  )
                : Material(
                    color: QolabTheme.wine,
                    borderRadius: BorderRadius.circular(14),
                    child: InkWell(
                      onTap: _send,
                      borderRadius: BorderRadius.circular(14),
                      child: const Padding(
                        padding: EdgeInsets.all(11),
                        child: Icon(Icons.send_rounded,
                            size: 20, color: Colors.white),
                      ),
                    ),
                  ),
          ],
        ),
      ),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({
    required this.message,
    required this.mine,
    this.showSender = false,
  });

  final ChatMessage message;
  final bool mine;
  final bool showSender;

  String _time(DateTime t) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.hour)}:${two(t.minute)}';
  }

  @override
  Widget build(BuildContext context) {
    final bubble = Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
      constraints: const BoxConstraints(maxWidth: 300),
      decoration: BoxDecoration(
        color: mine ? QolabTheme.wine : QolabTheme.surface,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(16),
          topRight: const Radius.circular(16),
          bottomLeft: Radius.circular(mine ? 16 : 4),
          bottomRight: Radius.circular(mine ? 4 : 16),
        ),
        border: mine ? null : Border.all(color: QolabTheme.line),
      ),
      child: Column(
        crossAxisAlignment: mine
            ? CrossAxisAlignment.end
            : CrossAxisAlignment.start,
        children: [
          if (showSender)
            Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text(
                message.senderName ?? 'Member',
                style: const TextStyle(
                  color: QolabTheme.wineFaded,
                  fontSize: 11.5,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          Text(
            message.body,
            style: TextStyle(
              color: mine ? Colors.white : QolabTheme.ink,
              fontSize: 14.5,
              height: 1.35,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            _time(message.createdAt) + (message.isEdited ? ' · edited' : ''),
            style: TextStyle(
              color: mine ? Colors.white70 : QolabTheme.textSoft,
              fontSize: 10.5,
            ),
          ),
        ],
      ),
    );
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: bubble,
    );
  }
}
