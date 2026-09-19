package bltconnector.core.receiver

import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.VirtualCdj
import org.deepsymmetry.beatlink.data.ArtFinder
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.TimeFinder
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.slf4j.LoggerFactory

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
     */
    fun start(waveformStyle: WaveformFinder.WaveformStyle = WaveformFinder.WaveformStyle.RGB) {
        shouldRun = true
        // MetadataFinderがdbserver経由でタイトル/アーティスト/波形/ジャケットを問い合わせるには、
        // VirtualCdjが1〜4番のプレイヤー番号を借用できる必要がある(実機CDJで確認済み。
        // これがfalseのままだと自己割り当て番号が7以上になり、メタデータ問い合わせが永久に失敗する)。
        virtualCdj.setUseStandardPlayerNumber(true)
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

        return DeckSnapshot(
            playerNumber = playerNumber,
            positionMs = positionMs.takeIf { it >= 0 },
            playing = status.isPlaying,
            bpm = status.effectiveTempo,
            master = status.isTempoMaster,
            trackKey = metadata?.trackReference,
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
        )
    }
}
