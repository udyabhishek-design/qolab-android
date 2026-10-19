# Qolab Mobile Development — Toolchain, Configuration & Install Record

Last updated: 2026-10-19 (after Flutter SDK upgrade 3.24.5 → 3.27.4).
Scope: Qolab Flutter Android app (`app/`, branch `flutter-go`) + legacy native
Android (`android/`, branch `android-dev`) + Go service (`server/`).
Repo: github.com/udyabhishek-design/qolab-android.

> This file is the single source of truth for the mobile dev environment.
> When the container is replaced (every ~10 min idle), an agent or human
> returning to this project should follow §4 "Warm-container restore" first.

---

## 1. Toolchain versions (current)

| Component | Version | Location |
|---|---|---|
| Flutter SDK | **3.27.4** (stable, Dart **3.6.2**) | `/home/coder/flutter` |
| Dart SDK | 3.6.2 (bundled with Flutter) | `/home/coder/flutter/bin/cache/dart-sdk` |
| Go | 1.24.4 (linux/amd64) | `/usr/local/go` ( reinstall on wipe, see §4 ) |
| Node.js | v20.20.2 | system |
| npm | 10.8.2 | system |
| JDK | 21 (required by Gradle 8.14 / AGP 8.7.3) | system (`java -version`) |
| Android SDK | platform tools + build tools | `/home/coder/android-sdk` |
| GRADLE_USER_HOME | `/var/gradle-home` | off `/home/coder` (disk) |
| PUB_CACHE | `/var/pub-cache` | off `/home/coder` (disk) |

### Why 3.27.4
`livekit_client` (Flutter LiveKit SDK, current 2.7.0) requires
**Flutter ≥ 3.27 / Dart ≥ 3.6**. The project was previously pinned to
Flutter 3.24.5 (Dart 3.5.4), which blocks Phase 4B/4C calling work.
3.27.4 is the last 3.27.x stable release (Dart 3.6.2). Approved by the
customer on 2026-10-19 (request said "3.47"/"3.6/3.7" — no 3.47 exists;
3.27.4 = Dart 3.6.2 is the matching stable).

The 3.27.4 upgrade was verified against the Phase-3 baseline app:
`flutter pub get`, `flutter analyze` (0 issues after one style fix),
`flutter test` → **99 passed + 1 skipped** (98 before; the skipped
placeholder unchanged), `go vet` + `go test` green.

---

## 2. Flutter SDK installation (from open-source release)

Canonical steps used (repeat if `/home/coder/flutter` is ever missing):

```bash
# 1. Discover available stable releases (open-source release manifest)
curl -sSL https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json \
  -o /tmp/releases.json
python3 - <<'EOF'
import json
d = json.load(open('/tmp/releases.json'))
for r in d['releases']:
    if r['channel'] == 'stable' and r['version'].startswith('3.27'):
        print(r['version'], r['dart_sdk_version'], r['archive']); break
EOF
# → 3.27.4 3.6.2 stable/linux/flutter_linux_3.27.4-stable.tar.xz

# 2. Download + extract to /home/coder (NOT into the repo)
cd /home/coder
curl -sSL -o flutter.tar.xz \
  "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.27.4-stable.tar.xz"
tar xf flutter.tar.xz && rm flutter.tar.xz

# 3. Use
export PATH="/home/coder/flutter/bin:$PATH"
flutter --version   # Flutter 3.27.4 • Dart 3.6.2
```

Notes:
- The release manifest URL is
  `.../releases/releases_linux.json` (NOT `/stable/releases_linux.json`
  — that key 404s as NoSuchKey).
- `flutter_linux_3.27.4-stable.tar.xz` ≈ 767 MB download / ~1.5 GB extracted.
  `/home/coder` is a ~9.8 GB volume: delete `/home/coder/qolab-android/app/build`
  (~950 MB, regenerable) FIRST or the disk fills (98% full observed).

### CI pin (GitHub Actions)
`.github/workflows/build-apk.yml` → `flutter-version: 3.27.4`
(was 3.24.5). Keep this in sync with the local SDK on every upgrade —
a mismatch silently builds the APK with the wrong Dart version.

---

## 3. Go, Node, npm configuration

### Go 1.24.4
Container wipes remove `/usr/local/go`. Reinstall (verified URL — note
`dl.google.com`, NOT `go.dev/dl/...` which returns an HTML redirect):

```bash
cd /tmp && rm -f go.tgz
curl -sSL -o go.tgz "https://dl.google.com/go/go1.24.4.linux-amd64.tar.gz"
sudo tar -C /usr/local -xzf go.tgz && rm go.tgz
export PATH="/usr/local/go/bin:$PATH" && go version
# Then: cd server && go vet ./... && go test ./...
```

