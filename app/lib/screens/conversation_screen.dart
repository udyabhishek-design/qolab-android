import 'package:flutter/material.dart';

import '../api/api_errors.dart';
import '../api/qolab_api.dart';
import '../chat/chat_controller.dart';
import '../chat/chat_models.dart';
import '../chat/chat_socket.dart';
import '../chat/message_formatting.dart';
import '../theme.dart';

/// A single conversation: message history (chronological, paginated),
/// composer, send. No call buttons (calling is a later phase).
///
/// Phase 4A.2: with [socket] provided, subscribes to the conversation's
/// private channel — message.sent arrives in real time (deduped against
/// the REST send response by id), message.edited/deleted update history,
/// and a reconnect triggers a REST catch-up. Without a socket the screen
/// behaves exactly like Phase 4A.1 REST chat.
///
/// UX pass (4A.2.5):
/// - The history list is REVERSED: scroll offset 0 is the newest message,
///   so the conversation opens at the latest message deterministically
///   (no jump hack, no animation through history, correct under keyboard
///   insets). Items are built newest-first.
/// - While the initial fetch is in flight a skeleton shows; the empty
///   state only renders after a completed fetch returned nothing.
/// - Date separators sit above the first message of each local calendar
///   day (Today / Yesterday / 28 October 2026); timestamps are local time.
/// - New message while reading older history: no forced jump — a small
///   "New messages" pill; tap to snap to the newest.
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

  /// Id of the newest message the user has seen (bottom-most built item in
  /// the reversed list). Drives the "New messages" pill while reading older
  /// history; cleared when the user returns to the newest end.
  int? _newestSeenId;
  bool _showJumpPill = false;

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
    _newestSeenId = chat.messages.isNotEmpty ? chat.messages.last.id : null;
    chat.load();
  }

  void _onChanged() {
    if (chat.messages.isNotEmpty) {
      final newestId = chat.messages.last.id;
      if (_scroll.hasClients) {
        final atBottom = _scroll.position.pixels <= 120;
        if (!atBottom && newestId != _newestSeenId && _newestSeenId != null) {
          // New message landed while the user is reading older history —
          // don't move their position; surface the pill instead.
          _showJumpPill = true;
        } else if (atBottom && newestId != _newestSeenId) {
          // Following the tail: a short ease to keep the newest in view.
          _newestSeenId = newestId;
          _showJumpPill = false;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && _scroll.hasClients && _scroll.position.pixels <= 120) {
              _scroll.animateTo(0,
                  duration: const Duration(milliseconds: 200),
                  curve: Curves.easeOut);
            }
          });
        }
      } else {
        _newestSeenId = newestId;
      }
    }
    if (mounted) setState(() {});
  }

  void _onScroll() {
    // Reversed list: near offset 0 == viewing the newest end.
    if (!_scroll.hasClients) return;
    if (_scroll.position.pixels <= 120 &&
        _showJumpPill &&
        chat.messages.isNotEmpty) {
      _newestSeenId = chat.messages.last.id;
      setState(() => _showJumpPill = false);
    }
    // Reversed list: the OLDER end is at maxScrollExtent → paginate there.
    if (_scroll.position.pixels >=
            _scroll.position.maxScrollExtent - 80 &&
        chat.hasMore &&
        !chat.loadingOlder) {
      chat.loadOlder();
    }
  }

  void _jumpToLatest() {
    _newestSeenId = chat.messages.isNotEmpty ? chat.messages.last.id : null;
    _showJumpPill = false;
    if (!_scroll.hasClients) return;
    if (_scroll.position.pixels <= 600) {
      _scroll.position.jumpTo(0);
    } else {
      _scroll.animateTo(0,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut);
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

  Future<void> _send() async {
    final text = _composer.text;
    if (text.trim().isEmpty || chat.sending) return;
    setState(() => _sendError = null);
    try {
      await chat.send(text);
      _composer.clear();
      _newestSeenId = chat.messages.isNotEmpty ? chat.messages.last.id : null;
      _showJumpPill = false;
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
            Expanded(
              child: Stack(
                children: [
                  _history(),
                  if (_showJumpPill)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 10,
                      child: Center(child: _jumpPill()),
                    ),
                ],
              ),
            ),
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

  Widget _jumpPill() {
    return Material(
      key: const Key('jump-pill'),
      color: QolabTheme.wine,
      borderRadius: BorderRadius.circular(20),
      elevation: 2,
      child: InkWell(
        onTap: _jumpToLatest,
        borderRadius: BorderRadius.circular(20),
        child: const Padding(
          padding: EdgeInsets.symmetric(horizontal: 14, vertical: 7),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.arrow_downward_rounded, size: 15, color: Colors.white),
              SizedBox(width: 5),
              Text('New messages',
                  style: TextStyle(
                      color: Colors.white,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      ),
    );
  }

  bool get isGroupLike => chat.conversation?.isGroupLike ?? false;

  Widget _history() {
    // Initial fetch in flight (header and/or first history page) with no
    // content yet → skeleton. The empty state must never render before the
    // fetch completed — that was the "No messages" flash.
    final initialLoading =
        chat.messages.isEmpty && (chat.detail.isLoading || chat.initialHistoryLoading);
    if (initialLoading) {
      return const _HistorySkeleton();
    }
    if (chat.detail.state == SectionState.error && chat.messages.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.cloud_off_outlined,
                  size: 40, color: QolabTheme.wineFaded),
              const SizedBox(height: 10),
              Text(chat.detail.error ?? 'Conversation unavailable.',
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
      // list. Reversed list: the newest end is offset 0 — only auto-snap
      // back when the user was already there.
      onRefresh: () async {
        final wasAtNewest = _scroll.hasClients && _scroll.position.pixels <= 160;
        await chat.refreshMessages();
        if (wasAtNewest && _scroll.hasClients) {
          _jumpToLatest();
        }
      },
      child: ListView.builder(
        controller: _scroll,
        reverse: true,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        itemCount: chat.messages.length + (chat.loadingOlder ? 1 : 0),
        itemBuilder: (context, i) {
          if (chat.loadingOlder && i == chat.messages.length) {
            // Older-history spinner renders at the visual TOP (older end).
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
          // Reversed index: i = 0 is the NEWEST message.
          final idx = chat.messages.length - 1 - i;
          final m = chat.messages[idx];
          // Separator sits above the first message of a local calendar day
          // (i.e. when this message is older than its successor's day).
          final newer = idx + 1 < chat.messages.length
              ? chat.messages[idx + 1]
              : null;
          final separator =
              newer == null || startsNewDay(m.createdAt, newer.createdAt)
                  ? dayLabel(m.createdAt)
                  : null;
          return _MessageBubble(
            message: m,
            mine: m.userId == widget.myUserId,
            showSender: isGroupLike && m.userId != widget.myUserId,
            daySeparator: separator,
          );
        },
      ),
    );
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

/// Subtle loading placeholder shown while the initial fetch runs — never an
/// "empty conversation" claim. Bubbles sit at the bottom (mirroring the
/// reversed list anchored at the newest end) with a gentle shimmer.
class _HistorySkeleton extends StatelessWidget {
  const _HistorySkeleton();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      key: Key('history-skeleton'),
      padding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          _SkeletonShimmer(width: 150, alignment: Alignment.centerLeft),
          _SkeletonShimmer(width: 220, alignment: Alignment.centerRight),
          _SkeletonShimmer(width: 180, alignment: Alignment.centerLeft),
          _SkeletonShimmer(width: 240, alignment: Alignment.centerRight),
          _SkeletonShimmer(width: 120, alignment: Alignment.centerLeft),
        ],
      ),
    );
  }
}

class _SkeletonShimmer extends StatefulWidget {
  const _SkeletonShimmer({required this.width, required this.alignment});

  final double width;
  final Alignment alignment;

  @override
  State<_SkeletonShimmer> createState() => _SkeletonShimmerState();
}

class _SkeletonShimmerState extends State<_SkeletonShimmer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 1100))..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: Tween(begin: 0.45, end: 1.0)
          .animate(CurvedAnimation(parent: _c, curve: Curves.easeInOut)),
      child: Align(
        alignment: widget.alignment,
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 5),
          width: widget.width,
          height: 38,
          decoration: BoxDecoration(
            color: QolabTheme.surface,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: QolabTheme.line),
          ),
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
    this.daySeparator,
  });

  final ChatMessage message;
  final bool mine;
  final bool showSender;
  final String? daySeparator;

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
            chatTimeLabel(message.createdAt) +
                (message.isEdited ? ' · edited' : ''),
            style: TextStyle(
              color: mine ? Colors.white70 : QolabTheme.textSoft,
              fontSize: 10.5,
            ),
          ),
        ],
      ),
    );
    final row = Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: bubble,
    );
    if (daySeparator == null) return row;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _DateChip(label: daySeparator!),
        row,
      ],
    );
  }
}

class _DateChip extends StatelessWidget {
  const _DateChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          decoration: BoxDecoration(
            color: QolabTheme.surface,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: QolabTheme.line),
          ),
          child: Text(
            label,
            style: const TextStyle(
              color: QolabTheme.textSoft,
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}
