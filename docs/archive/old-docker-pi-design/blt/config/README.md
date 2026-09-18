# blt/config/

ここに `vj-broadcast.blt` を置く。

作り方:
1. デバッグ用に `x11vnc` を追加したBLTコンテナ(または実機のBLT GUI)を起動し、SSHトンネル越しにGUIを開く
2. Triggers ウィンドウ → File → Editors → Global Setup Expression に
   [`../expressions/global-setup.clj`](../expressions/global-setup.clj) の内容を貼り付ける
3. 同じく Global Shutdown Expression に
   [`../expressions/global-shutdown.clj`](../expressions/global-shutdown.clj) の内容を貼り付ける
4. Network メニューからCarabiner接続設定を行う(接続先ホスト: `carabiner` [Composeのサービス名]、ポート: `17000`)
5. アップデート自動チェックなど、ネット接続を前提とする機能があれば無効化する
   (現場はほぼオフライン起動のため、起動時にこの手の通信を試みて遅延しないか確認する。該当項目が見つかったらこのREADMEに追記すること)
6. 実機のCDJを接続した状態で動作確認する
7. File → Save でこの `vj-broadcast.blt` として保存する

このファイルは `docker-compose.yml` からBLTコンテナに読み取り専用でマウントされる。
