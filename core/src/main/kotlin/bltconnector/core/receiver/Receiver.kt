package bltconnector.core.receiver

import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.VirtualCdj
import org.deepsymmetry.beatlink.data.ArtFinder
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.OpusProvider
import org.deepsymmetry.beatlink.Util
import org.deepsymmetry.beatlink.data.TimeFinder
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.slf4j.LoggerFactory
import java.io.File

/**
 * ホットキュー/メモリーキュー1件分(beat-linkの`CueList.Entry`から変換)。
 * [hotCueNumber] が0ならメモリーキュー、1以上ならホットキュー番号(1=A, 2=B, ...)。
 * [colorRgb] は`"#rrggbb"`形式。色情報が無い場合はnull。
 */
data class CuePoint(
    val timeMs: Long,
    val hotCueNumber: Int,
    val isLoop: Boolean,
    val colorRgb: String?,
    val comment: String,
)

/**
 * デッキ1台分の現在のスナップショット。
 * beat-linkの各種APIの正確な挙動は実機のCDJ+ネットワークでの検証がまだ済んでいない。
 *
 * [trackKey] は曲の同一性判定に使う(beat-linkの `TrackMetadata.trackReference`)。
 * Senderはこれが前回と変わった時だけ曲情報(title/artist/album/waveform等)を送る。
 */
data class DeckSnapshot(
    val playerNumber: Int,
    val positionMs: Long?,
    val playing: Boolean,
    val bpm: Double,
    val master: Boolean,
    val trackKey: Any?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val originalBpm: Double?,
    val waveform: ByteArray?,
    val waveformColor: Boolean,
    /**
     * 高解像度波形(beat-linkの`WaveformDetail`)の生バイト列。OSC(Sender)では従来通り
     * [waveform](プレビュー波形)のみ使う。曲によっては数十KBになり得るため、UDP/OSCでの
     * 常時配信には使わず、Overlay(HTTP)経由でのみ配信する。
     */
    val waveformDetail: ByteArray? = null,
    val waveformDetailColor: Boolean = false,
    /**
     * 高解像度波形のbeat-linkオブジェクトそのもの(生バイト列と違い、`segmentHeight`/`segmentColor`
     * で正しくデコードできる。3Band波形はバイト列の単純な解釈だけでは描画できないため、
     * Overlay側でオンデマンドに描画する際はこちらを使う)。JSON化やOSC配信の対象外。
     */
    val waveformDetailObject: WaveformDetail? = null,
    /** ジャケット画像の生バイト列(JPEG想定)。Overlay配信用。 */
    val artwork: ByteArray?,
    /** ホットキュー/メモリーキューの一覧(Overlay配信用、OSCへは配信しない)。 */
    val cues: List<CuePoint> = emptyList(),
    /** rekordboxでトラックに付けられたコメント。 */
    val comment: String? = null,
    /**
     * ミキサー上でこのチャンネルが実際にオンエア(フェーダーが上がっていて音が出ている)か。
     * Nexus系ミキサーのみ対応(beat-linkの`CdjStatus.isOnAir()`)。
     */
    val onAir: Boolean = false,
    /** Sync(Master追従)が有効か(beat-linkの`CdjStatus.isSynced()`)。 */
    val synced: Boolean = false,
    /** トラック先頭からの絶対ビート番号(beat-linkの`CdjStatus.getBeatNumber()`)。 */
    val beatNumber: Int = 0,
    /** 小節内のビート位置1〜4(beat-linkの`CdjStatus.getBeatWithinBar()`)。 */
    val beatWithinBar: Int = 0,
    /** ピッチフェーダーの倍率(1.0が等速、`Util.pitchToMultiplier()`)。 */
    val pitchMultiplier: Double = 1.0,
    val genre: String? = null,
    val key: String? = null,
    val label: String? = null,
    val originalArtist: String? = null,
    val remixer: String? = null,
    /** rekordboxの色ラベル名(例: "Red")。beat-linkの`ColorItem.colorName`。 */
    val colorName: String? = null,
    val dateAdded: String? = null,
    val bitRate: Int? = null,
    val rating: Int? = null,
    val year: Int? = null,
    val artworkId: Int? = null,
    /** 原曲のテンポメタデータ(BPM×100の生値、beat-linkの`TrackMetadata.getTempo()`)。 */
    val tempoMetaRaw: Int? = null,
    /** `TrackMetadata`オブジェクト全体の文字列ダンプ(デバッグ用配信向け)。 */
    val metadataRawDump: String? = null,
)

