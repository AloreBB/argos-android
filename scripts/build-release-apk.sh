#!/usr/bin/env bash
# Builds, verifies and packages the signed release APK into dist/. Run by semantic-release
# (prepare step) with the version it picked; needs the ARGOS_KEYSTORE_* / ARGOS_KEY_* env vars.
#
# Usage: scripts/build-release-apk.sh <version>
set -euo pipefail

version="${1:?usage: scripts/build-release-apk.sh <version>}"

./gradlew :app:testDebugUnitTest :app:assembleRelease -PversionName="$version" --console=plain

apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
src=app/build/outputs/apk/release/app-release.apk
certs="$("$apksigner" verify --print-certs "$src")"
echo "$certs"
if grep -q "CN=Android Debug" <<<"$certs"; then
  echo "::error::APK is signed with the debug key"
  exit 1
fi

mkdir -p dist
cp "$src" "dist/Argos-$version.apk"
(cd dist && sha256sum "Argos-$version.apk" > "Argos-$version.apk.sha256" && cat "Argos-$version.apk.sha256")
