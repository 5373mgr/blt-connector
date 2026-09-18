# Raspberry Pi + Beat Link Trigger VJメタデータ配信システム — 実装引き継ぎ (v2)

## プロジェクト概要
Raspberry Pi上でBeat Link Trigger (BLT) を動かし、Pioneer Pro DJ Link (CDJ/ミキサー) から取得した情報を、USB-LANアダプタ経由で接続した別LAN上の複数VJマシンに同時配信する。現場でPiの電源を入れたら人手を介さず自動的に稼働する状態を目指す。Docker化し、Piが落ちた場合には別PC(x86_64機)でも代替稼働できるようにする。

## 必須要件
- デッキ1〜4の再生中トラック情報(曲名・アーティスト)+再生位置(ms) — 必須
- MasterプレイヤーのBPM — 必須。Ableton Link経由での連携を想定
- 上記情報を複数のVJに同時配信できること
- 将来的に専用の受信ソフト(Windows/Mac両対応)を自作する可能性がある → プロトコルは実装しやすいものを優先
- 現場で電源投入後、人手を介さず配信まで自動的に立ち上がること
- Piが故障した場合、別のPC(x86_64機)で代替稼働できること
- 機能はなるべく絞る(BLT/Carabiner本体には手を入れない、追加機能は疎結合な別コンテナにする)

## ネットワーク構成
- `eth0`(Pi内蔵想定): CDJ/Pro DJ Linkネットワーク側
- USB-LANアダプタ(`usb0`/`eth1`等、環境依存): VJ向け出力LAN側。単一スイッチのフラットな小規模LANを想定
- どちらのNICをCDJ側/VJ出力側として使うかはハードコードせず、後述のnetconfig(Web GUI)で選択できるようにする(別PCへの切り替え時にインターフェース名が変わっても対応できるようにするため)
- 両NICともMACアドレス紐付けで固定IPにし、DHCP待ちで起動が遅延しないようにする
- 出力LAN側は基本ブロードキャストで複数VJへ同時到達させる方針(将来、選択配信が必要になればマルチキャスト239.x.x.xへの切り替えも検討)

## OS / コンテナ方針
- OS: **Raspberry Pi OS Lite (64-bit)** を第一候補
- Docker化してBLTをコンテナで動かす方針
- **重要**: BLT(beat-linkライブラリ)はCDJ検出にUDPブロードキャストを使うため、コンテナは `--network host` 必須(デフォルトのbridge/NATではCDJを検出できない)
- 将来的なx86_64機への切り替えに備え、`docker buildx` でarm64/amd64のマルチアーキイメージビルドを前提にする
- コンテナ構成: BLTコンテナ(Xvfb同居) / Carabinerコンテナ(別プロセス) / netconfigコンテナ(NIC選択Web GUI、後述)の3つに分離

### GUIコンテナ化の実装方針(Xvfb)
BLTはJava SwingのGUIアプリなので、コンテナ内に`Xvfb`(仮想フレームバッファ)を立ててその上で動かす。物理モニター・VNCなしで「GUI環境がある」状態を作れる。

```dockerfile
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y xvfb fluxbox
COPY beat-link-trigger.jar /app/
COPY entrypoint.sh /app/
ENTRYPOINT ["/app/entrypoint.sh"]
```
```bash
#!/bin/bash
Xvfb :99 -screen 0 1280x800x24 &
export DISPLAY=:99
fluxbox &   # Swingのモーダルダイアログ等がWM無しだと不安定になることがあるため軽量WMを添える
exec java -jar /app/beat-link-trigger.jar
```
デバッグ時のみ`x11vnc`をコンテナに追加し、SSHトンネル越しにGUIを覗く運用も可能(常時稼働には不要)。

## ソフトウェア構成
- **Beat Link Trigger(フル版)** を使用。Open Beat Control (OBC) は不採用
  - 理由: OBCはbeat-linkのテンポ/Ableton Linkブリッジのみのサブセットで、曲名メタデータや再生位置は取得できないため
- **Carabiner**: Ableton LinkブリッジのネイティブC++バイナリ。BLTの「Carabiner自動起動」機能は特定アーキ検出に依存するためDocker環境では使わず、独立したコンテナ/プロセスとして自前で起動し、BLT側は「起動済みのCarabinerに接続する」設定にする

## データ配信設計

### 1. Master BPM → Ableton Link経由(Carabiner使用)
- BLTのCarabiner連携でPro DJ LinkのMasterテンポをAbleton Linkセッションへブリッジ
- Ableton Link自体がLAN内UDPマルチキャストで複数アプリに自動配信する設計のため、追加の配信ロジックは不要
- 将来の自作受信ソフトはAbleton Link公式C++ SDK(Win/Mac対応)を組み込む想定

