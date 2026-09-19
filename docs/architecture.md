# アーキテクチャ設計(Receiver/Sender版)

## 背景・課題

Pro DJ Linkネットワーク内にBeat Link Trigger(BLT)が2台以上存在すると、既存のリンクに不具合が起きるバグがある。
そこで「1端末で受信し、別経路で複数のVJ機に配信する」という発想でプロジェクトを再設計した。

## 設計の変遷

- **当初案**: 受信用NICと配信用NICを分ける(2NIC構成)
- **見直し**: 問題の本質はProDJLinkのポートを複数プロセスが取り合うこと(デバイス番号の重複登録など)であり、
  ポートさえ分ければ解決する。NIC自体を分離する必然性はない
  → **Receiver**(ProDJLinkを唯一listenするプロセス)と**Sender**(別ポートで中継配信)に役割分割する方向に修正
- **折衷案**: SenderにNIC指定オプションを持たせ、受信用/送信用インターフェースを独立して選べるようにする。
  デフォルトは同一NICでよいが、会場によってネットワーク分離が必要な場合はNICを分けられる余地を残す

## 帯域について

- OSCでビート/フェーズ程度の情報を複数台に送っても合計数十kbps程度で、NDI(数Mbps〜数十Mbps/ストリーム)と
  比べ無視できるレベル
- ただしブロードキャストだとスイッチが全ポートにフラッディングするため、NDIなど帯域を食う機器が同居する
  ネットワークでは望ましくない。**宛先を明示したユニキャスト**(または必要ならマルチキャスト+IGMP snooping)を推奨

## 決定事項

- Win/Mac対応のGUIソフトとして、**Receiver/Sender構成**で実装を進める
- 実装言語/フレームワーク: **Kotlin + beat-link ライブラリ**(Deep SymmetryのPro DJ Link実装。BLT本体は使わない、
  ライブラリとして直接組み込む)+ **JavaFX** によるGUI、ビルドは **Gradle(Kotlin DSL)**
  - jpackageでWin/Macそれぞれのネイティブインストーラを将来作る想定
- 以前のDocker/Raspberry Pi/Linuxベースの構成は破棄。[docs/archive/old-docker-pi-design/](archive/old-docker-pi-design/) に参照用として保存

## コンポーネント構成(2026-09-18 決定、Overlay追加は2026-09-18)

```
1つのJavaFXアプリ(Kotlin)の中に Receiver / Sender / Overlay を別モジュールとして実装する
(別プロセス化はしない。NICはそれぞれのソケット/バインド先で個別に指定できるようにする)

Receiver モジュール
  - beat-link の DeviceFinder / VirtualCdj / MetadataFinder / TimeFinder / WaveformFinder / ArtFinder を使い、
    デッキ1〜4の曲名・アーティスト・アルバム・再生位置・波形プレビュー・ジャケットを取得
  - Master BPM/ビートはCarabiner経由でAbleton Linkへブリッジする(継続採用。後述)
  - 内部イベント(デッキ状態の変化)をコールバック/キューでSender/Overlayモジュールに渡す

Sender モジュール
  - 送信先VJ機のリストを管理: 手動IPアドレス登録 + mDNS等による自動検出の両対応
  - OSCメッセージとして各宛先へユニキャスト配信
  - NICを独立して指定可能(デフォルトはReceiverと同一NIC)

Overlay モジュール(2026-09-18追加、OSC実装が一段落次第着手)
  - OBSのBrowser Source等で読み込む配信画面向けオーバーレイをローカルで提供する
  - 詳細は下記「Overlay(配信画面向けオーバーレイ)」を参照
```

### Master BPMの配信経路(継続採用: Ableton Link、2026-09-19実装・実機バイナリで検証済み)

