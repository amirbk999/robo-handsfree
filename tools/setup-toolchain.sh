#!/usr/bin/env bash
# Portable, root-free Android build toolchain for this workspace.
set -euo pipefail
cd "$HOME"
mkdir -p toolchain downloads
cd downloads

echo "== downloading JDK 17 =="
curl -L --retry 3 -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p "$HOME/toolchain/jdk17"
tar xzf jdk17.tar.gz -C "$HOME/toolchain/jdk17" --strip-components=1
echo "jdk done"

echo "== downloading Android cmdline-tools =="
curl -L --retry 3 -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
mkdir -p "$HOME/toolchain/android-sdk/cmdline-tools"
unzip -q -o cmdline-tools.zip -d "$HOME/toolchain/android-sdk/cmdline-tools"
mv "$HOME/toolchain/android-sdk/cmdline-tools/cmdline-tools" "$HOME/toolchain/android-sdk/cmdline-tools/latest" 2>/dev/null || true
echo "cmdline-tools done"

echo "== downloading Gradle 8.7 =="
curl -L --retry 3 -o gradle.zip "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
unzip -q -o gradle.zip -d "$HOME/toolchain"
echo "gradle done"

export JAVA_HOME="$HOME/toolchain/jdk17"
export ANDROID_HOME="$HOME/toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/toolchain/gradle-8.7/bin:$PATH"

echo "== accepting licenses =="
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "== installing sdk packages =="
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

echo "ALL DONE"
java -version 2>&1 | head -2
ls "$ANDROID_HOME/platforms"
