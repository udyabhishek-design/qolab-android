import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../api/api_errors.dart';
import '../api/inertia.dart';
import '../api/qolab_api.dart';
import 'chat_events.dart';
import 'chat_models.dart';
import 'chat_socket.dart';
import 'reverb_client.dart' show BroadcastEvent;

/// Phase 4A.1 REST-based chat controllers.
///
/// Follows the Phase 3 HomeController patterns: independent loading/error
/// states, generation guards against stale responses, single-flight where
/// duplicate calls would corrupt state, last-good content preserved on
/// refresh failure, session expiry delegated to the existing auth flow via
/// [onSessionExpired]. NO polling, NO websockets — Phase 4A.2 adds realtime.

// ─────────────────────────────────────────────────────────────────────
// Section state (same shape as HomeController.Section)
// ─────────────────────────────────────────────────────────────────────

class Section<T> {
  Section.loading()
      : state = SectionState.loading,
        value = null,
        error = null;
  Section.loaded(this.value)
      : state = SectionState.loaded,
        error = null;
  Section.empty()
      : state = SectionState.empty,
        value = null,
        error = null;
  Section.failed(this.error)
      : state = SectionState.error,
        value = null;

  SectionState state;
  T? value; // last-good content survives failures once loaded
  String? error;

  bool get hasValue => value != null;
  bool get isLoading => state == SectionState.loading && !hasValue;

  /// Seed state directly (widget tests).
  @visibleForTesting
  void setLoaded(T? v) {
    value = v;
    error = null;
    state = v == null ? SectionState.empty : SectionState.loaded;
  }

  /// Seed an error state (widget tests).
  @visibleForTesting
  void setError(String message) {
    value = null;
    error = message;
    state = SectionState.error;
  }
}

enum SectionState { loading, loaded, empty, error }

// ─────────────────────────────────────────────────────────────────────
// Conversation list
// ─────────────────────────────────────────────────────────────────────

class ChatListController extends ChangeNotifier {
  ChatListController({required this.api, this.onSessionExpired, this.socket});

  final QolabApi api;
  final void Function()? onSessionExpired;

  /// Realtime socket (Phase 4A.2). Optional: null keeps pure-REST behavior.
  final ChatSocketService? socket;

  static const _parser = ChatEventParser();
  StreamSubscription<void>? _resyncSub;
  Timer? _unreadPoll;
  Timer? _reconcileTimer;
  final Map<int, DateTime> _activityHints = {};

  Section<ChatIndex> index = Section<ChatIndex>.loading();

  /// Unread counts keyed by channel id — independent of [index]; failure
  /// leaves the map null (never fabricated).
  Map<int, int>? unread;

  int _generation = 0;
  bool _loadingDm = false;
  bool _disposed = false;

  bool get startingDm => _loadingDm;

  /// Attach to the user's private notification channel. The my-user id is
  /// learned after the first index load (dm_partner pattern is not reliable
  /// for a user with no DMs) — instead the socket caller passes it; here we
  /// attach once [refresh] has the id available via api.me (subscribed by
  /// the shell when the user signs in).
  void attachUserChannel(int userId) {
    if (_disposed || socket == null || _notifyCancel != null) return;
    _notifyCancel = socket!.subscribe('user.$userId', _onNotificationEvent);
    _resyncSub = socket!.onResync.listen((_) {
      // Reconnected after a drop — reconcile unread + activity via REST.
      if (!_disposed) {
        _loadUnread(++_generation);
        refresh();
      }
    });
    _startBackgroundTimers();
  }

  VoidCallback? _notifyCancel;
  void _onNotificationEvent(BroadcastEvent e) {
    if (_disposed) return;
    final n = _parser.chatNotification(e);
    if (n == null) return; // unrelated notification — ignore
    final known = _findConversation(n.channelId) != null;
    if (!known) {
      // Unknown conversation (created elsewhere) — controlled REST refresh.
      if (!_refreshInFlight) refresh();
      return;
    }
    _activityHints[n.channelId] = DateTime.now();
    _bumpUnread(n.channelId);
    if (!_disposed) notifyListeners();
  }

