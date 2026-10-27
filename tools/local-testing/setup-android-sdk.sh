#!/usr/bin/env bash
# ProGallery — one-time, COMPLETELY FREE Android SDK setup on Linux Mint / Ubuntu.
# No paid services, no billing, no account upgrades. Downloads only Google's free SDK.
# Run once:  bash setup-android-sdk.sh
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
# Google's command-line tools (free). This build number is the current stable release.
CLT_VER="11076708"

echo "== ProGallery: free Android SDK setup =="
echo "SDK location: $SDK"
echo

echo "[1/3] System packages (needs your sudo password) ..."
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip wget zip

echo "[2/3] Android command-line tools ..."
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CLT_VER}_latest.zip" -O "$tmp/clt.zip"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
echo "[3/3] platform-tools (adb), Android 35 platform, build-tools 35 ..."
yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null
"$SDKM" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo
echo "DONE. Add these two lines to the end of ~/.bashrc, then open a NEW terminal:"
echo "  export ANDROID_HOME=$SDK"
echo '  export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin'
echo
echo "Then enable USB debugging on your phone (Settings > About phone > tap Build number 7x,"
echo "then Settings > System > Developer options > USB debugging ON), plug it in over USB,"
echo "and approve the 'Allow USB debugging' prompt. Check it is seen with:  adb devices"
