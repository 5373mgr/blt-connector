#!/bin/bash
# ネット接続がある環境でイメージをビルド/pullし、読み取り専用パーティション上にtar保存する。
# 現場(オフライン)で毎回同じ状態を確実に復元するための事前準備スクリプト。
set -eu

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGES_DIR="${BLT_CONNECTOR_IMAGES_DIR:-/opt/blt-connector/images}"
IMAGE_TAR="${IMAGES_DIR}/images.tar"

mkdir -p "${IMAGES_DIR}"

echo "building images..."
docker compose -f "${REPO_ROOT}/docker-compose.yml" build

echo "saving images to ${IMAGE_TAR} ..."
mapfile -t IMAGE_NAMES < <(docker compose -f "${REPO_ROOT}/docker-compose.yml" config --images)
docker save -o "${IMAGE_TAR}" "${IMAGE_NAMES[@]}"

echo "done: ${IMAGE_TAR}"
