#!/bin/bash
# 起動時にsystemdから呼ばれ、読み取り専用パーティション上のtarからDockerイメージを復元する。
# ネット接続なしの現場でも毎回確実に同じ状態を復元するためのもの。
set -eu

IMAGES_DIR="${BLT_CONNECTOR_IMAGES_DIR:-/opt/blt-connector/images}"
IMAGE_TAR="${IMAGES_DIR}/images.tar"

if [ ! -f "${IMAGE_TAR}" ]; then
  echo "image tar not found: ${IMAGE_TAR}" >&2
  exit 1
fi

docker load -i "${IMAGE_TAR}"