/**
 * Pro DJ Linkを唯一listenするReceiver。
 * BLT本体は使わず、beat-linkライブラリを直接組み込んで最小限の情報だけを取得する。
 */
class Receiver {
    private val logger = LoggerFactory.getLogger(Receiver::class.java)

    private val deviceFinder = DeviceFinder.getInstance()
    private val virtualCdj = VirtualCdj.getInstance()
    private val metadataFinder = MetadataFinder.getInstance()
    private val timeFinder = TimeFinder.getInstance()
    private val waveformFinder = WaveformFinder.getInstance()
    private val artFinder = ArtFinder.getInstance()

    @Volatile private var shouldRun = false
    /** metadataFinder等のサブコンポーネントまで含めて起動が完了したか。pollDeck()のレース防止用。 */
    @Volatile private var started = false

    /**
     * Receiverを起動する。呼び出し元スレッドをブロックする点に注意
     * (CDJが1台も見つからない間、`VirtualCdj.start()`は内部で約10秒待ってから諦めるため、
     * 呼び出し側は別スレッドで呼ぶこと)。
     *
     * CDJがまだ電源投入されていない/ネットワークに現れていない場合に備え、
     * 見つかるまで(または`stop()`が呼ばれるまで)内部でリトライし続ける。
     *
     * [waveformStyle] は色波形(RGB)か3Band波形かを指定する。beat-link側の制約でアプリ全体に
     * つき1つしか同時に扱えない。
     *
     * [preferredDeviceNumber] を1〜6に指定すると、VirtualCdjがその番号を明示的に要求する
     * (`VirtualCdj.setDeviceNumber()`)。0(既定)なら従来通りbeat-link側の自動選択(起動時に
     * 見えているデバイスを4秒間観察し、1〜4のうち空いている最小の番号を選ぶ。全て使用中なら
     * 5番以降へフォールバックするが、5番以降はdbserverのメタデータ問い合わせ対象外になる)に任せる。
     *
     * 自動選択は「起動時点でまだ電源が入っていない実機CDJ」の番号と後から衝突しうる
     * (実機で確認: Receiverが起動直後に空き番号だった1番を自己割り当てした後、deck1に固定設定された
     * 実機CDJが電源投入され同じ1番を要求し続け、`Sending device number defense packet to invader`が
     * 無限に繰り返されてそのCDJがPro DJ Linkに安定して参加できなくなった)。実機CDJの台数より
     * 大きい番号を[preferredDeviceNumber]に指定することで回避できる。
     *
     * 5・6番はbeat-link自身の自動選択では(実機の5・6番目のプレイヤーとの衝突を避けるため)
     * あえて避けているが、CDJ-3000世代のネットワーク(1台のPro DJ Linkに最大6台のプレイヤーを
     * 収容できる)では5・6番も「本物のプレイヤー」扱いになり、dbserverのメタデータ問い合わせに
     * 応答することを実機で確認済み。実機CDJが4台(1〜4番)ある場合は5か6を指定すれば
     * メタデータ取得を維持できる(ただし全てCDJ-3000世代であることが前提。Nexus等の旧世代機器が
     * 混在する場合は5・6番が使えるとは限らない)。
     */
    fun start(
        waveformStyle: WaveformFinder.WaveformStyle = WaveformFinder.WaveformStyle.RGB,
        preferredDeviceNumber: Int = 0,
    ) {
        shouldRun = true
        // MetadataFinderがdbserver経由でタイトル/アーティスト/波形/ジャケットを問い合わせるには、
        // VirtualCdjが1〜4番のプレイヤー番号を借用できる必要がある(実機CDJで確認済み。
        // これがfalseのままだと自己割り当て番号が7以上になり、メタデータ問い合わせが永久に失敗する)。
        virtualCdj.setUseStandardPlayerNumber(true)
        if (preferredDeviceNumber in 1..6) {
            virtualCdj.deviceNumber = preferredDeviceNumber.toByte()
        }
        while (shouldRun) {
            try {
                deviceFinder.start()
                if (virtualCdj.start()) break
                logger.info("Pro DJ Linkデバイスが見つからないため、起動を再試行します...")
            } catch (e: Exception) {
                // DeviceFinder/VirtualCdjのソケットバインドは、Pro DJ Linkのポートを他プロセス
                // (rekordbox等)が既に使っている場合にここで例外を投げる。リトライループの外側で
                // 起きるとReceiver起動スレッドが静かに死んでしまうため、ここで捕捉して再試行する。
                logger.warn("Receiverの起動に失敗しました。再試行します...", e)
                Thread.sleep(2000)
            }
        }
        if (!shouldRun) return

        metadataFinder.start()
        timeFinder.start()
        waveformFinder.setPreferredStyle(waveformStyle)
        // 高解像度波形(WaveformDetail)も取得する。プレビューと違いOSCでは配信せず、
        // Overlay(HTTP)経由でのみ使うため、UDPパケットサイズの制約は関係ない。
        waveformFinder.setFindDetails(true)
        waveformFinder.start()
        artFinder.start()
        started = true
        logger.info("Receiver started.")
    }

