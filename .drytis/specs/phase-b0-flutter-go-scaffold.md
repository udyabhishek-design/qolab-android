# Task: Phase B0 — Flutter + Go greenfield scaffold (new branch)

## Decision
Option B chosen: **New Flutter app + new Go backend**. All new work happens on a **new branch `flutter-go`** in `/home/coder/qolab-android`. Everything up to now stays untouched on `android-dev` (legacy native stack, HEAD 7da90f7).

## Out of scope
- Pushing to GitHub — blocked until customer supplies a **replacement scoped key** (current PAT is exposed + over-scoped; must be revoked).
- CI workflow file (next ticket once repo exists).
- Release signing (debug APK for now).

## Files to create (on flutter-go only)
```
server/
  go.mod           module qolab/server, go 1.24
  main.go          HTTP server, GET /health -> 200 {"status":"ok"}
  main_test.go     httptest coverage for /health
app/               flutter create (org dev.qolab), minimal placeholder UI
  lib/main.dart    minimal app shell
  test/widget_test.dart
.github/workflows/build-apk.yml  (deferred — next ticket)
```

## Acceptance criteria
- [ ] Branch `flutter-go` exists; `android-dev` unchanged (diff vs 7da90f7 = empty on that branch)
- [ ] `go test ./...` green in server/
- [ ] `flutter analyze` clean; `flutter test` green in app/
- [ ] `flutter build apk --debug` succeeds locally (CI will build release)
- [ ] No changes to /workspace web repo, Development backend, or Production

## Notes
- Board API 404 at ticket-creation time (standing customer bypass).
- Go 1.24.4 + Flutter 3.24.5 installed in container this session (PATH: /home/coder/flutter/bin).
