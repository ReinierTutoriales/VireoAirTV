#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:-}"
APK_PATH="${2:-}"
SERVER_NAME="${3:-}"
PACKAGE_NAME="${PACKAGE_NAME:-com.reiniertutoriales.vireoairtv}"

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb not found. Install Android SDK Platform-Tools and open a new terminal." >&2
  exit 1
fi

ADB_ARGS=()
if [[ -n "$TARGET" ]]; then
  if [[ "$TARGET" == *:* ]]; then
    # adb connect exits 0 even when it fails; check its message instead
    out=$(adb connect "$TARGET" 2>&1 || true)
    echo "$out"
    if [[ "$out" == *"failed to authenticate"* ]]; then
      echo "Accept \"Allow debugging\" on the TV; waiting..."
    elif [[ "$out" != *"connected to"* ]]; then
      echo "Error: cannot reach $TARGET. Check the IP and port and that debugging is on in the TV." >&2
      exit 1
    fi
  fi
  ADB_ARGS=(-s "$TARGET")
fi

run_adb() {
  adb "${ADB_ARGS[@]}" "$@"
}

run_adb wait-for-device

if [[ -n "$APK_PATH" ]]; then
  if [[ ! -f "$APK_PATH" ]]; then
    echo "Error: APK not found: $APK_PATH" >&2
    exit 1
  fi
  run_adb install -r -g "$APK_PATH"
fi

# Runtime permissions, also when the APK was installed earlier without -g; granted before the first
# launch so the TV shows no permission dialogs
SDK=$(run_adb shell getprop ro.build.version.sdk | tr -d '\r')
grant() {
  run_adb shell pm grant "$PACKAGE_NAME" "$1" || echo "Warning: could not grant $1" >&2
}
if (( SDK >= 33 )); then grant android.permission.POST_NOTIFICATIONS; fi
if (( SDK >= 37 )); then grant android.permission.ACCESS_LOCAL_NETWORK; fi

# Permitir abrir la app al conectar y evitar restricciones de batería en TV
run_adb shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow
run_adb shell dumpsys deviceidle whitelist "+$PACKAGE_NAME"

# A fresh install stays in the "stopped" state and receives no broadcasts (BOOT_COMPLETED,
# SettingsReceiver) until one of its components is launched once
run_adb shell am start -W -n "$PACKAGE_NAME/io.github.jqssun.airplay.MainActivity" >/dev/null

# adb shell joins its arguments into one remote command line: single-quote the value so names
# with spaces or quotes arrive whole
set_setting() {
  local key="$1"
  local value="$2"
  local quoted out
  quoted=$(printf '%s' "$value" | sed "s/'/'\\\\''/g")
  out=$(run_adb shell am broadcast \
    -a io.github.jqssun.airplay.SET_SETTING \
    -n "$PACKAGE_NAME/io.github.jqssun.airplay.SettingsReceiver" \
    --es key "$key" \
    --es value "'$quoted'")
  if [[ "$out" != *"result=-1"* ]]; then
    echo "Error: SettingsReceiver rejected $key=$value:" >&2
    echo "$out" >&2
    exit 1
  fi
}

set_setting auto_start true
set_setting boot_auto_start true
set_setting run_in_background true
set_setting launch_on_connect true
set_setting alac_enabled true

if [[ -n "$SERVER_NAME" ]]; then
  set_setting server_name "$SERVER_NAME"
fi

# the receiver runs as a service; leave the TV on its home screen
run_adb shell input keyevent KEYCODE_HOME

echo "Provisioning completed for $PACKAGE_NAME"
