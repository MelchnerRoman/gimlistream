#!/bin/sh
# Builds dist/Gimlistream.apk (Android 8+, targets Android 17 / API 37).
# Needs: brew install --cask android-commandlinetools; brew install gradle openjdk@21
#        sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
set -e
cd "$(dirname "$0")/android"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
gradle --no-daemon -q assembleRelease
mkdir -p ../dist
cp build/outputs/apk/release/gimlistream-release.apk ../dist/Gimlistream.apk
