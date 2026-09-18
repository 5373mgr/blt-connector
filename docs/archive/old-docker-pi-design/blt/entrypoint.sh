#!/bin/bash
set -eu

Xvfb :99 -screen 0 1280x800x24 &
XVFB_PID=$!

export DISPLAY=:99

# SwingのモーダルダイアログがWM無しだと不安定になることがあるため軽量WMを添える
fluxbox &
FLUXBOX_PID=$!

cleanup() {
  kill "${FLUXBOX_PID}" "${XVFB_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# BLT起動時に配布用の設定(.blt)を自動ロードする(BLTは起動引数でファイルパスを受け取れる)
exec java -jar /app/beat-link-trigger.jar "${BLT_CONFIG_PATH}"
