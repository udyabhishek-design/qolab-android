# Phase 4A.2.2 — Resume sync, unread race, subscription lifecycle, Chat nav badge, runtime diagnostics

Baseline: flutter-go @ cb16675. Flutter Android app only (`app/`).

## Root cause analysis (code-confirmed, ranked)

### RC1 — ChatListScreen never wires the socket into ChatListController (CRITICAL)
`chat_list_screen.dart:73`: `chat = widget.controller ?? ChatListController(api: widget.api);`
The `socket` field exists on ChatListController and `attachUserChannel` is called
immediately after (`socket != null && myUserId > 0`), but the constructor-created
controller has `socket == null` because ChatListScreen passes `widget.socket` to
`attachUserChannel` (which uses the controller's own `socket` field — always null
in the real app). Result: `attachUserChannel` early-returns (`socket == null`)
and the **user notification channel is never subscribed** and **onResync catch-up
never listens** in the real APK. Incoming messages surface only via the 5-minute
reconcile timer or manual refresh — exactly the confirmed failure.

### RC2 — `ChatSocketService.resume()` silently exits with zero listeners
`chat_socket.dart:89`: `if (_disposed || _listeners.isEmpty) return;` skips both
the reconnect and the onResync fire. On app resume with the Chat tab not yet
mounted (e.g. on Home after cold start), no REST catch-up runs.

### RC3 — `_bumpUnread` drops the increment when the unread map isn't loaded
`chat_controller.dart:145-149`: `unread?[channelId] == null → return` silently
discards the notification; the count is only restored by the later 60s poll.

### RC4 — `ConversationController._attachRealtime()` re-runs on every `load()`/`refresh()`
`chat_controller.dart:336-344` + `load()` at 444: every `load()` calls
`_attachRealtime()` which unconditionally `subscribe()`s and re-listens to
`onResync` — leaking ref-counted listeners (slow leak, one per refresh) and
duplicating event handling.

### RC5 — `trackSubscription` uses a `List` (duplicates accumulate)
`reverb_client.dart:254`: `_desiredSubscriptions.add(channel)` on an already-
tracked channel appends a duplicate; `_restoreSubscriptions` then sends N
duplicate subscribe frames per reconnect. With RC4's leak this compounds.

## Fixes

1. **RC1**: `ChatListController` constructor takes socket; ChatListScreen passes
   `widget.socket` through. Preserve `attachUserChannel(userId)` call.
2. **RC2**: `resume()` always fires onResync; only calls `connect()` when the
   client has tracked subscriptions (no useless WebSocket).
3. **RC3**: `_bumpUnread` with `null` map triggers a server reconciliation
   (bounded — single-flight, generation-guarded) instead of silently dropping;
   `attachUserChannel` eagerly fetches unread counts.
4. **RC4**: `_attachRealtime` guarded by `_eventCancel == null` — attach exactly
   once per controller lifetime.
5. **RC5**: `trackSubscription` dedupes (Set-like semantics).
6. **Badge**: hoist ChatListController ownership to HomeShell; `_BottomNav`
   receives `chatUnread` (int? — null = unknown → hidden) and renders a badge on
   the Chat item using the `_NotificationBell` pattern; cap 99+, hide at zero.
   Updates via the hoisted controller's notifyListeners through the existing
   `_onChanged` shell listener.
7. **Diagnostics**: debug-gated `debugPrint` at connection established, subscribe
   sent/succeeded/failed(auth), event received (channel+event name only), and
   controller append — no credentials/tokens/bodies.

## Acceptance criteria

- [ ] Unit: resume() with zero listeners fires onResync, does not connect.
- [ ] Unit: resume() with subscriptions but disconnected calls connect().
- + [ ] Unit: ChatListScreen wiring — controller constructed with socket (RC1) —
    integration-style test asserting attachUserChannel subscribes user channel.
- [ ] Unit: notification before unread map loads → reconciliation (unread
    fetch) called, not silent drop; after load → +1 exactly once, no double.
- [ ] Unit: load() twice → single subscription, single resync listener.
- [ ] Widget: badge shows count, hides at zero, caps 99+; updates on
    controller change; visible on Home tab.
- [ ] Widget: badge clears after messages read (unread map zeroed).
- [ ] flutter analyze clean; full suite green (4A.2.1 tests preserved).
- [ ] Live runtime probe (Dev, two accounts, DM 57): connection, subscription,
    event receipt, controller append — logged and verified via the app's own
    stack; honest result reported.
- [ ] BlueStacks/real-APK verification: PENDING unless emulator appears —
    reported as such, never claimed fixed without runtime evidence.

## Out of scope

Push notifications (FCM), Web/Laravel/Go changes, navigation redesign.