### Node / npm
Node v20.20.2 + npm 10.8.2 are system-provided; nothing to install.

**npm install (repo root `/home/coder/qolab-android`):**

```bash
cd /home/coder/qolab-android && npm install
```

- `package.json` currently has **no dependencies** (the Capacitor entries in
  `package-lock.json` are stale Phase-1 legacy; `npm install` with the
  restored lockfile audits 0 packages — "found 0 vulnerabilities", empty
  tree, and `git diff package-lock.json` shows the legacy entries being
  dropped. That is CORRECT for the Flutter pipeline: the Flutter app builds
  via Gradle + `flutter build`, not Capacitor. If legacy `cap:sync` /
  `build:apk` scripts are ever needed again, re-add
  `@capacitor/core|cli|android` ^7.x to package.json first.)
- Future Flutter-relevant npm tooling (if ever added) goes in this same
  package.json; keep `node_modules` out of git (already ignored).

---

## 4. Warm-container restore (CHECK THIS FIRST after any replacement)

Recurring symptoms after container replacement, all observed repeatedly:

| Symptom | Cause | Fix |
|---|---|---|
| `Permission denied` on `/var/pub-cache` | pub cache volume re-owned | `sudo mkdir -p /var/pub-cache /var/gradle-home && sudo chown -R coder:coder /var/pub-cache /var/gradle-home` |
| `flutter pub get` fails / uri_does_not_exist | pub cache wiped | recreate dirs (above) then `flutter pub get` in `app/` |
| `go: command not found` | Go wiped | reinstall per §3 |
| `/home/coder` 98–100% full | `app/build` regenerated | `rm -rf /home/coder/qolab-android/app/build` |
| JDK missing/old | wipe | need JDK 21 for Gradle 8.14 |

Full restore sequence:

```bash
sudo mkdir -p /var/pub-cache /var/gradle-home \
  && sudo chown -R coder:coder /var/pub-cache /var/gradle-home
# (Go if needed — §3)
cd /home/coder/qolab-android/app
export PATH="/home/coder/flutter/bin:/usr/local/go/bin:$PATH"
export PUB_CACHE=/var/pub-cache GRADLE_USER_HOME=/var/gradle-home
flutter pub get && flutter analyze && flutter test
```

## 5. Project layout & build pipeline

| Path | What |
|---|---|
| `app/` | Flutter Android app (phases 2+ work) — `flutter test` / CI APK |
| `android/` | Legacy native Android (android-dev branch context; JDK21+Gradle8.14+AGP8.7.3, minSdk per gradle.properties) |
| `server/` | Go service — `go vet ./... && go test ./...` (health-check only) |
| `.github/workflows/build-apk.yml` | CI: `apk` job (flutter-version **3.27.4**, `--dart-define=QOLAB_ORIGIN=https://workpulse-gbpedt.drytis.dev`, artifact `qolab-dev-debug-apk`) + `go-test` job; GITHUB_TOKEN only |
| `app/android/app/src/main/AndroidManifest.xml` | main manifest — only `INTERNET` today; Phase 4 adds RECORD_AUDIO / CAMERA / POST_NOTIFICATIONS etc. |

APK builds happen ONLY via GitHub Actions (no local APK builds).
Local verification stops at `flutter analyze` + `flutter test`.

## 6. Phase 4 readiness notes (from the 2026-10-19 audit)

- `livekit_client` 2.7.0 needs Dart ≥3.6 — **now satisfied** by 3.27.4.
- Realtime: Reverb Pusher protocol 7, public app key `E6bKH3QNfxtlNpk7ByiX`,
  `wss://workpulse-gbpedt.drytis.dev:443/app/{key}`, private auth via
  `POST /broadcasting/auth` with the existing session cookie + CSRF.
- Planned Flutter deps for Phase 4 (not yet installed — needs approval):
  `web_socket_channel`, `livekit_client`, `permission_handler`, file picker.
- 1:1 call lifecycle does NOT exist server-side; FCM absent (no
  google-services.json) — see the full audit report in the session of
  2026-10-19 before starting 4B.

## 7. Change log for this file

- 2026-10-19: Flutter 3.24.5 → 3.27.4 (Dart 3.6.2); CI pinned 3.27.4;
  npm install documented (legacy Capacitor lock entries explained);
  one analyze style fix in home_shell.dart (curly braces, no behavior
  change); full suite re-verified green.