    /**
     * OPUS-QUAD利用時、事前に(Beat Link Trigger等で)作成済みのメタデータアーカイブをUSBスロットへ
     * 紐付ける。アーカイブの生成自体はこのプロジェクトでは行わない(BLTの「File > Archive Metadata」で
     * 作成したファイルをそのまま指定する想定)。CDJ等、Opus Quad互換モードでない場合は何もしない
     * (呼んでも無害)。`start()`が完了し、Pro DJ Linkデバイスが見つかった後に呼ぶこと。
     *
     * [archives] はUSBスロット番号→アーカイブファイルのマップ。beat-linkの`OpusProvider`の制約により
     * 有効なスロット番号は1〜3(プレイヤー番号ではなく物理USBスロット番号)。
     */
    fun attachOpusArchives(archives: Map<Int, File>) {
        if (archives.isEmpty()) return
        if (!virtualCdj.inOpusQuadCompatibilityMode()) {
            logger.info("OPUS-QUAD互換モードではないため、メタデータアーカイブ設定は無視します")
            return
        }
        archives.forEach { (slot, file) ->
            try {
                OpusProvider.getInstance().attachMetadataArchive(file, slot)
                logger.info("OPUS-QUADのUSBスロット{}にメタデータアーカイブを紐付けました: {}", slot, file)
            } catch (e: Exception) {
                logger.warn("OPUS-QUADのUSBスロット{}へのメタデータアーカイブ紐付けに失敗しました: {}", slot, file, e)
            }
        }
    }

    fun stop() {
        shouldRun = false
        started = false
        artFinder.stop()
        waveformFinder.stop()
        timeFinder.stop()
        metadataFinder.stop()
        virtualCdj.stop()
        deviceFinder.stop()
    }