- Carabiner(Deep SymmetryのC++バイナリ、Ableton Linkブリッジ)経由でMaster BPMをAbleton Linkセッションへブリッジする
- バイナリ同梱・起動・プロトコル応答のパースはDeep Symmetry公式の`org.deepsymmetry:lib-carabiner`(Maven Central)に丸ごと任せる。
  Win/Mac(Universal)/Linux(x64・arm)向けCarabinerバイナリを`lib-carabiner`のjarがすべて同梱しており、
  `Runner.getInstance()`が実行環境に合ったものを自動抽出・子プロセス起動してくれるため、自前でのバイナリ同梱・
  ビルドは不要になった
- プロトコルは127.0.0.1宛のTCP、改行区切りのテキストコマンド/応答( https://github.com/Deep-Symmetry/carabiner#protocol )。
  `bpm <value>\n`でテンポ設定、`status`応答(edn形式)を`lib-carabiner`の`Message`クラスでMapにパースする。
  Carabinerは仕様上ループバック接続のみ受け付けるため、Carabinerと本体は同一マシンで動く必要がある(問題にはならない、
  そもそも1台のPC内で完結する設計のため)
- 実装は`core/src/main/kotlin/bltconnector/core/carabiner/CarabinerBridge.kt`。GUIのポーリングループから、
  Masterデッキの実効BPMが変化した時に`updateMasterTempo()`を呼ぶ
- `core/src/test/kotlin/.../CarabinerBridgeTest.kt`で実際にバイナリを起動し、`bpm`送信→Linkセッションのテンポ変化を
  実機バイナリで確認済み(CDJ等のハードウェアは不要、Carabiner単体で検証できる)

### OSCメッセージスキーマ(2026-09-18 確定)

フィールドごとに別アドレス、真偽値はOSCネイティブのbool型(T/F)を使う。

必要なデータの取得元(beat-linkで確認済み):
- タイトル/アーティスト/アルバム: `TrackMetadata.getTitle()` / `.getArtist().label` / `.getAlbum().label`
- 再生位置: `TimeFinder.getTimeFor(player)`
- BPM: `CdjStatus.getEffectiveTempo()`(ピッチ反映後の実効BPM)、原曲BPMは`TrackMetadata.getTempo()`(×100値)
- Masterかどうか: `CdjStatus.isTempoMaster()`
- 波形: `WaveformFinder.getLatestPreviewFor(player)` の低解像度プレビューのみ対象
  (`WaveformDetail`の高解像度版は曲によって数十KBになりUDP/OSC配信には不向きなため対象外)

帯域配慮のため2段階に分けて配信する:

**毎tick(10Hz、軽量なので常時送信)**
- `/blt/deck/{n}/position_ms` (int32)
- `/blt/deck/{n}/playing` (bool)
- `/blt/deck/{n}/bpm` (float32, 実効BPM)
- `/blt/deck/{n}/master` (bool)

**曲が変わった時のみ(`TrackMetadata.trackReference`で同一性判定。波形プレビューを毎tickは送らない)**
- `/blt/deck/{n}/title` (string)
- `/blt/deck/{n}/artist` (string)
- `/blt/deck/{n}/album` (string)
- `/blt/deck/{n}/duration_ms` (int32)
- `/blt/deck/{n}/original_bpm` (float32, 原曲BPM)
- `/blt/deck/{n}/waveform` (blob, `WaveformPreview.getData()`の生バイト列)
- `/blt/deck/{n}/waveform_color` (bool, 波形データがcolor形式[6byte/segment]かmono形式[2byte/segment]か)

宛先を新規追加した場合、その宛先に対してのみ現在の曲情報+波形を即座に再送する(Sender内で宛先ごとに
最後に送った`trackReference`を記憶し、差分があれば送る設計)。

## Overlay(配信画面向けオーバーレイ、2026-09-18決定)

**背景**: 自宅でOPUS-QUADを使ったDJ配信の際、再生中の曲情報・ジャケットを画面に出したい。
OBSのBrowser Sourceで読み込む埋め込み用HTMLを想定。

**ジャケットの取得元(beat-linkで確認済み)**: `ArtFinder.getInstance().getLatestArtFor(player)` →
`AlbumArt.getRawBytes()`(JPEG生データ、`ByteBuffer`)。数十KBになり得るため、Sender同様に毎tickではなく
曲が変わった時だけ更新する。

**方式**: OSC(外部VJソフト向け)とは別に、Receiverアプリ内にローカル専用の軽量HTTP+WebSocketサーバーを
組み込む(この用途にはOSCを使わない。ジャケットのような数十KBのバイナリはUDP/OSCのblobよりHTTPで
配る方が安全で、OBSのBrowser SourceもChromiumなのでHTTP/WebSocketと直接相性が良いため)。

```
GET  /                    -- オーバーレイ本体のHTML/CSS/JS(OBSのBrowser SourceにこのURLを指定するだけでよい)
GET  /monitor             -- VJが自分のブラウザで確認する非透過モニターページ(波形描画つき、2026-09-19追加)
GET  /art/deck/{n}        -- 現在のジャケット画像をそのまま返す(<img src="...">で直接読み込める)
GET  /waveform/deck/{n}          -- 現在の波形プレビューの生バイト列(2026-09-19追加)
GET  /waveform-detail/deck/{n}   -- 現在の高解像度波形(WaveformDetail)の生バイト列(2026-09-19追加)
WS   /ws                  -- デッキ状態(タイトル/アーティスト/アルバム/BPM/再生位置/Masterフラグ/波形)を
                              JSONでプッシュ配信
```

**VJ向け配信経路について(2026-09-19追加)**: OSC(Sender)は宛先をSender側に手動登録する
ユニキャスト方式のため、「VJ側で特に設定せずに受信できる」という要望には合わなかった。
Overlayは元々OBS向けだが、クライアント側から接続しに行くpull型でSender側の宛先登録が
不要なため、`/monitor`ページと`/waveform/deck/{n}`を追加してVJ向けの情報取得にも
流用できるようにした。OSC/Senderは既存のVJソフト連携用途のため削除せず併存させている。

**高解像度波形(WaveformDetail)について(2026-09-19追加)**: 当初プレビュー波形のみ採用した
理由は「UDP/OSC配信には大きすぎる」ためだったが、これはOSC(Sender)についての制約であり
HTTPベースのOverlay/`/monitor`には当てはまらない。そのため`Receiver`で
`WaveformFinder.setFindDetails(true)`を有効化し、`/monitor`では高解像度波形を優先して
取得・描画し、取得できない場合のみプレビュー波形にフォールバックする。OSC側は従来通り
プレビュー波形のみを送る(`DeckSnapshot.waveform`)。

- OSCの宛先管理・NIC選択とは独立して、常にlocalhost(またはOBSが同一LAN上の別PCの場合はそのNIC)で待受する
- 実装は`core`モジュールに配置(JavaFX非依存、将来のSender CLI版でも流用可能)。
  HTTP/WebSocketサーバーは`NanoHTTPD`/`NanoWSD`(org.nanohttpd:nanohttpd-websocket)を採用。
  `NanoWSD`を継承する都合上、この依存はcoreの`api`依存にしている(利用側がstart/stopを呼ぶ際に
  親クラスの型解決が必要なため。`core/build.gradle.kts`参照)
- 配布するHTML/CSS/JSはこのリポジトリ内に持ち、Overlayサーバーがそのまま静的ファイルとして返す

## モジュール構成

Receiver/Sender/OverlayのコアロジックはJavaFX(GUI)に依存しない独立モジュール(`core`)として切り出す。

```
core/  -- Kotlin純粋ロジック(JavaFX非依存)。Receiver/Sender/Overlay本体、beat-link/OSC/Carabiner連携
gui/   -- JavaFXアプリ。coreに依存し、Win/Mac向けGUIを提供
cli/   -- coreに依存するLinux(Raspberry Pi等)向けCLI + 組み込みWeb GUI
```

### CLI版(Raspberry Pi等、2026-09-19実装)

**位置づけ**: GUI版と役割は同じ(Receiver + Sender + Overlay)で、**GUI版とは二者択一の代替構成**として使う。
同じProDJLinkネットワーク上でGUI版とCLI版を同時に稼働させてはいけない
(Receiverが複数になり、冒頭の「BLTを2台立てると壊れる」問題に逆戻りするため)。

- beat-linkは実際にPro DJ Linkパケットが届いたNICを自動検出する設計(明示的なNIC指定APIはない)ため、
  受信側(Receiver)のNIC選択機能は不要。NIC選択が必要なのは送信側(Sender/Overlay)だけで、
  これは元々の設計判断通り
- 設定(送信用NIC・Overlayポート・設定画面ポート・OSC配信先リスト)はJSONファイルで管理し、
  `cli/ConfigWebServer.kt`(NanoHTTPD)による組み込みWeb GUIから編集する。保存後は
  **プロセス再起動で反映**(ホットリロードはしない。旧netconfigと同じ方針)
- `--config=<path>` 引数で設定ファイルの場所を指定可能(デフォルト: カレントディレクトリの
  `blt-connector-cli.json`)
- ビルド: `./gradlew :cli:installDist` で `cli/build/install/cli/` に実行スクリプト一式が生成される
  (Raspberry Piへtar転送する場合は `./gradlew :cli:distTar` で `cli/build/distributions/cli.tar` を使う)

**実装中に見つけて修正した不具合(実機テスト前に発見できたもの)**:
- `VirtualCdj.start()`はCDJが1台も見つからないと内部で約10秒ブロックしてから諦める仕様
  (例外は投げず`false`を返す)。当初はReceiver起動をOverlay/Web GUI起動より前に呼んでいたため、
  CDJの電源が入っていない間はOverlay/Web GUIに一切アクセスできなかった
  → Overlay/Web GUIを先に起動し、Receiverは別スレッドで起動するよう順序を修正(GUI版でも同様に
  JavaFXスレッドをブロックしないよう修正)
- `VirtualCdj`が起動していない状態で`Receiver.pollDeck()`を呼ぶと`IllegalStateException`が発生していた
  → `virtualCdj.isRunning`を確認し、未起動なら`null`を返すように修正
- CDJが見つからず`start()`が一度諦めると、そのままでは後からCDJの電源が入っても自動復帰しなかった
  → `Receiver.start()`内で(`stop()`が呼ばれるまで)見つかるまでリトライし続けるループに変更

## 未着手タスク

- [x] プロジェクト雛形(Gradle + Kotlin + JavaFX + beat-link依存関係、core/gui マルチモジュール)
- [x] Receiver: beat-linkでのデバイス検出・メタデータ取得の最小実装
- [x] OSCメッセージスキーマの確定・レビュー
- [x] Sender: OSC送信の実装(手動宛先リスト管理、曲変化時のみ曲情報/波形を再送)
- [x] GUI(JavaFX): Receiver開始 + 宛先手動追加 + 状態ログ表示(最小限)
- [x] Overlay: ジャケット取得(ArtFinder)+ ローカルHTTP/WebSocketサーバー(NanoHTTPD)+ 配布用HTML/CSS/JS。
      `GET /`・`GET /art/deck/{n}`・`WS /ws`をcurlでend-to-end確認済み(実機CDJでの見た目確認は未)
- [x] Carabiner連携によるMaster BPMブリッジ。`org.deepsymmetry:lib-carabiner`でバイナリ同梱・起動・
      プロトコル解析を実装、`bpm`送信→Linkセッションのテンポ変化を実機バイナリで確認済み(実機CDJは不要)
- [x] GUI(JavaFX): 宛先の選択削除ボタンを追加
- [x] CLI版(Receiver + Sender + Overlay、`cli`モジュール)+ 組み込みWeb GUI(NIC/OSC配信先設定)。
      ローカルで起動確認・修正済み(上記「実装中に見つけて修正した不具合」参照)
- [ ] Sender: mDNS等による宛先自動検出
- [ ] GUI(JavaFX)/CLIでのCarabiner/Overlay状態表示の充実
- [ ] Win/Mac向けjpackageパッケージング、CLI版のRaspberry Pi向け配布(distTar等)の実機検証
- [ ] 明日、実機(CDJ-3000 + DJM-A9、Raspberry Pi)で動作確認・不具合修正
