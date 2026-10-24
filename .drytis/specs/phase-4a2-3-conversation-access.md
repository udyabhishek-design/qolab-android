# Phase 4A.2.3 — Critical Conversation Access Failure

**Status:** Implemented; awaiting BlueStacks manual verification.
Baseline: flutter-go @ 82b5282. Delivery: Flutter Android only.

## Root cause (confirmed live against Development)

Opening any conversation showed "You do not have access to this
conversation." for EVERY conversation, Retry never helped.

1. Laravel `APP_URL` is configured on Development, so session-expiry
   redirects carry ABSOLUTE Locations:
   `302 → Location: https://workpulse-gbpedt.drytis.dev/login`
   (verified live for `/chat` and `/chat/channels/58`).
2. `ApiResult.redirectedToLogin` (qolab_api.dart) matched only
   RELATIVE paths (`/login`), so the absolute redirect was not
   recognized as session expiry.
3. `fetchChannelDetailHtml` then classified ANY 301/302 as
   `ApiErrorKind.conflict` ("This conversation is not available in
   Chat.") — and `_friendly()` rendered conflict as "You do not have
   access to this conversation."
4. The Chat list kept its last-good cached content, so only
   conversation OPEN failed — matching the reported symptoms exactly.

The stored session had expired (likely from the QA password rotation
during 4A.2.2 verification); the misclassification turned that into a
false access-denied message.

## Fix (Flutter-only)

- `lib/api/qolab_api.dart`
  - `ApiResult._isLoginLocation`: normalizes absolute URLs and matches
    `/login` in both relative and absolute forms.
  - `fetchChannelDetailHtml`: 301/302 is conflict ONLY when the
    destination is a room route (`/rooms/...`); other redirects are
    `server` errors; 403 → forbidden.
  - `startDm` 403 → forbidden (was conflict).
- `lib/api/api_errors.dart`
  - New `ApiErrorKind.forbidden`; `apiErrorFromStatus` maps 403 to it.
- `lib/chat/chat_controller.dart`
  - `_friendly`: distinct messages per kind — network / timeout /
    rate-limit / server / csrfExpired / unauthenticated (session
    expired) / forbidden (no access) / conflict (room only).
  - `ConversationController.load()`: clears a stale error section so
    Retry visibly transitions to loading and never lingers.

## Error messages contract

| Failure | Message |
|---|---|
| Session expired | "Your session has expired. Please sign in again." (also routes to sign-in via onSessionExpired) |
| 403 | "You do not have access to this conversation." |
| Network | "No connection. Check your network and retry." |
| Server | "The server had a problem. Please retry." |
| Room redirect | "This conversation is not available in Chat." |

## Regression tests (app/test/chat_conversation_access_test.dart, 12 tests)

1. Absolute-URL login redirect → unauthenticated (the root cause).
2. Chat index absolute login redirect → unauthenticated.
3. DM detail + history load.
4. Group channel detail load.
5. ConversationController.load() end-to-end.
6. Retry after transient 503 recovers.
7. Error→loading transition on retry.
8. Genuine 403 → forbidden + message.
9. Room redirect → conflict + "not available in Chat" (never "no access").
10. Unrelated redirect → server (never "no access").
11. Session expiry triggers onSessionExpired, NOT access-denied.
12. Send appends to history after open.

## Preserved features

All 4A.2.2 work intact: unread badge, resume catch-up, socket
lifecycle, ref-counted subscriptions. Full suite 223 tests green.

## Delivery

- APK 1.5.54 (versionCode 70) published:
  https://workpulse-gbpedt.drytis.dev/apk/qolab-dev-latest.apk
- BlueStacks verification PENDING (no emulator in container).
