# Task: Restore correct Flutter Development APK (1.5.55+71)

## Context
The published dev APK at `/apk/qolab-dev-latest.apk` was accidentally overwritten by a legacy
Capacitor build (v1.5.54, code 70, no libflutter.so). The Flutter project at `app/` still declared
`1.1.0+2`. Flutter branch `flutter-go` HEAD `2f0779a`, clean tree; `82b5282` and `2f0779a` unpushed.

## Changes
1. `app/pubspec.yaml`: version → `1.5.55+71` (single source of truth for Android versionName/versionCode).
2. `publish-dev-apk.sh` (repo root): default APK path → Flutter output
   `app/build/app/outputs/flutter-apk/app-debug.apk`; version read from `app/pubspec.yaml`
   (not the legacy `android/app/build.gradle`); add Flutter-identity guard (must contain
   `libflutter.so`, must NOT contain `assets/capacitor.config.json`).

## Acceptance criteria
- [ ] `flutter analyze` clean
- [ ] `flutter test` ≥ 211 passed, 1 skipped (baseline)
- [ ] Built APK manifest: versionName 1.5.55, versionCode 71, applicationId `dev.qolab.qolab_app`
- [ ] APK contains `lib/<abi>/libflutter.so`
- [ ] APK contains no `capacitor.config.json`
- [ ] APK built from `app/` at commit `2f0779a` (+ version-bump commit on top)
- [ ] Published `qolab-dev-latest.apk` + `qolab-dev-1.5.55.dev.apk` checksum-match the local build
- [ ] `latest.json` updated to versionName 1.5.55 / versionCode 71
- [ ] Publish script rejects an APK without libflutter.so (guard verified)
- [ ] Committed on `flutter-go`; pushed if GitHub access available (no force-push)

## Out of scope
Kotlin/Capacitor app, Qolab Web, Laravel backend, Go service, production deploy, new features,
BlueStacks verification (user-side).
