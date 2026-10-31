# Phase B — LiveKit Audio & Video Calling (Flutter Android)

Baseline: flutter-go @ 1a72833, APK 1.5.57+73, chat accepted.

## Architecture
- `lib/meetings/meeting_models.dart` — pure models: MeetingJoinToken (parse
  POST /meetings/{id}/join-token JSON: success/token/url, isUsable),
  MeetingEntry.
- `lib/meetings/call_controller.dart` — MeetingCallController (ChangeNotifier):
  join() = fetch join-token → LiveKitRoom.connect; status machine
  (idle→requestingToken→connecting→connected / reconnecting / failed / ended);
  participants list REMOTE-FIRST, local last (never local-only grid);
  toggleMic/toggleCamera/switchCamera; idempotent leave() with full teardown;
  dispose() cleans up if screen dropped without leave.
  Abstract LiveKitRoom + LiveKitEvent sealed classes → testable with fakes.
- `lib/meetings/livekit_adapter.dart` — production LiveKitRoomAdapter over
  package:livekit_client Room; maps RoomEvents to coarse LiveKitEvents;
  exposes videoTrackFor(identity) / localVideoTrack for renderers.
- `lib/meetings/call_screen.dart` — in-call UI: title + participant count
  badge, responsive grid (1/2/3/4 columns by participant count), avatar
  tiles for audio-only/camera-off, mic/camera/switch/leave controls,
  connecting + failed (Retry / Go back) states. Video rendering injected via
  videoRendererBuilder so tests run without native plugins.
- `lib/meetings/call_launcher.dart` — joinMeetingCall(): runtime permissions
  (mic → camera; camera denial = audio-only), pushes call screen with
  production adapter + VideoTrackRenderer tiles, disposes adapter on exit.
- `lib/api/qolab_api.dart` — +fetchMeetingJoinToken (401/403/404/other).
- `home_shell.dart` — Next Meeting card now tappable → Join call.
- AndroidManifest: RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, CAMERA (+FOREGROUND_SERVICE, WAKE_LOCK).

## Constraints honored
- No backend/Web/Kotlin/Capacitor changes; tokens never logged.
- Chat untouched.

## Tests (test/meeting_call_test.dart)
1. join-token parsing + errors
2. join/leave lifecycle (double-join, double-leave safe)
3. mic toggle
4. camera toggle (incl. audioOnly start → camera off)
5. remote participant track changes (join/leave/camera on-off-on)
6. multi-participant grid layout (columns 1/2/3)
7. cleanup + rejoin (leave → join fresh room, no ghosts)
8. permission/token failure → failed state with message
9. reconnect events (reconnecting → connected; failure → failed)
+ widget test: grid renders remote-first, controls present.

## Acceptance
- [x] unit/widget tests above green
- [x] flutter analyze no new issues
- [x] APK built, identity verified, published
- [ ] Multi-device live calling — NOT testable in this environment (needs two
  real authenticated participants); customer must verify in BlueStacks/web.
