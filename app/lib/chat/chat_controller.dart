import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../api/api_errors.dart';
import '../api/inertia.dart';
import '../api/qolab_api.dart';
import 'chat_models.dart';

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
  ChatListController({required this.api, this.onSessionExpired});

  final QolabApi api;
  final void Function()? onSessionExpired;

  Section<ChatIndex> index = Section<ChatIndex>.loading();

  /// Unread counts keyed by channel id — independent of [index]; failure
  /// leaves the map null (never fabricated).
  Map<int, int>? unread;

  int _generation = 0;
  bool _loadingDm = false;
  bool _disposed = false;

  bool get startingDm => _loadingDm;

  /// Load (or refresh) the conversation list + unread counts.
  Future<void> refresh() async {
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
  });

  final QolabApi api;
  final int channelId;
  final int myUserId;
  final void Function()? onSessionExpired;

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

  /// Initial load / refresh: channel detail + first (newest) history page.
  Future<void> load() async {
    final gen = ++_generation;
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
      final json =
          await api.fetchMessagesJson(channelId, page: _nextPage);
      if (_disposed || gen != _generation) return;
      final decoded = jsonDecode(json);
      final pageData = MessagesPage.fromJson(decoded);
      if (reset) {
        messages = pageData.chronological;
      } else {
        final seen = messages.map((m) => m.id).toSet();
        final older = pageData.chronological
            .where((m) => !seen.contains(m.id))
            .toList();
        messages = [...older, ...messages];
      }
      hasMore = pageData.currentPage < pageData.lastPage;
      _nextPage = pageData.currentPage + 1;
      if (reset) _markReadUpToLatest();
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
