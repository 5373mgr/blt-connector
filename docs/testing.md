# 実機テスト環境

このプロジェクトはシミュレータを持たず、実機のPro DJ Link機材(CDJ/ミキサー)と
Raspberry Pi(CLI版の実行環境)を使って動作確認する。本書はその接続情報・手順をまとめたもので、
別のPC/別の担当者に移っても同じ手順で開発を継続できることを目的にしている。

## 構成

- **Pi本体**: ホスト名`blt-connector`、Pro DJ Linkセグメント側IP `192.168.50.1`(固定、`eth0`)。
  Pro DJ Link機材(CDJ/ミキサー)とこのPiは同一スイッチ(またはPi直結)でつながっている。
- **実行ユーザー**: `blt-admin`(sudo権限あり、パスワード認証。後述の通り一部コマンドのみ
  パスワード不要)
- **常駐プロセス**: CLI版が`blt-connector.service`という名前のsystemdサービスとして常時起動
  (`/etc/systemd/system/blt-connector.service`)。作業ディレクトリは
  `/home/blt-admin/blt-connector-run/cli/`、設定ファイルは同ディレクトリの
  `blt-connector-cli.json`(存在しない場合は`Config`の既定値で動く)。
- **DHCPサーバー**: Pro DJ Linkセグメント向けに`dnsmasq`(`/etc/dnsmasq.d/prodjlink.conf`、
  配布範囲`192.168.50.10`〜`200`)。CLIのWeb UIからON/OFF・自動切り替え可能
  ([DhcpService.kt](../cli/src/main/kotlin/bltconnector/cli/DhcpService.kt)参照)。

## SSH接続

秘密鍵認証。手元の`~/.ssh/config`に以下のような設定を追加しておくと、
`ssh blt-connector-pdl`の1コマンドで接続できる(鍵ファイル自体はこのリポジトリには
含まれないので、既存の鍵を引き継ぐか、新しい鍵ペアを作って`~/.ssh/authorized_keys`に
追加してもらう必要がある)。

```
Host blt-connector-pdl
  HostName 192.168.50.1
  User blt-admin
  IdentityFile ~/.ssh/blt-connector-pi
  IdentitiesOnly yes
```

sudoパスワードは秘密情報のため本リポジトリには記載しない。チームメンバーには別経路
(パスワードマネージャー等)で共有すること。**パスワードをgitやチャットの平文で長期間
共有し続けない**(本プロジェクトでは作業者追加のタイミングでローテーションした実績あり)。

## デプロイ手順(コード変更を実機へ反映する)

CLI版はホットリロードしないため、`core`/`cli`のコードを変更したら毎回ビルド→転送→
再起動が必要。

```bash
# 1. ビルド(Windows側。非ASCIIパス対策でビルド出力はOS一時ディレクトリ配下に出る。
#    実際の出力先は各タスクのログで確認するか、下記のような一時ディレクトリを探す)
./gradlew :cli:installDist -x test

# 2. 変更したモジュールのjarだけ転送すればよい(core.jarとcli.jarは独立)
scp <build出力>/cli/install/cli/lib/core.jar blt-connector-pdl:~/blt-connector-run/cli/lib/core.jar
# cli側(Main.kt/ConfigWebServer.kt等)を変更した場合はcli.jarも
scp <build出力>/cli/install/cli/lib/cli.jar blt-connector-pdl:~/blt-connector-run/cli/lib/cli.jar
# beat-linkのフォーク(third_party/beat-link)を変更した場合はbeat-link-fork.jarも
scp <build出力>/cli/install/cli/lib/beat-link-fork.jar blt-connector-pdl:~/blt-connector-run/cli/lib/beat-link-fork.jar
# 依存ライブラリ自体を増減した場合は、bin/cli(起動スクリプトにCLASSPATHが直書きされている)と
# lib/を丸ごと入れ替えること(jarだけ差し替えるとClassNotFoundになる)

# 3. サービス再起動(sudoパスワードが必要)
ssh blt-connector-pdl "sudo systemctl restart blt-connector.service"
```

`core/src/main/resources/`配下(Overlay用HTML/CSS/JS等の静的ファイル)を変更した場合も
`core.jar`に同梱されるため、同じ手順で反映される。

## ログの確認

```bash
ssh blt-connector-pdl "journalctl -u blt-connector.service --no-pager -n 100"
ssh blt-connector-pdl "journalctl -u blt-connector.service -f"   # 追従表示
```

**注意**: Piの時計はNTPに同期していないことがある(Pro DJ Linkセグメントはインターネットに
出られないため)。ログのタイムスタンプが実際の日時とズレることがあるので、`date`コマンドで
Pi自身の時刻を確認してから相対的に読むこと。また、エラーが高頻度(数十〜数百回/分)で
発生すると journalの保持ウィンドウがすぐに流れてしまい、起動直後の正常なログ
(デバイス検出等)が追えなくなることがある。

