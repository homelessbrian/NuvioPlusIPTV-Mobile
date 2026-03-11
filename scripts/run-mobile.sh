#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$ROOT_DIR/gradlew"

ANDROID_APP_ID="com.nuvio.app"
ANDROID_ACTIVITY=".MainActivity"
IOS_PROJECT="$ROOT_DIR/iosApp/iosApp.xcodeproj"
IOS_SCHEME="iosApp"
IOS_DERIVED_DATA="$ROOT_DIR/build/ios-derived"
IOS_APP_NAME="Nuvio.app"
IOS_BUNDLE_ID="com.nuvio.app.Nuvio"
IOS_PREFERRED_DEVICE_MODEL="iPhone 14 Pro"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/run-mobile.sh android
  ./scripts/run-mobile.sh ios

Builds the debug app for the selected platform, installs it on the first running
Android emulator or the configured iOS device target, and launches the app.
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

first_booted_android_emulator() {
  adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1; exit }'
}

first_booted_ios_simulator() {
  xcrun simctl list devices booted | awk -F '[()]' '/Booted/ { print $2; exit }'
}

preferred_ios_device() {
  xcrun xcdevice list --timeout 5 2>/dev/null | python3 -c '
import json
import sys
import os

try:
    devices = json.load(sys.stdin)
except Exception:
    sys.exit(0)

physical = [
    device for device in devices
    if device.get("platform") == "com.apple.platform.iphoneos"
    and not device.get("simulator", False)
    and device.get("available") is True
    and device.get("modelName") == os.environ["IOS_PREFERRED_DEVICE_MODEL"]
]

if physical:
    print(physical[0].get("identifier", ""))
'
}

run_android() {
  require_command adb

  local serial
  serial="$(first_booted_android_emulator)"

  if [[ -z "$serial" ]]; then
    echo "No running Android emulator found." >&2
    echo "Start an emulator first, then rerun: ./scripts/run-mobile.sh android" >&2
    exit 1
  fi

  echo "Building Android debug APK..."
  "$GRADLEW" :composeApp:assembleDebug

  local apk_path
  apk_path="$ROOT_DIR/composeApp/build/outputs/apk/debug/composeApp-debug.apk"

  if [[ ! -f "$apk_path" ]]; then
    echo "Expected APK not found at: $apk_path" >&2
    exit 1
  fi

  echo "Installing on emulator $serial..."
  adb -s "$serial" install -r "$apk_path"

  echo "Launching app..."
  adb -s "$serial" shell am start -n "$ANDROID_APP_ID/$ANDROID_ACTIVITY"
}

run_ios() {
  require_command xcodebuild
  require_command xcrun

  local physical_device_id
  physical_device_id="$(IOS_PREFERRED_DEVICE_MODEL="$IOS_PREFERRED_DEVICE_MODEL" preferred_ios_device)"

  if [[ -n "$physical_device_id" ]]; then
    local device_app_path
    device_app_path="$IOS_DERIVED_DATA/Build/Products/Debug-iphoneos/$IOS_APP_NAME"

    echo "Building iOS debug app for physical device $physical_device_id..."
    xcodebuild \
      -project "$IOS_PROJECT" \
      -scheme "$IOS_SCHEME" \
      -configuration Debug \
      -destination "id=$physical_device_id" \
      -derivedDataPath "$IOS_DERIVED_DATA" \
      build

    if [[ ! -d "$device_app_path" ]]; then
      echo "Expected iOS app not found at: $device_app_path" >&2
      exit 1
    fi

    echo "Installing on physical device $physical_device_id..."
    xcrun devicectl device install app --device "$physical_device_id" "$device_app_path"

    echo "Launching app..."
    xcrun devicectl device process launch --device "$physical_device_id" "$IOS_BUNDLE_ID"
    return
  fi

  echo "Preferred iOS device not available: $IOS_PREFERRED_DEVICE_MODEL" >&2
  echo "Connect and unlock that device, then rerun: ./scripts/run-mobile.sh ios" >&2
  exit 1
}

main() {
  if [[ $# -ne 1 ]]; then
    usage
    exit 1
  fi

  case "$1" in
    android)
      run_android
      ;;
    ios)
      run_ios
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      echo "Unknown platform: $1" >&2
      usage
      exit 1
      ;;
  esac
}

main "$@"
