#!/usr/bin/env bash
# ── Qolab Development APK publisher ──────────────────────────────
# Run AFTER a successful `./gradlew assembleDebug`. Publishes:
#   1. versioned archive  /workspace/public/apk/qolab-dev-<version>.dev.apk
#   2. stable APK         /workspace/public/apk/qolab-dev-latest.apk
#   3. manifest           /workspace/public/apk/latest.json
# The manifest is written LAST and atomically (tmp + mv) so it can never
# disagree with the APK. Refuses to run when the APK is missing.
set -euo pipefail

APK="${1:-/home/coder/qolab-android/android/app/build/outputs/apk/debug/app-debug.apk}"
OUT="/workspace/public/apk"
GRADLE_FILE="/home/coder/qolab-android/android/app/build.gradle"

if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found: $APK — build assembleDebug first." >&2
  exit 1
fi

VERSION_CODE=$(grep -oP 'versionCode\s+\K[0-9]+' "$GRADLE_FILE" | head -1)
VERSION_NAME=$(grep -oP 'versionName\s+"\K[^"]+' "$GRADLE_FILE" | head -1)
if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
  echo "ERROR: could not read versionCode/versionName from $GRADLE_FILE" >&2
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

echo "Published ${VERSION_NAME} (versionCode ${VERSION_CODE}):"
echo "  ${OUT}/qolab-dev-${VERSION_NAME}.dev.apk"
echo "  ${OUT}/qolab-dev-latest.apk"
echo "  ${OUT}/latest.json"