## 動作確認用の簡易スクリプト

このリポジトリにツールとして同梱してはいないが、Overlayの`/ws`(WebSocket)が
配信する実際のデッキデータを直接確認したい場合は、Python標準ライブラリだけで
最小限のWebSocketクライアントを書いてPi上で実行するのが手早い(`curl`はWebSocket
ハンドシェイクに対応していないため)。概ね以下の形:

```python
import socket, base64, struct, time, json
s = socket.create_connection(("127.0.0.1", 8090), timeout=5)
key = base64.b64encode(b"check-key-000000").decode()
s.sendall(
    f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:8090\r\nUpgrade: websocket\r\n"
    f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
)
s.recv(4096)  # ハンドシェイク応答を読み捨て
# 以降、RFC6455のフレームをパースしてJSONを取り出す(マスクビット等の処理が必要)
```

## 単体テスト

実機不要で動くものは通常のJUnit単体テスト([core/src/test/](../core/src/test/))に
まとまっている。

```bash
./gradlew :core:test                                              # core全体
./gradlew :core:test --tests "bltconnector.core.sender.SenderTest" # 個別クラスだけ
```

- `SenderTest`: OSC送信の送信タイミング・差分検知ロジックを検証(実機不要)
- `CarabinerBridgeTest`: 実際にCarabinerバイナリを起動して`bpm`送信→Ableton Linkの
  テンポ変化を確認する(CDJは不要だが、実際のネイティブバイナリを起動するので
  実行環境のOS/アーキテクチャに依存する)

Windows + 日本語パス環境でのGradle Testワーカーの既知の問題については
[README.md](../README.md)の「既知の環境固有の問題」を参照。

## 既知の実機特有の不具合と対処(詳細は各コードのコメント/コミット参照)

- **VirtualCdjのプレイヤー番号衝突**: Receiver自身がPro DJ Linkのプレイヤー番号(1〜4)を
  自己割り当てする際、起動時点でまだ電源が入っていない実機CDJの番号と後から衝突することがある。
  `Config.virtualCdjDeviceNumber`で明示的に空き番号を指定することで回避
  ([Receiver.kt](../core/src/main/kotlin/bltconnector/core/receiver/Receiver.kt)の
  `start()`のコメント参照)。
- **dbserverポート番号の無効値キャッシュ**: beat-link側の既知の不具合で、CDJ再起動直後の
  タイミング次第でdbserverポート番号の無効値(65535)がキャッシュされたまま固定され、
  以後そのプレイヤーのメタデータ取得が永久に失敗することがある。Receiver内部の
  watchdogが定期的に検知して自動復旧する。
- **USB抜き差し後のメタデータ固着**: beat-linkのメディアマウント検知が正しく働かず、
  USBを抜き差しした後にそのプレイヤーのメタデータが永久にnullのまま固着することがある
  (トラック自体は再生されている=BPM等は正常に取れる状態で、曲名/アーティスト等だけが
  取れない)。同じwatchdogが「トラックロード中なのにメタデータが15秒以上nullのまま」を
  検知して自動復旧する(同じ曲への再試行は1回のみ)。
- **Carabinerのネイティブバイナリクラッシュ**: Raspberry Pi(32bit ARM)上のCarabinerバイナリは
  bpm送信を受け続けると内部でクラッシュすることがある(Ableton Link本体側の既知の制約と見られる)。
  supervisorが自動再起動するため実運用上は大きな支障にならないが、頻度が気になる場合は
  [CarabinerBridge.kt](../core/src/main/kotlin/bltconnector/core/carabiner/CarabinerBridge.kt)の
  `MIN_SEND_INTERVAL_NANOS`を調整する。
- **onAir(チャンネルオンエア状態)の信頼性**: beat-linkの`CdjStatus.isOnAir()`はミキサー機種・
  配線によっては実際のフェーダー状態と一致しないことを実機で確認している。構造的な制約の
  可能性があり、確実な対処法は未確立。

- **他のPro DJ Linkソフトとのプレイヤー番号の衝突(曲情報が空になる)**: TCS ShowKontrol等、
  CDJのdbserverに問い合わせる別ソフトが同じネットワークにいると、Receiverと同じ空きプレイヤー番号
  (例: 4番)を名乗って問い合わせが衝突し、曲情報が全項目空のメタデータとして返ってくることを実機で確認した。
  CDJ-3000だけの構成なら`Config.virtualCdjDeviceNumber`を5か6にすると、Receiverは各CDJ自身の番号を
  名乗って問い合わせるようになるため衝突しない。BLT本体(beat-link)を同時に動かしている場合も同様の
  問題が起きうるので、検証中はどちらか一方だけを動かすこと。
  なお本家beat-linkは5・6番のとき問い合わせ先自身の番号を名乗るため、PCのrekordbox(17番)からLink Exportで
  読み込んだ曲では「rekordbox自身」を名乗ることになり、setupメッセージにteardownが返って曲情報が取れなかった。
  フォークでrekordbox(16番以上)宛てには自分の番号を名乗るよう修正済み。