  Conversation? _findConversation(int channelId) {
    final data = index.value;
    if (data == null) return null;
    for (final c in data.direct) {
      if (c.id == channelId) return c;
    }
    for (final c in data.groups) {
      if (c.id == channelId) return c;
    }
    return null;
  }

  void _bumpUnread(int channelId) {
    final current = unread?[channelId];
    if (current == null) return; // unknown count stays unknown
    unread = {...unread!, channelId: current + 1};
  }

  /// Latest activity time for a conversation: real-time hint wins when
  /// fresher than the backend's last_message_at (covers channels whose
  /// last_message_at is null because the message just arrived).
  DateTime? activityOf(Conversation c) {
    final hint = _activityHints[c.id];
    final backend = c.lastMessageAt;
    if (hint == null) return backend;
    if (backend == null) return hint;
    return hint.isAfter(backend) ? hint : backend;
  }

  /// Test hook: apply an activity hint for a channel (simulates a realtime
  /// notification) without needing a socket.
  @visibleForTesting
  void debugApplyActivityHint(int channelId) {
    _activityHints[channelId] = DateTime.now();
    notifyListeners();
  }

  void _startBackgroundTimers() {
    // Periodic unread refresh (authoritative reconciliation; cheap JSON).
    _unreadPoll?.cancel();
    _unreadPoll = Timer.periodic(const Duration(seconds: 60), (_) {
      if (!_disposed) _loadUnread(++_generation);
    });
    // Bounded reconciliation during healthy sessions for silent broadcast
    // loss: refresh unread + activity timestamps together, conservatively.
    _reconcileTimer?.cancel();
    _reconcileTimer = Timer.periodic(const Duration(minutes: 5), (_) {
      if (!_disposed && !_refreshInFlight) refresh();
    });
  }

  bool _refreshInFlight = false;

  /// Load (or refresh) the conversation list + unread counts.
  Future<void> refresh() async {
    if (_refreshInFlight) return;
    _refreshInFlight = true;
    try {
      await _doRefresh();
    } finally {
      _refreshInFlight = false;
    }
  }

  Future<void> _doRefresh() async {
    final gen = ++_generation;
    if (!index.hasValue) {
      index = Section<ChatIndex>.loading();
      notifyListeners();
    }

    // Unread counts are independent — never block the list on them.
    unawaited(_loadUnread(gen));

    try {
      final html = await api.fetchChatIndexHtml();
      if (_disposed || gen != _generation) return;
      final page = InertiaPage.parse(html);
      if (page == null) {
        if (!index.hasValue) {
          index = Section<ChatIndex>.failed('Chat is unavailable right now.');
          notifyListeners();
        }
        return;
      }
      final data = ChatIndex.fromPage(page);
      final isEmpty =
          data.direct.isEmpty && data.groups.isEmpty && data.employees.isEmpty;
      index = isEmpty ? Section<ChatIndex>.empty() : Section.loaded(data);
      notifyListeners();
    } on ApiException catch (e) {
      if (_disposed || gen != _generation) return;
      if (e.kind == ApiErrorKind.unauthenticated) {
        onSessionExpired?.call();
        return;
      }
      if (!index.hasValue) {
        index = Section<ChatIndex>.failed(_friendly(e));
        notifyListeners();
      }
    } catch (_) {
      if (_disposed || gen != _generation) return;
      if (!index.hasValue) {
        index = Section<ChatIndex>.failed('Chat is unavailable right now.');
        notifyListeners();
      }
    }
  }

  Future<void> _loadUnread(int gen) async {
    try {
      final counts = await api.fetchChatUnreadCounts();
      if (_disposed || gen != _generation) return;
      unread = counts;
      notifyListeners();
    } catch (_) {
      // Unknown unread stays unknown — never blocks the list.
    }
  }

  /// Start or reopen a DM with [userId]. Returns the channel id on success.
  /// Single-flight: a second tap while one is in progress is a no-op.
  Future<int?> startDm(int userId) async {
    if (_loadingDm) return null;
    _loadingDm = true;
    notifyListeners();
    try {
      return await api.startDm(userId);
    } on ApiException catch (e) {
      if (e.kind == ApiErrorKind.unauthenticated) {
        onSessionExpired?.call();
      }
      rethrow;
    } finally {
      _loadingDm = false;
      if (!_disposed) notifyListeners();
    }
  }

