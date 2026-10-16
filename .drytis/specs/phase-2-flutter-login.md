# Phase 2 — Flutter Login, Session Management & Authenticated Home

Branch: flutter-go (baseline 91104b9). Option A architecture (Flutter → Laravel direct, per Phase-1 audit).

## Files
- app/lib/api/cookie_jar.dart — Set-Cookie capture, rotation, expiry/deletion, XSRF unquoting
- app/lib/api/qolab_api.dart — HTTPS client, CSRF bootstrap (GET /login), POST /login,
  GET /dashboard Inertia parse, POST /logout; redirects NOT followed blindly
- app/lib/api/inertia.dart — Inertia page/props parser (auth.user)
- app/lib/api/api_errors.dart — error taxonomy (network/timeout/419/422/429/5xx/unauthenticated)
- app/lib/auth/session_store.dart — SessionStore iface + secure (flutter_secure_storage) + in-memory (tests)
- app/lib/auth/auth_controller.dart — ChangeNotifier auth state machine + restore + logout
- app/lib/screens/login_screen.dart — warm-light login UI, validation, error states
- app/lib/screens/home_screen.dart — real user identity from Inertia props, logout
- app/lib/auth_gate.dart — startup gate: check → Home | Login | offline-retry
- app/lib/main.dart — bootstrap, QOLAB_ORIGIN via String.fromEnvironment
- AndroidManifest.xml — add INTERNET permission only
- pubspec.yaml — flutter_secure_storage; version bump
- .github/workflows/build-apk.yml — --dart-define=QOLAB_ORIGIN
- tests: cookie jar, CSRF/url-decode, API flows (mock HttpServer), controller states,
  duplicate-submit lock, session restore/expiry, login form widget tests

## Acceptance criteria
- [ ] Login validates email/password client-side; show/hide password; button disabled while in flight
- [ ] CSRF bootstrap via GET /login; XSRF-TOKEN URL-decoded; X-XSRF-TOKEN on mutations
- [ ] POST /login: 2xx success; 422 field errors; 419 re-bootstrap path; 429 message; 5xx message; network → retry
- [ ] Session cookies persisted in secure storage; restored on launch; validated via /dashboard
- [ ] 302→/login treated as expired: stored session cleared, user returned to Login
- [ ] Offline at startup keeps stored session, shows recoverable connection state (no data deletion)
- [ ] Home shows real name/email from auth.user; missing fields omitted
- [ ] POST /logout + local clear; back navigation cannot return to Home
- [ ] flutter analyze / flutter test / go vet / go test all green
- [ ] CI builds APK with --dart-define origin; artifact uploaded
- [ ] android-dev untouched; no Laravel/Go/Production changes; no secrets committed

## Out of scope
Rooms/Chat/Meetings/Camera; Go changes; token auth; background push; release signing.
