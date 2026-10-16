# Qolab Android — greenfield (Flutter app + Go backend)

Branch layout:
- `android-dev` — legacy native Java/Kotlin app + Capacitor shell (through v1.5.53). FROZEN, do not build on it.
- `flutter-go`  — NEW stack (option B): Flutter UI (`app/`) + Go backend (`server/`). All new work happens here.

## Flutter app (`app/`)
- Flutter 3.24 stable, Material 3, org `dev.qolab`, app id `dev.qolab.qolab_app`.
- Build locally (container has small `/home/coder` — caches are redirected):
  ```
  export PATH=$PATH:/home/coder/flutter/bin
  export GRADLE_USER_HOME=/var/gradle-home PUB_CACHE=/var/pub-cache
  export ANDROID_HOME=/home/coder/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  cd app && flutter build apk --debug
  ```
- `flutter analyze` must stay clean; every change ships with widget/unit tests.

## Go backend (`server/`)
- Go 1.24, stdlib net/http only (no framework yet).
- `go test ./...` green. `GET /health` → 200 `{"status":"ok"}`.

## CI
- `.github/workflows/build-apk.yml` — on push to `flutter-go`: flutter analyze+test+debug APK artifact, go vet+test.
- CI uses the built-in GITHUB_TOKEN; the owner's PAT is only for pushing FROM this container (and should be a scoped replacement, never committed).

## Known local-environment gotchas (this container)
- `/home/coder` is a 9.8GB disk that FILLS UP; Gradle caches live at `/var/gradle-home` + `/var/gradle-caches`, pub cache at `/var/pub-cache` (symlinked). Clean `app/build` + `android/.gradle` when tight.
- JDK vanishes on container replacement: `sudo apt-get install -y openjdk-21-jdk-headless`.
- Flutter SDK: `/home/coder/flutter` (needs `git config --global --add safe.directory /home/coder/flutter` after fresh containers).