  void retry() => refresh();

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _unreadPoll?.cancel();
    _unreadPoll = null;
    _reconcileTimer?.cancel();
    _reconcileTimer = null;
    _notifyCancel?.call();
    _notifyCancel = null;
    _resyncSub?.cancel();
    _resyncSub = null;
    _activityHints.clear();
    super.dispose();
  }
}

// ─────────────────────────────────────────────────────────────────────
// Single conversation screen
// ─────────────────────────────────────────────────────────────────────

class ConversationController extends ChangeNotifier {
  ConversationController({
    required this.api,
    required this.channelId,
    required this.myUserId,
    this.onSessionExpired,
    this.socket,
  });

  final QolabApi api;
  final int channelId;
  final int myUserId;
  final void Function()? onSessionExpired;

  /// Realtime socket (Phase 4A.2). Optional: null keeps pure-REST 4A.1
  /// behavior (tests / degraded environments).
  final ChatSocketService? socket;

  StreamSubscription<void>? _resyncSub;
  Timer? _fallbackPoll;
  static const _parser = ChatEventParser();

  Section<Conversation> detail = Section<Conversation>.loading();

  /// Chronological (oldest→newest), deduped by id.
  List<ChatMessage> messages = [];
  bool loadingOlder = false;
  bool hasMore = true;
  bool sending = false;

  int _nextPage = 1; // page to fetch next (1 = newest page)
  int _generation = 0;
  bool _disposed = false;

  Conversation? get conversation => detail.value;

  bool get historyEmpty =>
      detail.state == SectionState.loaded && messages.isEmpty;

  /// Subscribe BEFORE loading history so no event can fall in the gap
  /// between subscribe and REST fetch; the id-dedupe absorbs overlaps.
  void _attachRealtime() {
    final s = socket;
    if (s == null) return;
    _eventCancel = s.subscribe('channel.$channelId', _onSocketEvent);
    _resyncSub = s.onResync.listen((_) {
      if (!_disposed) _loadMessagesPage(reset: true, gen: _generation);
    });
    _startFallbackPolling();
  }

  VoidCallback? _eventCancel;

  void _onSocketEvent(BroadcastEvent e) {
    if (_disposed) return;
    _lastSocketEventAt = DateTime.now();
    final incoming = _parser.incomingMessage(e);
    if (incoming != null) {
      // Cross-device delivery: a message sent by the SAME account from
      // another device (e.g. Qolab Web) carries myUserId but MUST still be
      // appended. The id-dedupe in _appendRealtime already suppresses the
      // echo of this device's own REST send (send() appends the server
      // response first).
      _appendRealtime(incoming);
      return;
    }
    final changed = _parser.messageChanged(e);
    if (changed != null) {
      _applyMessageChange(changed, deleted: e.name.endsWith('message.deleted'));
    }
  }

  void _appendRealtime(ChatMessage msg) {
    // Preserve chronology even for out-of-order delivery: insert at the
    // correct position by (createdAt, id).
    if (messages.any((m) => m.id == msg.id)) return; // duplicate
    if (messages.isEmpty || _isAfterLast(msg)) {
      messages = [...messages, msg];
    } else {
      // Out-of-order: insert before the first message newer than [msg].
      final i = messages.indexWhere((m) => _compare(m, msg) > 0);
      final at = i < 0 ? messages.length : i;
      messages = [...messages.sublist(0, at), msg, ...messages.sublist(at)];
    }
    _markReadUpToLatest();
    if (!_disposed) notifyListeners();
  }

  bool _isAfterLast(ChatMessage msg) {
    final last = messages.last;
    return _compare(msg, last) > 0;
  }

  int _compare(ChatMessage a, ChatMessage b) {
    final cmp = a.createdAt.compareTo(b.createdAt);
    if (cmp != 0) return cmp;
    return a.id.compareTo(b.id);
  }

