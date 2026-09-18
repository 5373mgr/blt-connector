# Beat Link Trigger (フル版) を Xvfb 上で動かすコンテナ。
# GUIレスの物理モニター/VNCなし環境でSwingアプリを起動するため、
# 仮想フレームバッファ(Xvfb)+軽量WM(fluxbox)を同居させる。
# beat-link/Carabiner本体には手を入れず、設定ファイル(.blt)とJVMの起動だけを担う。

FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb fluxbox curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# latest-preview タグは中身が更新され得るローリングリリースのため、
# ビルドの再現性が必要になったら特定バージョンのタグ付きリリースへの切り替えを検討すること。
ARG BLT_JAR_URL=https://github.com/Deep-Symmetry/beat-link-trigger/releases/download/latest-preview/beat-link-trigger-8.1.0-Preview.jar
RUN curl -fsSL -o beat-link-trigger.jar "${BLT_JAR_URL}"

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 起動時に読み込む設定(.blt)。CI/デプロイ時に差し替え可能なようにENVで指定できるようにする。
ENV BLT_CONFIG_PATH=/app/config/vj-broadcast.blt

ENTRYPOINT ["/app/entrypoint.sh"]
