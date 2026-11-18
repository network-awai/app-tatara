#!/bin/sh
# Run on a fleet mini after setup-mini.sh (AWAI-3411):
#   provision-app.sh APK COORDINATOR_URL
# Installs the Tatara APK, writes the coordinator URL into the app's private
# config (0600, debug build via run-as), starts the app and prints the
# device's runner DID. No secrets are written.
set -eu
apk=$1; url=$2
adb="$HOME/Library/Android/sdk/platform-tools/adb -s emulator-5580"
case "$url" in http://*|https://*) ;; *) echo "bad url" >&2; exit 64;; esac
$adb install -r "$apk" >/dev/null
printf '{"coordinator":"%s"}' "$url" | $adb shell "run-as network.awai.tatara sh -c 'umask 077; cat > config.json'"
$adb shell am start -n network.awai.tatara/.MainActivity >/dev/null
i=0; until $adb shell "run-as network.awai.tatara test -f device-did" 2>/dev/null; do i=$((i+1)); [ $i -gt 40 ] && { echo "no device DID" >&2; exit 1; }; sleep 2; done
# Only the public DID is read; the key file never leaves the app.
$adb shell "run-as network.awai.tatara cat device-did"; echo
