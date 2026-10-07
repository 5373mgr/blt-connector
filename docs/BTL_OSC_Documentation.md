# Beat Link Trigger OSCメッセージドキュメント

## 概要

BLT に読み込ませる設定ファイルの実体は同ディレクトリの BTL_OSC.blt。本書はその解説である。

このドキュメントは、Beat Link Trigger v7.4.1からOSC経由でCDJデバイスのデータを取得する設定をまとめたものです。DJ Link対応のCDJデッキから再生状態、テンポ、トラックメタデータをリアルタイムで受け取り、外部アプリケーション（例：StreamMix Display）で活用できます。

---

## セットアップ

### 接続先（Beat Link Trigger 側の送信設定）
- **ホスト**: `localhost`（StreamMix Display と同じMacで動かす場合）
- **ポート**: `8000`（StreamMix Display 側のデフォルト受信ポート）
- **プロトコル**: OSC (Open Sound Control) / UDP

### 受信側（StreamMix Display）の設定
受信ポートと受信ホストは StreamMix Display の設定画面（メニュー「設定…」/ Cmd+,）で変更できます。

| 項目 | デフォルト | 変更方法 |
|------|-----------|---------|
| 受信ポート | `8000` | 設定画面「OSCサーバー」の「受信ポート」（1〜65535） |
| 受信ホスト | `127.0.0.1`（このMacのみ） | 設定画面「OSCサーバー」の「受信ホスト」Picker |

- 受信ホストは「このMacのみ（127.0.0.1）」/「すべてのインターフェース（0.0.0.0）」の2択です。**別のマシンで動くDJソフトからOSCを受ける場合は `0.0.0.0` に変更**してください。
- 変更後は「適用してサーバー再起動」ボタンを押すとOSCサーバーが再起動され、設定が反映されます。

### 監視対象デッキ
- デッキ 1
- デッキ 2
- デッキ 4

※ 実機設定（`Document/BTL_OSC.blt`）のトリガーは players 1 / 2 / 4 の3つです。デッキ3のトリガーはありません。

※ StreamMix Display 側はデッキ番号 1〜4 のメッセージのみ受理します（範囲外のデッキ番号は無視されます）。

---

## OSCメッセージ構造

### 基本形式

```
/deck/{deck_number}/{message_type} {value}
```

各メッセージは以下の構成となります：
- `deck_number`: デッキ番号（1〜4）
- `message_type`: メッセージの種類
- `value`: 送信データ（型はメッセージごとに異なる）

---

## メッセージ一覧

### 1. 再生状態メッセージ

| メッセージ | 型 | 値 | 説明 |
|----------|----|----|------|
| `/deck/{n}/playing` | int | 0 / 1 | 再生状態。1=再生中、0=停止 |
| `/deck/{n}/onAir` | int | 0 / 1 | オンエア状態。1=オンエア、0=オフエア。**同梱の `BTL_OSC.blt` はこのアドレスを送信しない**（`activation`/`tracked`/`deactivation`/`beat` のどの式にも送信コードがなく、`DeckState.isOnAir` は常に `false` のまま）。ここに載せているのは受信側（`OSCViewModel`）の対応能力であり、送りたい場合は BLT 側で送信式を追加する必要がある |

**⚠️ 上表のとおり `onAir` は同梱の `BTL_OSC.blt` が送りません**（`grep onAir` が0ヒット）。受信側は macOS / Windows とも実装済みなので、実際に着弾するのはテストツール経由の場合だけです（`Diagnostics/FakeBLT/fakeblt.py --legacy-onair` および旧 `Diagnostics/FakePadWin/Send-Osc.ps1`）。**実機の挙動を再現したいときは `onAir` を送らないでください。**

**タイミング**（`onAir` を送る設定にした場合。既定では送られません）
- **Activation**: 再生開始時に即座に送信（値: 1）
- **Deactivation**: 再生停止時に即座に送信（値: 0）
- **Tracked**: state に差分がある時に再度送信（→「3. Tracked」の差分判定。再生中はビート進行でも差分が成立するため繰り返し届きうる）

---

### 2. テンポ・ビート情報