    /**
     * デッキ1台分の現在の状態を取得する。デバイスが見えていない/CDJでなければnull。
     * まだCDJが1台も見つかっておらずVirtualCdjが起動していない間もnullを返す
     * (beat-linkはこの状態で `getLatestStatusFor` を呼ぶと例外を投げるため)。
     * `start()`がmetadataFinder等のサブコンポーネントまで起動し終える前に呼ばれた場合も
     * nullを返す(呼び出し元は`start()`を別スレッドで呼んだ直後からポーリングを始めることが
     * あり、サブコンポーネント起動完了前に呼ぶと`IllegalStateException`になるため。実機で確認済み)。
     */
    fun pollDeck(playerNumber: Int): DeckSnapshot? {
        if (!started || !virtualCdj.isRunning) return null
        val status = virtualCdj.getLatestStatusFor(playerNumber) as? CdjStatus ?: return null
        val metadata = metadataFinder.getLatestMetadataFor(playerNumber)
        val positionMs = timeFinder.getTimeFor(playerNumber)
        val waveform = waveformFinder.getLatestPreviewFor(playerNumber)
        val waveformDetail = waveformFinder.getLatestDetailFor(playerNumber)
        val art = artFinder.getLatestArtFor(playerNumber)
        // 曲の同一性判定には`metadata?.trackReference`(dbserver問い合わせ成功時のみ非null)ではなく、
        // CdjStatusの生フィールド(rekordboxId等、メタデータ取得の成否に関係なく常に得られる)から
        // 直接組み立てる。dbserverのメタデータ問い合わせが特定の曲で失敗し続けると(実機CDJ-3000で
        // 確認済み、原因は曲ごとのライブラリデータ側の問題と見られる)、`metadata`が恒久的にnullになり、
        // 「メタデータなしの曲A」と「メタデータなしの曲B」の区別がつかず、以後その曲が何度切り替わっても
        // 曲変化そのものを検知できなくなる(波形/ジャケットの再取得トリガーやOverlayのtrackVersionが
        // 固まったままになる)不具合があったため。
        val trackKey = status.rekordboxId.takeIf { it != 0 }
            ?.let { DataReference(status.trackSourcePlayer, status.trackSourceSlot, it, status.trackType) }

        return DeckSnapshot(
            playerNumber = playerNumber,
            positionMs = positionMs.takeIf { it >= 0 },
            playing = status.isPlaying,
            // トラック未ロード時、beat-linkの生bpmフィールドはPro DJ Linkプロトコル上の無効値センチネル
            // 0xFFFF(65535)になる。CdjStatus.getEffectiveTempo()はこれをそのままピッチ倍したdouble値を
            // 返す(655.35付近)ため、ここでガードしないと未ロードデッキの見た目のBPMとして流れてしまう
            // (実機のOPUS-QUADで確認済み)。
            bpm = if (status.bpm == 0xFFFF) 0.0 else status.effectiveTempo,
            master = status.isTempoMaster,
            trackKey = trackKey,
            title = metadata?.title,
            artist = metadata?.artist?.label,
            album = metadata?.album?.label,
            durationMs = metadata?.duration?.let { it * 1000L },
            originalBpm = metadata?.tempo?.let { it / 100.0 },
            waveform = waveform?.data?.let { buf ->
                ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
            },
            waveformColor = waveform?.isColor ?: false,
            waveformDetail = waveformDetail?.data?.let { buf ->
                ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
            },
            waveformDetailColor = waveformDetail?.isColor ?: false,
            waveformDetailObject = waveformDetail,
            artwork = art?.rawBytes?.let { buf ->
                ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
            },
            cues = metadata?.cueList?.entries.orEmpty().map { entry ->
                val color = entry.rekordboxColor ?: entry.embeddedColor
                CuePoint(
                    timeMs = entry.cueTime,
                    hotCueNumber = entry.hotCueNumber,
                    isLoop = entry.isLoop,
                    colorRgb = color?.let { String.format("#%02x%02x%02x", it.red, it.green, it.blue) },
                    comment = entry.comment,
                )
            },
            comment = metadata?.comment,
            onAir = status.isOnAir,
            synced = status.isSynced,
            beatNumber = status.beatNumber,
            beatWithinBar = status.beatWithinBar,
            pitchMultiplier = Util.pitchToMultiplier(status.pitch.toLong()),
            genre = metadata?.genre?.label,
            key = metadata?.key?.label,
            label = metadata?.label?.label,
            originalArtist = metadata?.originalArtist?.label,
            remixer = metadata?.remixer?.label,
            colorName = metadata?.color?.colorName,
            dateAdded = metadata?.dateAdded,
            bitRate = metadata?.bitRate,
            rating = metadata?.rating,
            year = metadata?.year,
            artworkId = metadata?.artworkId,
            tempoMetaRaw = metadata?.tempo,
            metadataRawDump = metadata?.toString(),
        )
    }
}
