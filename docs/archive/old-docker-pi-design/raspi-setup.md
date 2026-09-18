# Raspberry Pi OS セットアップ手順(下書き・要検証)

このドキュメントは実機での検証がまだ済んでいない手順です。値は現場の実際のIPアドレス設計・MACアドレスに置き換えること。

## 1. NICの固定IP化(MACアドレス紐付け)

DHCP待ちで起動が遅延しないよう、CDJ側・VJ出力側の両NICをMACアドレスに紐付けた固定IPにする。
Raspberry Pi OS (Bookworm以降) はデフォルトでNetworkManagerを使うため `nmcli` で設定する例:

```bash
# CDJ側(例: onboard eth0)
sudo nmcli con add type ethernet con-name cdj-link ifname "*" \
  mac-address AA:BB:CC:DD:EE:FF ip4 192.168.1.10/24
sudo nmcli con mod cdj-link ipv4.method manual
sudo nmcli con up cdj-link

# VJ出力側(例: USB-LANアダプタ)
sudo nmcli con add type ethernet con-name vj-output ifname "*" \
  mac-address 11:22:33:44:55:66 ip4 192.168.2.10/24
sudo nmcli con mod vj-output ipv4.method manual
sudo nmcli con up vj-output
```

MACアドレス・IPアドレス帯は現場の実機に合わせて置き換える。`nmcli`のバージョン差異があるため、
実機で `nmcli device show` 等と合わせて動作確認すること。

## 2. Overlay File System の有効化

```
sudo raspi-config
```
→ Performance Options → Overlay File System → 有効化 → 再起動

これによりSDカードは読み取り専用になり、実行時の書き込みはtmpfsに吸収されるため、電源断によるファイルシステム破損のリスクが原理的になくなる。

## 3. `/var/lib/docker` 用tmpfsマウント

overlay有効時、`/var/lib/docker`関連のrename処理で `invalid cross-device link` エラーが出てDockerが起動失敗する事例が報告されている。
`/var/lib/docker` はOS側のoverlayに任せず、専用のtmpfsを明示的にマウントする。`/etc/fstab` に以下を追記:

```
tmpfs /var/lib/docker tmpfs defaults,size=4G 0 0
```

`size`はビルドしたイメージの合計サイズとPiの搭載RAMを見て調整する(Pi実機での要検証事項)。

## 4. イメージの事前save/load

ネット接続がある環境で:
```bash
sudo mkdir -p /opt/blt-connector/images
sudo BLT_CONNECTOR_IMAGES_DIR=/opt/blt-connector/images ./scripts/save-images.sh
```

現場(オフライン)では起動時にsystemd経由で `scripts/load-images.sh` が自動実行される(下記5を参照)。

## 5. systemdユニットの導入

```bash
sudo mkdir -p /opt/blt-connector
sudo cp -r . /opt/blt-connector/repo
sudo cp systemd/blt-connector-load-images.service systemd/blt-connector.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now blt-connector-load-images.service
sudo systemctl enable --now blt-connector.service
```

`blt-connector.service` の `WorkingDirectory=/opt/blt-connector/repo` は、このリポジトリを配置するパスに合わせて調整すること。

## 6. (検討中)電源瞬断検知UPS HAT

OverlayFSは「クリーンシャットダウン/安定電源の代替にはならない」補助策という位置づけ。
さらに保険が欲しい場合は電源瞬断検知でgraceful shutdownをトリガーする小型UPS HATの追加を検討する(必須ではない、要否は別途判断)。