| メッセージ | 型 | 値 | 説明 |
|----------|----|----|------|
| `/deck/{n}/bpm` | float | 例) 128.5 | 現在のBPM（ピッチ反映済み） |
| `/deck/{n}/beat` | int | 1-4, 1-8, 1-16 | 現在のビート番号 |
| `/deck/{n}/beatWithinBar` | int | 1-4 | バー内でのビート位置（1-4拍） |
| `/deck/{n}/pitchMultiplier` | float | 0.0 - 2.0 | ピッチ調整率 |

**タイミング**
- **Beat**: 各ビート時に継続送信
- **Tracked**: state に差分がある時に送信（→「3. Tracked」の差分判定。再生中はビート進行でも差分が成立するため繰り返し届きうる）

---

### 3. トラックメタデータ（文字列）

| メッセージ | 型 | 値 | 説明 |
|----------|----|----|------|
| `/deck/{n}/title` | string | 例) "Song Title" | 曲名 |
| `/deck/{n}/artist` | string | 例) "Artist Name" | アーティスト |
| `/deck/{n}/album` | string | 例) "Album Name" | アルバム名 |
| `/deck/{n}/genre` | string | 例) "Techno" | ジャンル |
| `/deck/{n}/key` | string | 例) "C Min" | ミュージカルキー |
| `/deck/{n}/label` | string | 例) "Record Label" | レーベル |
| `/deck/{n}/originalArtist` | string | 例) "Original Artist" | オリジナルアーティスト |
| `/deck/{n}/remixer` | string | 例) "Remixer Name" | リミキサー |
| `/deck/{n}/color` | string | 例) "Red" | 色ラベル |
| `/deck/{n}/comment` | string | 例) "Live mix version" | コメント |
| `/deck/{n}/dateAdded` | string | 例) "2024-12-15" | ライブラリに追加した日付 |

**タイミング**: Tracked の一括送信に乗る（前回の state から1キーでも変わった場合）（→「3. Tracked」の差分判定。再生中はビート進行でも差分が成立するため繰り返し届きうる）

**注記**: 値がない場合は空文字列 `""` が送信されます（`track-metadata` そのものが nil の場合も、これら11メッセージは `""` で送信されます）

---

### 4. トラックメタデータ（数値）

| メッセージ | 型 | 値 | 説明 |
|----------|----|----|------|
| `/deck/{n}/duration` | float | 例) 240.5 | 曲の長さ（秒） |
| `/deck/{n}/bitRate` | int | 例) 320 | ビットレート（kbps） |
| `/deck/{n}/rating` | int | 0-5 | レーティング（★数） |
| `/deck/{n}/tempoMeta` | int | 例) 12800 | テンポメタデータ（BPM×100） |
| `/deck/{n}/year` | int | 例) 2024 | リリース年 |
| `/deck/{n}/artworkId` | int | 0以上 | アートワークID |
| `/deck/{n}/hasCues` | int | 0 / 1 | キューの有無（0=なし、1=あり） |

**タイミング**: Tracked の一括送信に乗る（→「3. Tracked」の差分判定。再生中はビート進行でも差分が成立するため繰り返し届きうる）

**注記**: 値がない場合は 0 が送信されます（`tempoMeta` はBPM×100なので、128BPMの場合12800）。`track-metadata` そのものが nil の場合も、これら7メッセージは 0（`duration` は `0.0`）で送信されます

---

### 5. デバッグ用メッセージ

| メッセージ | 型 | 説明 |
|----------|----|----|
| `/deck/{n}/metadata/raw` | string | TrackMetadataオブジェクト全体の文字列ダンプ |

**タイミング**: Tracked の一括送信に乗る（デバッグ用）（→「3. Tracked」の差分判定。再生中はビート進行でも差分が成立するため繰り返し届きうる）

**⚠️ 注記**: このメッセージだけは `(when tm ...)` でガードされており、**`track-metadata` が nil のときは送信されません**（他の23メッセージはフォールバック値で送られる）。一括送信の通数が24ではなく23になる唯一のケースです。

---

## 送信タイミング

### 1. Activation（再生開始時）
```
/deck/{n}/playing 1
```
再生ボタンが押された直後、即座に1回だけ送信されます。

### 2. Deactivation（再生停止時）
```
/deck/{n}/playing 0
```
再生停止の直後、即座に1回だけ送信されます。

