#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to a writable Android SDK directory}"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  staging=$(mktemp -d)
  trap 'rm -rf "$staging"' EXIT
  curl --fail --location --retry 3 \
    https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    --output "$staging/tools.zip"
  # Google's repository2-3.xml checksum for cmdline-tools;19.0 (Linux).
  printf '%s  %s\n' 5fdcc763663eefb86a5b8879697aa6088b041e70 "$staging/tools.zip" | sha1sum --check
  unzip -q "$staging/tools.zip" -d "$staging/unpacked"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$staging/unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
license_input=$(mktemp)
trap 'rm -f "$license_input"; if [[ -n "${staging:-}" ]]; then rm -rf "$staging"; fi' EXIT
printf 'y\n%.0s' {1..100} > "$license_input"
"$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses < "$license_input"
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