  void _applyMessageChange(ChatMessage msg, {required bool deleted}) {
    final i = messages.indexWhere((m) => m.id == msg.id);
    if (i < 0) {
      // Edit/delete for a message we haven't fetched (older page). If it's
      // newer than everything loaded, treat as an append (deleted
      // tombstones are skipped — nothing to show).
      if (!deleted && _isAfterLast(msg)) {
        messages = [...messages, msg];
        if (!_disposed) notifyListeners();
      }
      return;
    }
    messages = [...messages]..[i] = msg;
    if (!_disposed) notifyListeners();
  }

  /// Fallback reconciliation for the OPEN conversation.
  ///
  /// - Socket NOT connected (or no socket): poll every 30s — full REST
  ///   catch-up for silent broadcast loss / Reverb outage.
  /// - Socket connected but SILENT (no event for 2 minutes — zombie
  ///   connection, silently dropped subscription): poll every 60s. The
  ///   merge-dedupe in _loadMessagesPage makes this cheap and idempotent.
  /// Single timer, never accumulated; refresh() is single-flight via the
  /// generation counter.
  DateTime? _lastSocketEventAt;

  void _startFallbackPolling() {
    _fallbackPoll?.cancel();
    _fallbackPoll = Timer.periodic(const Duration(seconds: 30), (_) {
      if (_disposed) return;
      final s = socket;
      if (s == null || !s.isConnected) {
        _loadMessagesPage(reset: true, gen: _generation);
        return;
      }
      // Connected: only reconcile when the connection has been silent long
      // enough to suspect a zombie (aligned to a 60s cadence via the 30s
      // tick).
      final last = _lastSocketEventAt;
      if (last != null &&
          DateTime.now().difference(last) >= const Duration(minutes: 2)) {
        _loadMessagesPage(reset: true, gen: _generation);
      }
    });
  }

  /// Initial load / refresh: channel detail + first (newest) history page.
  Future<void> load() async {
    final gen = ++_generation;
    _attachRealtime();
    if (!detail.hasValue) {
      detail = Section<Conversation>.loading();
      notifyListeners();
    }

    try {
      final html = await api.fetchChannelDetailHtml(channelId);
      if (_disposed || gen != _generation) return;
      final page = InertiaPage.parse(html);
      final d = page == null ? null : ChannelDetail.fromPage(page);
      if (d == null) {
        if (!detail.hasValue) {
          detail = Section<Conversation>.failed(
              'This conversation is unavailable.');
          notifyListeners();
        }
        return;
      }
      detail = Section.loaded(d.conversation);
      notifyListeners();
    } on ApiException catch (e) {
      if (_disposed || gen != _generation) return;
      if (e.kind == ApiErrorKind.unauthenticated) {
        onSessionExpired?.call();
        return;
      }
      if (!detail.hasValue) {
        detail = Section<Conversation>.failed(_friendly(e));
        notifyListeners();
      }
      // Detail failed but content exists → still try history refresh below.
    } catch (_) {
      if (_disposed || gen != _generation) return;
      if (!detail.hasValue) {
        detail = Section<Conversation>.failed(
            'This conversation is unavailable.');
        notifyListeners();
      }
    }

    // Reset fetches the NEWEST page regardless of pagination position.
    _nextPage = 1;
    await _loadMessagesPage(reset: true, gen: gen);
  }

