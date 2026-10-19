# Phase 4A.2 — Real-Time Chat (Laravel Reverb) + Scroll Restoration

Baseline: `1ced7b2` on `flutter-go` (verified == local == remote, clean tree).
Audit: see conversation audit 2026-10-19 (Phase 4A.2 read-only report) +
memory `phase-4a1-1-chat-list-tabs.md`, `phase-4a1-chat-rest-status.md`.

## Scope
Flutter app/ ONLY. No Laravel/Web/Go/native-Android/Production changes.
No calling, no FCM, no edit/delete UI controls.

## Architecture
- `app/lib/chat/reverb_client.dart` — Pusher protocol 7 over `dart:io`
  WebSocket. WSS to `{origin}/app/E6bKH3QNfxtlNpk7ByiX?protocol=7&client=flutter`.
  Public app key from verified dev config (ships in web bundle — not secret).
  Connection states: disconnected/connecting/connected. Bounded exponential
  reconnect w/ jitter (1s→30s cap, resets after stable 60s). Server `ping` →
  `pong` reply; client-side inactivity watchdog.
- `app/lib/chat/broadcast_auth.dart` — POST /broadcasting/auth, FORM-ENCODED
  channel_name+socket_id, session cookies + X-XSRF-TOKEN + X-Requested-With
  (audit-verified: JSON body → 403). Uses QolabApi request infra. Never logs
  auth payloads. Auth failure (403/401) on a channel = surface + do NOT retry
  that subscription (until manual refresh/reconnect).
- `app/lib/chat/chat_socket.dart` — ChatSocketService singleton-ish object
  owned by the auth/session layer: connects after login, disconnects on
  logout/session-expiry; exposes typed event streams per channel; tracks
  subscriptions with ref-counting; no stale events after logout (generation
  token). Reconnect → re-auth + re-subscribe + emit `onResubscribed`.
- ConversationController: subscribe before history fetch (subscribe-then-fetch
  ordering), append-dedupe by id (existing seen-set logic), edited → in-place
  update, deleted → remove if for_everyone. Single-flight send unchanged;
  REST response + Reverb echo dedupe by id.
- ChatListController: subscription to `private-user.{me}`; notification.received
  with channel_id/message_id → patch lastMessageAt (event data only), unread
  increment (only if not currently active conversation), resort; unknown
  channel → controlled refresh(). Unread source of truth remains
  /chat/unread-counts, reconciled: after mark-read, after reconnect/resume,
  and on a slow cadence while healthy (5 min) — never double counts.
- REST fallback: when disconnected AND conversation screen visible: poll
  messages newest page every 15s (only newest page — dedupe handles overlap),
  unread-counts every 30s on list screen visible. Healthy-socket reconciliation:
  every 5 min, one unread-counts fetch. All timers cancelled on dispose/
  visibility change — no accumulation. Documented intervals.
- Lifecycle: WidgetsBindingObserver (resume → reconnect + catch-up refresh);
  logout disposes socket; repeated login/logout cycles must not leak sockets
  (dispose force-closes, generation token invalidates old streams).

## Scroll restoration (chat_list_screen.dart)
- Per-tab `PageStorageKey('chat-list-{tab}')` on the ListView.
- Keep list mounted during refresh: when content exists, refresh keeps old
  list (Section last-good already does) and never swaps subtree to loading.
- Reorder-without-jump: on realtime re-sort, keep current scroll offset
  (ScrollController) — items reflow; new messages arriving do not scroll the
  list away from user position. Optional anchor: not needed if we simply don't
  auto-scroll.
- Retain _tab (already State field) and _query.

## Acceptance criteria
- [ ] WSS handshake + socket_id parse (unit w/ fake socket)
- [ ] Form-encoded broadcast auth success + 403 handling (unit vs MockLaravel)
- [ ] Subscribe before fetch; no missed-gap (unit)
- [ ] message.sent append + dedupe incl. REST/WS double delivery (unit)
- [ ] out-of-order ids → chronological insert (unit)
- [ ] edited/deleted sync (unit)
- [ ] list reorder on notification.received + no double unread (unit)
- [ ] unread reconciliation after mark-read/reconnect/resume (unit)
- [ ] disconnect → reconnect restores subscriptions + REST catch-up (unit)
- [ ] logout disposes socket; no stale events post-logout (unit)
- [ ] scroll restoration per tab + across conversation open/return (widget)
- [ ] reorder doesn't jump scroll (widget)
- [ ] fallback polling starts/stops correctly; no timer leak (unit)
- [ ] analyze clean; full suite green; go vet/test green
- [ ] Dev integration check (two accounts, real Reverb) where feasible
- [ ] CI APK artifact

## Tests
test/reverb_client_test.dart (fake WebSocket via injectable factory),
test/chat_socket_test.dart (subscription lifecycle, auth failure, reconnect),
chat_controller_test.dart additions (WS events), chat_widget_test.dart
additions (scroll restore, reorder).