### 2. デッキ1〜4の曲情報+再生位置 → JSON over UDPブロードキャスト(独自チャンネル)
- Ableton Linkはテンポ/トランスポートのみでトラックメタデータを扱わないため、こちらは別チャンネルが必要
- 出力LANのブロードキャストアドレスに、周期的(目安10Hz程度)にJSONペイロードを送信する方式を想定
- ペイロード例:
  ```json
  {
    "decks": [
      {"id": 1, "title": "...", "artist": "...", "position_ms": 12345, "duration_ms": 320000, "playing": true}
    ]
  }
  ```
- BLT側ではbeat-linkのメタデータ取得(曲名/アーティスト)+再生位置推定(TimeFinder相当)を、Trigger/Showのexpressionまたは周期タイマーから取得してJSON化・送信する実装が必要(未実装)
- 送信元インターフェース/ブロードキャストアドレスはnetconfigの設定を参照する

## NIC選択Web GUI(netconfig)
- 目的: CDJ側/VJ出力側のNICをハードコードせず、現場で人が選べるようにする(別PCへの切り替え時にインターフェース名が変わることに対応)
- BLT/Carabinerには手を入れず、完全に独立した小さな1機能コンテナとして実装(機能を絞る方針)
- 技術: Python + Flask程度の小規模実装
- 機能: `/sys/class/net/`からインターフェース一覧(名前・MACアドレス・現在のIP・リンク状態)を取得しWeb UIに表示、プルダウンで「CDJ側」「VJ出力側」を選択して保存
- 保存先: 共有ボリューム上の設定ファイル(例: `/config/network.json`)。BLT起動スクリプト・配信スクリプトはこれを読んで使用インターフェースを決定する
- MACアドレスも表示する(USB-LANアダプタの列挙順が入れ替わることがあるため、名前だけでなくMACで判別できるようにする)
- 設定反映は「保存後は手動でコンテナ再起動」で運用(Dockerソケットを渡してまで自動再起動は作らない = 機能を絞る方針)

## 電源断・ファイルシステム保護
- 方針: `raspi-config` → Performance Options → Overlay File System(公式サポートの読み取り専用ルート機能)を有効化する
  - 有効化するとSDカードは読み取り専用になり、実行時の書き込みはRAM上のtmpfsに吸収されて再起動で消える → 電源断によるファイルシステム破損のリスクが原理的になくなる
- **既知の注意点**: overlay有効時にDockerが`/var/lib/docker`関連のrename処理で`invalid cross-device link`エラーを出して起動失敗する事例が報告されている
  - 対策: `/var/lib/docker`はOS側のoverlayに任せず、専用の`tmpfs`を明示的にマウントして使わせる(`/etc/fstab`に1行追加するイメージ)
- 上記構成だとDockerイメージも再起動のたびに消えるため、以下の起動フローにする:
  1. 事前にビルド/pullしたイメージを`docker save`でtar化し、読み取り専用パーティション上に保存しておく
  2. 起動時、systemdサービスで`docker load`を実行してから`docker compose up`する
  - これによりネット接続なしの現場でも毎回確実に同じ状態で復元できる
- OverlayFSは「クリーンシャットダウン/安定電源の代替にはならない」補助策という位置づけ。さらに保険が欲しい場合は電源瞬断検知でgraceful shutdownをトリガーする小型UPS HATの追加も選択肢(必須ではない)

## 自動起動要件
- 電源投入後、ネットワーク → (netconfigのnetwork.json読み込み) → Dockerコンテナ(BLT/Carabiner)の順で自動起動し、人手を介さず配信開始まで到達すること(systemd管理、`Restart=always`想定)
- 各NICの固定IP設定(MACアドレス紐付け)、DHCP待ちで起動が遅延しないようにする

## 未着手(これから実装するタスク)
- [ ] Docker Compose定義(BLTコンテナ + Carabinerコンテナ + netconfigコンテナ、host networking)
- [ ] BLTコンテナのDockerfile/entrypoint(Xvfb + fluxbox)
- [ ] BLT側のTrigger/Show expression実装(曲情報+再生位置のJSON生成・UDPブロードキャスト送信、netconfigのnetwork.jsonを参照)
- [ ] Carabiner接続設定(BLT → Carabiner → Ableton Link)
- [ ] netconfig(NIC選択Web GUI)実装
- [ ] raspi-configでのOverlay File System有効化 + `/var/lib/docker`用tmpfsマウント設定
- [ ] Dockerイメージの事前save/load起動フロー(systemdユニット)
- [ ] 各種systemd自動起動ユニット一式
- [ ] (将来)Windows/Mac向け専用受信ソフトの設計(Ableton Link SDK + JSON UDP受信)
- [ ] (検討中)電源瞬断検知UPS HATの要否判断
