#!/usr/bin/env bash
# Dựng môi trường build NightCam trên máy Linux mới (cloud session, CI tự dựng).
# Dùng: source nightcam/scripts/setup-env.sh   (để ANDROID_HOME còn lại trong shell)
set -euo pipefail

SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK_DIR/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -sSL -o "$tmp/clt.zip" "$CLT_URL"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /dev/null

# Maven Central hay trả 429 cho IP cloud: đặt mirror của Google lên trước.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/mirror.gradle" <<'GRADLE'
def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
beforeSettings { s ->
    s.pluginManagement.repositories { maven { url mirror } }
    s.dependencyResolutionManagement.repositories { maven { url mirror } }
}
GRADLE

export ANDROID_HOME="$SDK_DIR"
echo "ANDROID_HOME=$ANDROID_HOME — giờ chạy: cd nightcam && ./gradlew assembleDebug"