- **OSCの送信先**: CLI版のStreamMix Display配信先は初期設定で`broadcast:8000`(Pro DJ Link側NICの
  ブロードキャストアドレス、例: 192.168.50.255)。BLT本体の`BTL_OSC.blt`は`localhost:8000`に送る設定のため
  「同じMacでBLTを動かしていれば送信先指定なしで届く」が、Pi上のCLI版からはlocalhostでは届かない。
  受信側のStreamMix Displayは「受信ホスト」を0.0.0.0にしておく必要がある。

- **OSC(StreamMix)のビート遅延**: 当初はCLIの100ms間隔のポーリングでしかOSCを送っておらず、
  CDJのビートパケットからOSC送出まで実測で中央値94ms・最大149ms遅れていた。BLTの"Beat"式と同様に
  ビートパケット受信時に`bpm`/`beat`/`beatWithinBar`を即送するようにし、中央値2.9ms・p90 3.9msになった
  (計測はPi上でeth0をキャプチャし、ポート50001のビートパケットとポート8000のOSCの時刻差を取る)。

## beat-linkのフォーク(XDJ-AZ対応)

本家beat-link 8.0.0はXDJ-AZに対応していないため、フォーク
([5373mgr/beat-link](https://github.com/5373mgr/beat-link)の`xdj-az-rekordbox-mode`ブランチ)を
`third_party/beat-link`にサブモジュールとして置き、`beat-link-fork`サブプロジェクトでソースから
ビルドしている(本家のMavenビルドは使わない)。フォークを変更したら、フォーク側でコミット・プッシュした上で
このリポジトリでサブモジュールの参照コミットを更新してコミットすること。

XDJ-AZを実機でパケットキャプチャして分かったこと:

- XDJ-AZは1〜4番(デッキ)と33番(ミキサー部)の5台分をannounceする。
- 通常の仮想CDJ(beat-linkの標準動作)として参加すると、プレイヤー番号の割り当て自体は行われるが
  CDJステータスパケットが一切届かない。さらにXDJ-AZが同じ番号を主張する0x06パケットをユニキャストしてくるため、
  番号の防衛ループに陥る。デバイス名をCDJ-3000にしても、番号を変えても、自分からステータスを送っても同じだった
  (本物のCDJ-3000との通信をキャプチャできれば手がかりになる可能性はある)。
- rekordboxとして振る舞う(beat-linkのOPUS-QUAD用`VirtualRekordbox`)と、全4デッキの完全なCDJステータスと
  0x56のメタデータパケットを送ってくる。フォークの変更は、デバイス名`XDJ-AZ`を見つけたらこのモードに入ることと、
  そのモードで不要なプレイヤー番号防衛を行わないことだけ。

このモードでの制約と対処:

- BPM/再生状態/Master/Sync/拍位置は常に取得できる。
- **PCのrekordboxからLAN経由で読み込んだ曲**は、XDJ-AZがステータス上で「rekordbox(17番)の
  COLLECTION」由来と報告するため、rekordbox本体のdbserverに問い合わせて曲名・長さ・元BPM・キー・
  波形(プレビュー/詳細)まで取得できる(実機確認済み)。本家beat-linkは互換モードで全デバイスへの
  dbserverポート問い合わせを止めてしまうため、フォークでrekordboxだけは問い合わせるようにしている。
- **XDJ-AZに挿したUSBメモリの曲**はメタデータを取得できない(XDJ-AZのdbserverへは問い合わせできないため)。
  OPUS-QUADと同様にBLTで作ったメタデータアーカイブを紐付ければPSSI照合で取れる見込みだが未検証。
  また、XDJ-AZはport 33895でdbserverを持っており、Device Library PlusのIDを使う(BLTの変更履歴より)ため、
  直接問い合わせる方式を作るならそこが手がかりになる。
- [Receiver.kt](../core/src/main/kotlin/bltconnector/core/receiver/Receiver.kt)は互換モード
  (`VirtualCdj.inOpusQuadCompatibilityMode()`)ではdbserver/メタデータ固着のwatchdogを止め、
  `MetadataFinder`をパッシブモードにする(そうしないと「メタデータが取れない=異常」と判定して
  Receiverを再起動し続け、失敗が確定している問い合わせのエラーログも大量に出る)。
- PSSI解析時に`IllegalArgumentException: 12 > 0`が出ることがある(OPUS-QUADでも見られる本家側の挙動)。
  ステータス取得には影響しない。
