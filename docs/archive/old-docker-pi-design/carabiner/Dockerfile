# Carabiner (Ableton Link ブリッジ) のビルド + 実行コンテナ
# BLT本体の「Carabiner自動起動」機能は使わず、独立プロセスとして起動し、
# BLT側は「起動済みのCarabinerに接続する」設定にする。
# buildx で arm64(Raspberry Pi)/amd64(代替機)の両方をビルドする前提。

FROM debian:bookworm-slim AS builder

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential cmake git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN git clone --recursive https://github.com/Deep-Symmetry/carabiner.git .
RUN make

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /src/Carabiner /usr/local/bin/Carabiner
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# BLT側のデフォルト接続先ポートに合わせる(BLTのCarabiner接続設定で同じ値を指定する)
ENV CARABINER_PORT=17000

ENTRYPOINT ["/entrypoint.sh"]
