#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:-}"
APK_PATH="${2:-}"
SERVER_NAME="${3:-}"
PACKAGE_NAME="${PACKAGE_NAME:-com.reiniertutoriales.vireoairtv}"

ADB_ARGS=()
if [[ -n "$TARGET" ]]; then
  if [[ "$TARGET" == *:* ]]; then
    adb connect "$TARGET"
  fi
  ADB_ARGS=(-s "$TARGET")
fi

run_adb() {
  adb "${ADB_ARGS[@]}" "$@"
}

run_adb wait-for-device

if [[ -n "$APK_PATH" ]]; then
  run_adb install -r -g "$APK_PATH"
fi

# Permitir abrir la app al conectar y evitar restricciones de batería en TV
run_adb shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow
run_adb shell dumpsys deviceidle whitelist "+$PACKAGE_NAME"

set_setting() {
  local key="$1"
  local value="$2"
  local out
  out=$(run_adb shell am broadcast \
    -a io.github.jqssun.airplay.SET_SETTING \
    -n "$PACKAGE_NAME/io.github.jqssun.airplay.SettingsReceiver" \
    --es key "$key" \
    --es value "$value")
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

if [[ -n "$SERVER_NAME" ]]; then
  set_setting server_name "$SERVER_NAME"
fi

echo "Provisioning completed for $PACKAGE_NAME"
