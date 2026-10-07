package bltconnector.core.sender

import bltconnector.core.receiver.DeckSnapshot
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCSerializerAndParserBuilder
import com.illposed.osc.transport.OSCPortOut
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * VJ機1台分の配信先(ユニキャスト宛先)。
 * name はGUI上の表示・手動登録/自動検出どちらでも共通に使う識別用ラベル。
 */
data class Destination(val name: String, val address: InetAddress, val port: Int)

/**
 * デッキ状態をOSCメッセージとして各Destinationへユニキャスト配信する。
 *
 * 帯域配慮のため3段階に分ける(docs/architecture.md参照):
 *  - 毎tick: 再生位置・再生中フラグ・実効BPM・Masterフラグ(軽量なので常時送信)
 *  - 1秒に1回チェックし、変化があった時だけ: タイトル・アーティスト・アルバム・曲長・原曲BPM
 *  - 曲が変わった時(trackKey変化)のみ: 波形プレビュー(帯域が大きいため、こちらは従来通り
 *    曲が変わった瞬間だけ送る。1秒毎の再評価はしない)
 *
 * タイトル等を「曲が変わった瞬間だけ」送る設計だった頃は、その瞬間にdbserverへのメタデータ
 * 問い合わせがまだ完了していないと(非同期なので起こり得る)、曲名が空のまま二度と更新されない
 * 不具合があった。1秒間隔で値を再評価し、前回送った値と異なる場合だけ送り直す方式に変更することで、
 * 値が確定した時点で自然に追いつくようにしている(無関係な変化が無い限り送信しないので帯域も抑えられる)。
 *
 * [localBindAddress] を指定すると、送信元NICを固定できる(Sender単体をLinux CLIとして動かし、
 * 受信用/送信用インターフェースを分けたい場合に使う。未指定時はOSのルーティングに任せる)。
 */
class Sender(private val localBindAddress: InetAddress? = null) {
    private val logger = LoggerFactory.getLogger(Sender::class.java)
    private val ports = mutableMapOf<Destination, OSCPortOut>()
    private val lastTrackKeySent = mutableMapOf<Pair<Destination, Int>, Any?>()
    private val lastTrackInfoSent = mutableMapOf<Pair<Destination, Int>, TrackInfo>()
    private val lastTrackInfoCheckNanos = mutableMapOf<Pair<Destination, Int>, Long>()

    /** OSCで送るテキスト系メタデータの比較用スナップショット(波形は含まない、別経路で扱う)。 */
    private data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val originalBpm: Float,
    )

    private fun trackInfoOf(deck: DeckSnapshot) = TrackInfo(
        title = deck.title ?: "",
        artist = deck.artist ?: "",
        album = deck.album ?: "",
        durationMs = deck.durationMs ?: -1L,
        originalBpm = (deck.originalBpm ?: -1.0).toFloat(),
    )

    fun addDestination(destination: Destination) {
        if (ports.containsKey(destination)) return
        val remote = InetSocketAddress(destination.address, destination.port)
        ports[destination] = if (localBindAddress != null) {
            OSCPortOut(OSCSerializerAndParserBuilder(), remote, InetSocketAddress(localBindAddress, 0))
        } else {
            OSCPortOut(destination.address, destination.port)
        }
    }

    fun removeDestination(destination: Destination) {
        ports.remove(destination)?.close()
        lastTrackKeySent.keys.removeAll { it.first == destination }
        lastTrackInfoSent.keys.removeAll { it.first == destination }
        lastTrackInfoCheckNanos.keys.removeAll { it.first == destination }
    }

    fun destinations(): Set<Destination> = ports.keys.toSet()

    /** デッキ1台分の最新状態を全宛先に配信する。曲が変わっていれば波形を、メタデータに変化があれば曲情報を併せて送る。 */
    fun update(deck: DeckSnapshot) {
        val base = "/blt/deck/${deck.playerNumber}"
        val tickMessages = listOf(
            OSCMessage("$base/position_ms", listOf(deck.positionMs ?: -1L)),
            OSCMessage("$base/playing", listOf(deck.playing)),
            OSCMessage("$base/bpm", listOf(deck.bpm.toFloat())),
            OSCMessage("$base/master", listOf(deck.master)),
        )

        ports.forEach { (destination, port) ->
            val key = destination to deck.playerNumber
            val trackChanged = lastTrackKeySent[key] != deck.trackKey
            val messages = mutableListOf<OSCMessage>().apply { addAll(tickMessages) }

            if (trackChanged) {
                lastTrackKeySent[key] = deck.trackKey
                messages.addAll(waveformMessages(base, deck))
                // 曲が変わったので、テキスト系メタデータも次のチェックで必ず再評価させる
                // (前の曲の値と偶然同じでも、少なくとも1回は送るようにするため)。
                lastTrackInfoSent.remove(key)
                lastTrackInfoCheckNanos.remove(key)
            }

            val now = System.nanoTime()
            val lastCheck = lastTrackInfoCheckNanos[key]
            val shouldCheckTrackInfo = lastCheck == null || (now - lastCheck) >= TRACK_INFO_CHECK_INTERVAL_NANOS
            if (shouldCheckTrackInfo) {
                lastTrackInfoCheckNanos[key] = now
                val info = trackInfoOf(deck)
                if (lastTrackInfoSent[key] != info) {
                    lastTrackInfoSent[key] = info
                    messages.addAll(trackInfoMessages(base, info))
                }
            }

            try {
                messages.forEach { port.send(it) }
            } catch (e: Exception) {
                logger.warn("failed to send OSC to {}", destination, e)
            }
        }
    }

    private fun trackInfoMessages(base: String, info: TrackInfo): List<OSCMessage> = listOf(
        OSCMessage("$base/title", listOf(info.title)),
        OSCMessage("$base/artist", listOf(info.artist)),
        OSCMessage("$base/album", listOf(info.album)),
        OSCMessage("$base/duration_ms", listOf(info.durationMs)),
        OSCMessage("$base/original_bpm", listOf(info.originalBpm)),
    )

    private fun waveformMessages(base: String, deck: DeckSnapshot): List<OSCMessage> {
        val messages = mutableListOf(OSCMessage("$base/waveform_color", listOf(deck.waveformColor)))
        deck.waveform?.let { messages.add(OSCMessage("$base/waveform", listOf(it))) }
        return messages
    }

    fun close() {
        ports.values.forEach { it.close() }
        ports.clear()
        lastTrackKeySent.clear()
        lastTrackInfoSent.clear()
        lastTrackInfoCheckNanos.clear()
    }

    companion object {
        private val TRACK_INFO_CHECK_INTERVAL_NANOS = java.time.Duration.ofSeconds(1).toNanos()
    }
}