### 3. Tracked（state に差分がある時）
追跡中プレイヤーのステータス更新のたびに評価され、前回送った state と1キーでも違えば以下をすべて送信します：
- 再生状態
- テンポ・ビート情報
- トラックメタデータ（すべて）

**重要**: 前回の値から変更がない場合は送信されません（差分ベース）。

**差分判定の実体**: Tracked 表現式は送信予定の値を1つの state マップ（device / playing / beat-number / beat-in-bar / tempo / pitch-mul ＋ メタデータ18種の計24キー）にまとめ、トリガーの locals に保存した前回値と比較して、**1つでも違う場合にのみ一括送信**します。1つも違わなければ1通も送りません。

**⚠️ 一括送信の通数は24とは限らない（23通のケース）**: 末尾の `/deck/{n}/metadata/raw` だけは `(when tm ...)` でガードされており、**`track-metadata` が nil のときは送信されません**（＝そのバッチは **23メッセージ**になります）。他の23メッセージは tm が nil でもフォールバック値で送られます（文字列は `""`、数値は `0`、`duration` は `0.0`、`hasCues` は `0`）。再生・テンポ系の5メッセージは tm を参照しないため常に送られます。受信側は「必ず24通来る」前提を置かず、**アドレス単位で処理**してください。なお state マップ側は nil のまま比較されるため、メタデータが取れない状態で別のトラックを積んでも（テンポが同じなら）差分は成立せず1通も飛びません。

**⚠️ 再生中も一括送信が繰り返し届きうる**: state には `beat-number` と `beat-in-bar` が含まれます。Tracked 表現式は追跡中プレイヤーのステータス更新のたびに呼ばれるため、**再生中はビートが進むたびに state が変わり、差分ゲートを通過します**。つまり「メタデータ系はトラック変更やテンポ変化の瞬間にしか届かない」という前提を置いてはいけません。メタデータ文字列を含む一括送信（24メッセージ、`track-metadata` が nil なら23メッセージ）が再生中ずっと繰り返し飛ぶ想定で受信側を設計してください。ただし**実機での発火頻度は未計測**です（呼び出し頻度＝プレイヤーのステータスパケットのレート、実際に送信が起きる頻度＝それと beat-number が変わるレートの小さい方、という理屈上の想定に留まります）。停止中は beat 系が動かないため、メタデータも変わらなければ state は変化せず、送信は止まります。

**送信ソケットの実体**: 全トリガー（デッキ1/2/4）はトップレベル setup が生成した**単一の共有 osc-client** から送信するため、受信側から見た送信元ポートは全デッキで同一になります（各トリガーがローカルに作る osc-client は参照されていません）。テストツールで実機を模す場合は、この点と差分送信を守らないと実機では起こらない状態でテストが通ってしまいます。

### 4. Beat（各ビート時）
```
/deck/{n}/bpm {値}
/deck/{n}/beat {値}
/deck/{n}/beatWithinBar {値}
```
再生中は各ビートのタイミングで継続送信されます。停止中は送信されません。

---

## データ型リファレンス

| 型 | 説明 | 例 |
|----|------|-----|
| **int** | 整数 | 1, 0, 128, 12800 |
| **float** | 浮動小数点数 | 128.5, 0.95, 240.75 |
| **string** | 文字列 | "Techno", "Song Title", "" |

---

## 実装例

### macOSでの受け取り（Swift疑似コード）

```swift
import OSCKit

class CDJDataReceiver {
    let oscServer: OSCServer
    
    init() {
        oscServer = OSCServer(address: "localhost", port: 8000)
        
        // デッキ1の再生状態を監視
        oscServer.addHandler(for: "/deck/1/playing") { message in
            let isPlaying = message.values[0] as? Int == 1
            print("Deck 1 Playing: \(isPlaying)")
        }
        
        // BPMを監視
        oscServer.addHandler(for: "/deck/1/bpm") { message in
            let bpm = message.values[0] as? Float ?? 0.0
            print("Deck 1 BPM: \(bpm)")
        }
        
        // トラックメタデータを監視
        oscServer.addHandler(for: "/deck/1/title") { message in
            let title = message.values[0] as? String ?? ""
            print("Deck 1 Track Title: \(title)")
        }
        
        oscServer.start()
    }
}
```

