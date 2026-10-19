# Phase 4A.1.1 — Chat List Tabs (All / Direct / Groups)

Baseline: `c0001af` on `flutter-go`. Approved 2026-10-19 (board API 404 —
standing bypass per phase spec §2).

## Goal
Replace the sectioned conversation list (Direct messages, then Groups &
channels) with three selectable tabs placed below the Chat heading and above
the list:

- **All** (default): DMs + groups + messaging channels, sorted by latest
  activity (`last_message_at`), newest first. Stable fallback for missing
  timestamps (null sorts last; stable by id within same key).
- **Direct**: only `type == 'dm'`, sorted by latest activity.
- **Groups**: `group`/`company`/`department` channels (rooms already excluded
  by parser), sorted by latest activity.

## Requirements
- Tabs must NOT trigger backend requests (client-side filtering of loaded
  ChatIndex).
- Search filters within the selected tab.
- Preserve: search control, new-conversation action, conversation names,
  unread badges, timestamps shown today, empty/loading/error/retry states.
- Selected tab stable when opening and returning from a conversation
  (state lives in the screen State, survives push/pop).
- No WebSockets/polling; no auth changes; no new deps.

## Files
- `app/lib/screens/chat_list_screen.dart` — add `_ChatTab` enum + tab row,
  activity-sorted per-tab lists, single list render (no section labels in
  All/Direct; Groups tab keeps "Channels" divider between group-like and
  company/department channels? NO — keep single flat sorted list per tab).
- `app/test/chat_widget_test.dart` — new tab tests.

## Timestamp reality (live dev check 2026-10-19)
`last_message_at` is present on the channel objects from GET /chat but NULL
for channels with no messages ever (e.g. company `announcements`, `general`,
group `My test grp`). Sorting: newest first, nulls last, stable within ties.

## Acceptance criteria
- [ ] Three tabs render below heading, above search+list (search stays above
      list, below tabs per spec: "below the Chat heading and above the
      conversation list" — tabs go between heading and search).
- [ ] All tab mixes DMs + groups sorted by last_message_at desc.
- [ ] Direct tab shows only DMs; Groups tab shows only group-like.
- [ ] Search filters within active tab.
- [ ] Tab switch: no chat.refresh() call (verify via listener/flag).
- [ ] Selected tab preserved across conversation open/return.
- [ ] Empty/loading/error/retry states unchanged.
- [ ] flutter analyze clean; full flutter test green; go vet/test green.

## Tests (widget + sorting)
- Sorting: mixed last_message_at (some null) → newest first, nulls last,
  stable ties.
- All/Direct/Groups tab contents.
- Search within tab.
- No refresh on tab switch.
- Tab preserved on return.
