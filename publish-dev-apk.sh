#!/usr/bin/env bash
# ── Qolab FLUTTER Development APK publisher ──────────────────────
# Run AFTER a successful `flutter build apk --debug` in /home/coder/qolab-android/app.
# Publishes the FLUTTER build only:
#   1. versioned archive  /workspace/public/apk/qolab-dev-<version>.dev.apk
#   2. stable APK         /workspace/public/apk/qolab-dev-latest.apk
#   3. manifest           /workspace/public/apk/latest.json
# The manifest is written LAST and atomically (tmp + mv) so it can never
# disagree with the APK.
#
# GUARD: this script refuses to publish anything that is not a Flutter build.
# It checks the APK contains libflutter.so and does NOT contain the Capacitor
# runtime (capacitor.config.json). The version source of truth is the Flutter
# project's app/pubspec.yaml — NOT the legacy root android/ Gradle config.
# (An earlier release accidentally published the legacy Capacitor APK through
# the old version of this script; this guard prevents a repeat.)
set -euo pipefail

APK="${1:-/home/coder/qolab-android/app/build/app/outputs/flutter-apk/app-debug.apk}"
OUT="/workspace/public/apk"
PUBSPEC="/home/coder/qolab-android/app/pubspec.yaml"

if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found: $APK — run 'flutter build apk --debug' in app/ first." >&2
  exit 1
fi

# ── Flutter identity guard ────────────────────────────────────────
FLUTTER_SO=$(unzip -l "$APK" 2>/dev/null | grep -c 'lib/[^/]*/libflutter\.so' || true)
if [[ "$FLUTTER_SO" -eq 0 ]]; then
  echo "ERROR: $APK is not a Flutter build (no libflutter.so found). Refusing to publish." >&2
  echo "       Build the Flutter app:  cd /home/coder/qolab-android/app && flutter build apk --debug" >&2
  exit 1
fi
if unzip -l "$APK" 2>/dev/null | grep -q 'assets/capacitor\.config\.json'; then
  echo "ERROR: $APK contains the Capacitor runtime (assets/capacitor.config.json) — this is the legacy app. Refusing to publish." >&2
  exit 1
fi

# ── Version from Flutter pubspec (source of truth) ────────────────
VERSION_LINE=$(grep -E '^version:' "$PUBSPEC" | head -1 | tr -d ' ' | sed 's/^version://')
VERSION_NAME="${VERSION_LINE%%+*}"
VERSION_CODE="${VERSION_LINE##*+}"
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" || "$VERSION_NAME" == "$VERSION_LINE" ]]; then
  echo "ERROR: could not parse version (name+code) from $PUBSPEC" >&2
  exit 1
fi

# Cross-check the APK's own manifest versionName (binary XML, UTF-16 string scan)
APK_VERSION=$(python3 -c "
import zipfile, re, sys
data = zipfile.ZipFile('$APK').read('AndroidManifest.xml')
s = data.decode('utf-16-le', errors='ignore')
m = re.search(r'\b$VERSION_NAME\b', s)
print('MATCH' if m else 'MISMATCH')
")
if [[ "$APK_VERSION" != "MATCH" ]]; then
  echo "ERROR: APK manifest does not report versionName $VERSION_NAME (pubspec and APK disagree). Refusing to publish." >&2
  exit 1
fi

mkdir -p "$OUT"
BASE_URL="${QOLAB_DEV_ORIGIN:-https://workpulse-gbpedt.drytis.dev}"

cp "$APK" "$OUT/qolab-dev-${VERSION_NAME}.dev.apk"
cp "$APK" "$OUT/qolab-dev-latest.apk"

TMP="$(mktemp "$OUT/latest.json.XXXXXX")"
cat > "$TMP" <<EOF
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "${BASE_URL}/apk/qolab-dev-latest.apk"
}
EOF
mv -f "$TMP" "$OUT/latest.json"

echo "Published Flutter dev APK ${VERSION_NAME} (versionCode ${VERSION_CODE}):"
echo "  ${OUT}/qolab-dev-${VERSION_NAME}.dev.apk"
echo "  ${OUT}/qolab-dev-latest.apk"
echo "  ${OUT}/latest.json"