### 受け取るメッセージ例

デッキ1で新しいトラックを再生した場合の送信シーケンス：

```
1. /deck/1/playing 1                          (即座に)
2. /deck/1/playing 1                          (Tracked内)
3. /deck/1/bpm 128.5                          (Tracked内)
4. /deck/1/beat 1                             (Tracked内)
5. /deck/1/beatWithinBar 1                    (Tracked内)
6. /deck/1/pitchMultiplier 1.0                (Tracked内)
7. /deck/1/title "New Song"                   (Tracked内)
8. /deck/1/artist "Artist Name"               (Tracked内)
... (その他メタデータ)
9. /deck/1/bpm 128.5                          (次のビートで)
10. /deck/1/beat 2                            (次のビートで)
11. /deck/1/beatWithinBar 2                   (次のビートで)
```

---

## トラブルシューティング

### OSCメッセージが届かない場合

1. **Beat Link Triggerが起動しているか確認**
   - アプリケーションが実行中であることを確認

2. **CDJ接続を確認**
   - CDJデバイスがDJ Link ネットワークに接続されているか確認
   - Triggerで「Carabiner」接続が確認できているか確認

3. **ネットワーク設定を確認**
   - 送信先ポートが StreamMix Display 設定画面の「受信ポート」（デフォルト `8000`）と一致しているか確認
   - 別マシンから送信している場合、StreamMix Display の「受信ホスト」が「すべてのインターフェース（0.0.0.0）」になっているか確認（デフォルトは `127.0.0.1` で同一Mac内のみ受信）
   - 設定変更後に「適用してサーバー再起動」を押したか確認
   - ファイアウォール設定でOSCポートがブロックされていないか確認

4. **MetadataFinderが起動しているか確認**
   - Triggerのログでメタデータ取得が走っているか確認
   - Tracked 表現式は全体が `(when (.isRunning mf) ...)` で囲まれているため、**MetadataFinder が起動していないと1通も送信されません**
   - 起動していれば、メタデータそのものが取れない状態（`track-metadata` が nil）でも 23 メッセージは送られます（→「3. Tracked」）

### 一部のメッセージのみ来ない場合

- **メタデータが空**: CDJのライブラリにメタデータが記録されていない可能性
- **ビート系が来ない**: トラック再生中でないか、`beat` 表現式が有効でない可能性
- **数値が0**: メタデータが記録されていないか、CDJバージョンが古い可能性

---

## 参考情報

- **Beat Link Trigger**: https://github.com/Deep-Symmetry/beat-link-trigger
- **OSC仕様**: http://opensoundcontrol.org/
- **DJ Link プロトコル**: Pioneer社のDJ Link対応CDJ間通信

---

## 更新履歴

| 日付 | 版 | 説明 |
|------|-----|------|
| 2024-12-15 | 1.0 | 初版作成（BTL v7.4.1ベース） |
| 2026-06-12 | 1.1 | 受信ポート・ホストの設定画面対応（v0.9.4 dev）を追記。`onAir` を追加し、実装に存在しない `bar` を `beatWithinBar` に修正 |
| 2026-08-02 | 1.2 | `onAir` の記述を修正: 同梱の `BTL_OSC.blt` はこのアドレスを送信しないことを明記（受信側 `OSCViewModel` の対応能力として記載） |
| 2026-08-02 | 1.3 | 実機設定 `BTL_OSC.blt` と照合して修正。監視対象デッキに「トリガーは players 1 / 2 / 4 でデッキ3は無い」旨を補足、Tracked の差分判定（24キーの state 比較）と単一共有 osc-client の挙動を追記。state に `beat-number` / `beat-in-bar` が含まれるため**再生中も一括送信が繰り返し届きうる**点を明記（発火頻度は未計測）。`onAir` の項には 1.2 の記述に加えて「着弾するのはテストツール経由だけ」を補足 |
| 2026-08-02 | 1.4 | `/deck/{n}/metadata/raw` が `(when tm ...)` でガードされており、`track-metadata` が nil のときは送られない（一括送信が**23メッセージ**になる）点を明記。他の23メッセージが nil 時にフォールバック値（`""` / `0` / `0.0`）で送られることも追記 |
