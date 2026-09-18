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
 * 帯域配慮のため2段階に分ける(docs/architecture.md参照):
 *  - 毎tick: 再生位置・再生中フラグ・実効BPM・Masterフラグ(軽量なので常時送信)
 *  - 曲が変わった時のみ: タイトル・アーティスト・アルバム・原曲BPM・波形プレビュー
 * 宛先ごとに最後に送った trackKey を記憶し、差分があれば(新規追加した宛先も含めて)曲情報を送り直す。
 *
 * [localBindAddress] を指定すると、送信元NICを固定できる(Sender単体をLinux CLIとして動かし、
 * 受信用/送信用インターフェースを分けたい場合に使う。未指定時はOSのルーティングに任せる)。
 */
class Sender(private val localBindAddress: InetAddress? = null) {
    private val logger = LoggerFactory.getLogger(Sender::class.java)
    private val ports = mutableMapOf<Destination, OSCPortOut>()
    private val lastTrackKeySent = mutableMapOf<Pair<Destination, Int>, Any?>()

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
    }

    fun destinations(): Set<Destination> = ports.keys.toSet()

    /** デッキ1台分の最新状態を全宛先に配信する。曲が変わっていれば曲情報も併せて送る。 */
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
            val messages = if (trackChanged) tickMessages + trackInfoMessages(base, deck) else tickMessages
            try {
                messages.forEach { port.send(it) }
                if (trackChanged) lastTrackKeySent[key] = deck.trackKey
            } catch (e: Exception) {
                logger.warn("failed to send OSC to {}", destination, e)
            }
        }
    }

    private fun trackInfoMessages(base: String, deck: DeckSnapshot): List<OSCMessage> {
        val messages = mutableListOf(
            OSCMessage("$base/title", listOf(deck.title ?: "")),
            OSCMessage("$base/artist", listOf(deck.artist ?: "")),
            OSCMessage("$base/album", listOf(deck.album ?: "")),
            OSCMessage("$base/duration_ms", listOf(deck.durationMs ?: -1L)),
            OSCMessage("$base/original_bpm", listOf((deck.originalBpm ?: -1.0).toFloat())),
            OSCMessage("$base/waveform_color", listOf(deck.waveformColor)),
        )
        deck.waveform?.let { messages.add(OSCMessage("$base/waveform", listOf(it))) }
        return messages
    }

    fun close() {
        ports.values.forEach { it.close() }
        ports.clear()
        lastTrackKeySent.clear()
    }
}
