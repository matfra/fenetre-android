#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="${1:-"$ROOT_DIR/adb-devices.yaml"}"
INTERVAL_SECONDS="${ADB_KEEPALIVE_INTERVAL_SECONDS:-60}"
ADB_BIN="${ADB:-adb}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Missing config file: $CONFIG_FILE" >&2
  exit 1
fi

mapfile -t DEVICES < <(
  awk '
    /^[[:space:]]*-[[:space:]]*name:/ { name=$3 }
    /^[[:space:]]*ip:/ { ip=$2 }
    /^[[:space:]]*port:/ {
      port=$2
      if (name != "" && ip != "" && port != "") {
        print name "|" ip "|" port
      }
      name=""; ip=""; port=""
    }
  ' "$CONFIG_FILE"
)

if [[ "${#DEVICES[@]}" -eq 0 ]]; then
  echo "No devices found in $CONFIG_FILE" >&2
  exit 1
fi

echo "ADB keepalive using $CONFIG_FILE every ${INTERVAL_SECONDS}s"

while true; do
  for device in "${DEVICES[@]}"; do
    IFS="|" read -r name ip port <<< "$device"
    serial="${ip}:${port}"
    timestamp="$(date -Is)"
    if "$ADB_BIN" -s "$serial" shell true >/dev/null 2>&1; then
      echo "$timestamp $name $serial ok"
      continue
    fi

    "$ADB_BIN" connect "$serial" >/dev/null 2>&1 || true
    if "$ADB_BIN" -s "$serial" shell true >/dev/null 2>&1; then
      echo "$timestamp $name $serial reconnected"
    else
      echo "$timestamp $name $serial unavailable" >&2
    fi
  done
  sleep "$INTERVAL_SECONDS"
done
