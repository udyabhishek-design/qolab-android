# Conversation UX Fixes — loading state, open-at-latest, date separators (Flutter Android)

Task: three Chat conversation UI fixes in the Flutter app (`/home/coder/qolab-android`, branch `flutter-go`). No changes to Web, Laravel, Go, legacy Kotlin, Capacitor, or Production. No changes to realtime delivery internals.

## Root causes
1. **Empty-state flash**: `ConversationScreen._history()` keys off `detail.isLoading`, but `detail` (header fetch) completes before the messages page fetch begins → brief `messages.isEmpty && !loading` window renders "No messages yet".
2. **Opens at oldest**: forward `ListView` starts at scroll offset 0 (oldest); the `didChangeDependencies` `jumpTo(maxScrollExtent)` fires before history exists, and the follow-up `animateTo` scrolls visibly through history / breaks under keyboard insets.
3. **No separators / UTC times**: no day grouping existed; `_time()` formatted the UTC-parsed `createdAt` without `toLocal()`.

## Changes
- `app/lib/chat/chat_controller.dart`: expose `initialHistoryLoading` (true from `load()` until the first reset page completes). No delivery-logic changes.
- NEW `app/lib/chat/message_formatting.dart`: pure helpers — `chatTimeLabel(DateTime)`, `dayLabel(DateTime local)` (Today / Yesterday / `d MMMM yyyy`), `startsWithNewDay(DateTime olderOrFirst, DateTime? newer)` — all convert via `toLocal()`.
- `app/lib/screens/conversation_screen.dart`:
  - Loading: skeleton placeholder bubbles while `messages.isEmpty && (detail.isLoading || initialHistoryLoading)`; empty state only after a completed fetch (`historyEmpty`).
  - Reverse list (`reverse: true`): offset 0 = newest → conversation opens at the latest message deterministically, keyboard-safe, no animation. Load-older triggers near the visual top; spinner rendered at the top end.
  - Date separator chip inside the first (chronologically) message item of each local day.
  - Timestamps via `chatTimeLabel` (local).
  - Follow-tail: when a new newest message arrives and user is near offset 0 → short animate; otherwise a "New messages" jump pill appears; tap → animate to 0. Scroll position preserved when reading older history.
  - Remove the `didChangeDependencies` jump hack.

## Acceptance criteria
- [ ] No "No messages" while initial fetch is in flight; skeleton instead
- [ ] "No messages yet" only after a successful fetch that returned empty
- [ ] Conversation opens showing the newest message, no animated scroll
- [ ] New message while near newest → auto-follow
- [ ] New message while reading older → position preserved + New-messages pill; tap jumps to newest
- [ ] Load-older pagination preserved (trigger moved to the older end)
- [ ] Date separators: Today / Yesterday / `d MMMM yyyy`, local timezone, no duplicates on append/pagination
- [ ] Timestamps in device-local time
- [ ] Existing suite green; `flutter analyze` clean
- [ ] APK versionName/versionCode incremented; identity verified; published
- [ ] Committed + pushed to `flutter-go`

## Tests (test/conversation_ux_test.dart + label unit tests)
1. Loading state shows skeleton, not empty-state; completed-empty shows empty state
2. Newest message visible immediately after first pump (oldest not built — lazy builder)
3. New message while near bottom is visible (follow-tail)
4. New message while scrolled into older history: not auto-jumped, pill shown, tap → visible
5. Separators across three days incl. Today/Yesterday
6. Timezone/day-boundary: `dayLabel`/`chatTimeLabel` agree with `toLocal()`; UTC near-m midnight maps to the local day
7. loadOlder prepends older messages; newest stays anchored (reverse offset stable)
