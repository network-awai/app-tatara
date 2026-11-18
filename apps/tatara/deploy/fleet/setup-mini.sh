#!/bin/sh
# Run on a fleet mini as its user (AWAI-3411). Needs the SDK pieces copied
# to ~/Library/Android/sdk (emulator, platform-tools, the API 35 arm64
# default image). Creates the AVD and the LaunchAgent, then waits for boot.
# Idempotent: an existing AVD and agent are left as they are.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
avd="$HOME/.android/avd/Tatara_Fleet.avd"
agent="$HOME/Library/LaunchAgents/network.awai.tatara.android-emu.plist"
mkdir -p "$avd" "$HOME/.tatara" "$HOME/Library/LaunchAgents"
if [ ! -f "$avd/config.ini" ]; then
  sed "s|@HOME@|$HOME|g" "$here/config.ini" > "$avd/config.ini"
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' "$avd" > "$HOME/.android/avd/Tatara_Fleet.ini"
fi
if [ ! -f "$agent" ]; then
  sed "s|@HOME@|$HOME|g" "$here/network.awai.tatara.android-emu.plist" > "$agent"
  launchctl bootstrap "user/$(id -u)" "$agent"
fi
adb="$HOME/Library/Android/sdk/platform-tools/adb"
"$adb" -s emulator-5580 wait-for-device
until [ "$("$adb" -s emulator-5580 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
echo "emulator-5580 booted"
