#!/usr/bin/env bash
# Install a user-space Android toolchain (JDK 17, Android cmdline-tools + SDK,
# Gradle) without root. Everything lands under $HOME so it survives re-runs.
set -euo pipefail

LOG_PREFIX() { echo "[$(date +%H:%M:%S)]"; }

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export GRADLE_HOME="${GRADLE_HOME:-$HOME/gradle}"
mkdir -p "$ANDROID_HOME" "$GRADLE_HOME" "$HOME/toolchain-dl"

echo "$(LOG_PREFIX) [1/4] JDK 17 (Temurin)"
if [ ! -x "$HOME/jdk17/bin/java" ]; then
  curl -fsSL -o "$HOME/toolchain-dl/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$HOME/jdk17-extract"
  tar -xzf "$HOME/toolchain-dl/jdk17.tar.gz" -C "$HOME/jdk17-extract"
  # Adoptium archives unpack to a versioned directory; normalise the path.
  mv "$HOME/jdk17-extract"/* "$HOME/jdk17" 2>/dev/null || true
  rmdir "$HOME/jdk17-extract" 2>/dev/null || true
fi
"$HOME/jdk17/bin/java" -version 2>&1 | head -1

echo "$(LOG_PREFIX) [2/4] Android command-line tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o "$HOME/toolchain-dl/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q -o "$HOME/toolchain-dl/cmdline-tools.zip" -d "$ANDROID_HOME/cmdline-tools"
  # The archive unpacks to cmdline-tools/; the SDK expects .../cmdline-tools/latest/.
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

export JAVA_HOME="$HOME/jdk17"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "$(LOG_PREFIX) [3/4] SDK packages (platform, build-tools, platform-tools)"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  >/dev/null
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --list_installed

echo "$(LOG_PREFIX) [4/4] Gradle"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  curl -fsSL -o "$HOME/toolchain-dl/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-8.13-bin.zip"
  unzip -q -o "$HOME/toolchain-dl/gradle.zip" -d "$GRADLE_HOME-tmp"
  # The archive unpacks to gradle-<version>/; flatten it into GRADLE_HOME.
  mv "$GRADLE_HOME-tmp"/gradle-*/* "$GRADLE_HOME/"
  rm -rf "$GRADLE_HOME-tmp"
fi
"$GRADLE_HOME/bin/gradle" --version | sed -n '1,12p'

echo "$(LOG_PREFIX) TOOLCHAIN_READY"