  Future<void> _loadMessagesPage(
      {required bool reset, required int gen}) async {
    if (!reset) {
      if (loadingOlder || !hasMore) return;
      loadingOlder = true;
      notifyListeners();
    }
    try {
      // reset == true ALWAYS fetches the NEWEST page (page 1) regardless of
      // how far the user has paginated into older history — _nextPage then
      // resumes from page 2 and the merge dedupes any overlap.
      final page = reset ? 1 : _nextPage;
      final json = await api.fetchMessagesJson(channelId, page: page);
      if (_disposed || gen != _generation) return;
      final decoded = jsonDecode(json);
      final pageData = MessagesPage.fromJson(decoded);
      if (reset) {
        // MERGE (id-dedupe), never replace: keeps older pages the user
        // already scrolled up to load, and preserves reading position.
        final byId = <int, ChatMessage>{};
        for (final m in messages) {
          byId[m.id] = m;
        }
        // Server copy wins for content (captures edits), existing ids stay
        // (id-dedupe), and brand-new messages from the refresh are added.
        for (final m in pageData.chronological) {
          byId[m.id] = m;
        }
        final merged = byId.values.toList()..sort(_compare);
        messages = merged;
      } else {
        final seen = messages.map((m) => m.id).toSet();
        final older = pageData.chronological
            .where((m) => !seen.contains(m.id))
            .toList();
        messages = [...older, ...messages];
      }
      hasMore = pageData.currentPage < pageData.lastPage;
      _nextPage = pageData.currentPage + 1;
      if (reset) {
        // Mark-read the newest message (fire-and-forget) after a refresh —
        // only when the user is effectively at the bottom, i.e. the refresh
        // didn't land older-than-loaded content (merge keeps everything).
        _markReadUpToLatest();
      }
    } on ApiException catch (e) {
      if (_disposed || gen != _generation) return;
      if (e.kind == ApiErrorKind.unauthenticated) {
        onSessionExpired?.call();
        return;
      }
      if (reset && !detail.hasValue) {
        detail = Section<Conversation>.failed(_friendly(e));
      }
      // Older-page or refresh failure: keep current content.
    } catch (_) {
      if (_disposed || gen != _generation) return;
      if (reset && !detail.hasValue) {
        detail = Section<Conversation>.failed(
            'Messages are unavailable right now.');
      }
    } finally {
      if (!_disposed && gen == _generation) {
        loadingOlder = false;
        notifyListeners();
      }
    }
  }

  /// Load the next (older) page of history.
  Future<void> loadOlder() =>
      _loadMessagesPage(reset: false, gen: _generation);

  /// Refresh after returning to the screen (single-flight via generation).
  Future<void> refresh() => load();

  /// Pull-to-refresh inside the conversation: fetches the newest page and
  /// MERGES with id-dedupe — older loaded pages and the reading position are
  /// preserved (never a replace-then-jump).
  Future<void> refreshMessages() =>
      _loadMessagesPage(reset: true, gen: _generation);

  /// Send a text message. Single-flight prevents duplicate sends from
  /// rapid taps; the server-returned message is appended (deduped by id).
  Future<void> send(String body) async {
    final text = body.trim();
    if (sending || text.isEmpty) return;
    sending = true;
    notifyListeners();
    try {
      final json = await api.sendMessageJson(channelId, text);
      if (_disposed) return;
      final decoded = jsonDecode(json);
      final msg =
          decoded is Map ? ChatMessage.fromJson(decoded['message']) : null;
      if (msg != null && !messages.any((m) => m.id == msg.id)) {
        messages = [...messages, msg];
        _markReadUpToLatest();
      }
    } on ApiException catch (e) {
      if (e.kind == ApiErrorKind.unauthenticated) {
        onSessionExpired?.call();
        return;
      }
      rethrow; // caller shows a transient send error
    } finally {
      sending = false;
      if (!_disposed) notifyListeners();
    }
  }

  void _markReadUpToLatest() {
    if (messages.isEmpty) return;
    final last = messages.last;
    // Fire-and-forget: read-state is best-effort in 4A.1.
    unawaited(api.markRead(channelId, last.id).catchError((Object _) {}));
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _fallbackPoll?.cancel();
    _fallbackPoll = null;
    _eventCancel?.call();
    _eventCancel = null;
    _resyncSub?.cancel();
    _resyncSub = null;
    super.dispose();
  }
}

String _friendly(ApiException e) {
  switch (e.kind) {
    case ApiErrorKind.network:
      return 'No connection. Check your network and retry.';
    case ApiErrorKind.timeout:
      return 'The server took too long. Please retry.';
    case ApiErrorKind.rateLimited:
      return 'Too many requests — wait a moment and retry.';
    case ApiErrorKind.server:
      return 'The server had a problem. Please retry.';
    case ApiErrorKind.conflict:
      return 'You do not have access to this conversation.';
    default:
      return 'Something went wrong. Please try again later.';
  }
}
