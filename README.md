# BLT Connector

Pioneer Pro DJ Link (CDJ/ミキサー) の情報を、複数のVJマシンへ配信するソフト。
Pro DJ Link上にBeat Link Trigger (BLT) を2台以上立てると既存のリンクが壊れるバグがあるため、
「1端末だけがPro DJ Linkを受信する(Receiver)」「別チャンネルで複数VJへ中継配信する(Sender)」という
役割分割の設計にしている。設計の経緯・決定事項は [docs/architecture.md](docs/architecture.md) を参照。

以前検討していたDocker/Raspberry Pi/Linuxベースの設計は破棄した。参照用に
[docs/archive/old-docker-pi-design/](docs/archive/old-docker-pi-design/) に残している。

## 構成

Gradle(Kotlin DSL)のマルチモジュール構成。2つの配布形態(GUI版/CLI版)は
**Receiver+Sender+Overlayという同じ役割を持つ、二者択一の代替構成**(同じProDJLinkネットワーク上で
同時に動かさないこと。Receiverが複数になり冒頭のバグに逆戻りするため)。

- [core/](core/) — Receiver(beat-linkでPro DJ Linkを受信)・Sender(OSCでVJ機へユニキャスト配信)・Overlay(OBS向けローカルHTTP/WebSocket配信)のロジック本体。JavaFXに非依存。
- [gui/](gui/) — JavaFXによるWin/Mac向けGUI版。`core`に依存。
- [cli/](cli/) — Linux(Raspberry Pi等)向けCLI版 + 組み込みWeb GUI(NIC/OSC配信先設定)。`core`に依存。

## 現状のステータス

- [x] プロジェクト雛形(Gradle + Kotlin + JavaFX + beat-link、core/gui/cliマルチモジュール)
- [x] Receiver: beat-linkでのデバイス検出・メタデータ取得(`core/src/main/kotlin/bltconnector/core/receiver/Receiver.kt`)。タイトル/アーティスト/アルバム/再生位置/実効BPM/Masterフラグ/波形プレビュー/ジャケット取得済み。**実機のCDJでの動作確認は未**
- [x] Sender: OSC送信の実装(`core/src/main/kotlin/bltconnector/core/sender/Sender.kt`)。曲が変わった時だけ曲情報/波形を再送する2段階配信、単体テストあり。宛先は手動追加のみ、mDNS等の自動検出は未実装
- [x] OSCメッセージスキーマ確定([docs/architecture.md](docs/architecture.md)参照)
- [x] GUI(JavaFX): Receiver開始 + 宛先追加/削除 + 状態ログ表示(最小限)
- [x] Overlay: ジャケット取得(ArtFinder)+ ローカルHTTP/WebSocketサーバー + OBS向けHTML/CSS/JS(`http://localhost:8090/`をOBSのBrowser Sourceに指定)。**実機CDJでの見た目確認は未**
- [x] Carabiner連携によるMaster BPMブリッジ(`core/src/main/kotlin/bltconnector/core/carabiner/CarabinerBridge.kt`)。
      `org.deepsymmetry:lib-carabiner`がWin/Mac/Linux向けバイナリを同梱・自動起動してくれるので自前でのバイナリ準備は不要。
      実際にバイナリを起動して`bpm`送信→Ableton Linkのテンポ変化を確認する単体テストあり(CDJ不要で検証可能)
- [x] CLI版(`cli/`): Receiver + Sender + Overlayをそのまま束ねたLinux向けCLI + 組み込みWeb GUI。
      ローカルで起動確認済み。実装中にCDJ未接続時の実挙動から2つの不具合を発見・修正
      (詳細は[docs/architecture.md](docs/architecture.md)の「CLI版」節を参照)
- [ ] Sender: mDNS等による宛先自動検出
- [ ] GUI/CLIでのCarabiner/Overlay状態表示の充実
- [ ] Win/Mac向けjpackageパッケージング
- [ ] 明日、実機(CDJ-3000 + DJM-A9、Raspberry Pi)での動作確認・不具合修正

## 既知の環境固有の問題

- **Windows + 非ASCIIパス**: プロジェクトパスに日本語などの非ASCII文字が含まれると、GradleのTestワーカーの
  クラスローディングが`ClassNotFoundException`で失敗する既知の問題がある。回避策として、ルートの
  [build.gradle.kts](build.gradle.kts)でビルド出力先をOS一時ディレクトリ配下(ASCIIパス)に変更している。

## 開発

```bash
./gradlew build            # 全体ビルド・テスト
./gradlew :gui:run         # GUI版を起動
./gradlew :cli:run         # CLI版を起動(カレントディレクトリに blt-connector-cli.json を生成/参照)
./gradlew :cli:installDist # CLI版の実行スクリプト一式を cli/build/install/cli/ に生成(Pi等への配布用)
```

JDK 21が必要(Gradle toolchainで自動解決を試みるが、ローカルにJDK 21が無い場合は別途用意すること)。

CLI版の設定画面はデフォルトで `http://localhost:8081/`、Overlayは `http://localhost:8090/`。
設定を保存したらプロセスを再起動して反映する(ホットリロードはしない)。
