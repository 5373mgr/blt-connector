package bltconnector.core.sender

import bltconnector.core.receiver.DeckSnapshot
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCSerializerAndParserBuilder
import com.illposed.osc.transport.OSCPortOut
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 外部ソフト「StreamMix Display」向けのOSCスキーマ(`/deck/{n}/...`)でデッキ状態を配信する。
 * 実機Beat Link Trigger(`BTL_OSC.blt`)の送信仕様を再現したもの。詳細仕様は
 * docs/BTL_OSC_Documentation.md 参照。
 *
 * 既存の[Sender](`/blt/deck/{n}/...`、独自スキーマ・毎tick配信)とは別の、StreamMix Display
 * 互換のためだけの配信経路。両方を同時に有効にして併用できる。
 *
 * 実機BLTの"Tracked"式を再現する: playing/beat-number/beat-in-bar/tempo/pitch-mul＋
 * メタデータ18種、計24キーの状態を宛先ごとに記憶し、前回送信時から1つでも値が変わっていれば
 * 23〜24メッセージをまとめて再送する(変化が無ければ何も送らない)。`playing`が変化した瞬間は
 * 単独でも即座に送る(実機の"Activation"/"Deactivation"に相当)。
 *
 * `onAir`は意図的に送信しない: 同梱の実機設定`BTL_OSC.blt`もこのアドレスを送らない
 * (ドキュメントの注記「実機の挙動を再現したいときはonAirを送らないでください」に従う)。
 */
class StreamMixSender(private val localBindAddress: InetAddress? = null) {
    private val logger = LoggerFactory.getLogger(StreamMixSender::class.java)
    private val ports = mutableMapOf<Destination, OSCPortOut>()
    private val lastState = mutableMapOf<Pair<Destination, Int>, State>()

    @Synchronized
    fun addDestination(destination: Destination) {
        if (ports.containsKey(destination)) return
        val remote = InetSocketAddress(destination.address, destination.port)
        ports[destination] = if (localBindAddress != null) {
            OSCPortOut(OSCSerializerAndParserBuilder(), remote, InetSocketAddress(localBindAddress, 0))
        } else {
            OSCPortOut(destination.address, destination.port)
        }
    }

    @Synchronized
    fun removeDestination(destination: Destination) {
        ports.remove(destination)?.close()
        lastState.keys.removeAll { it.first == destination }
    }

    fun destinations(): Set<Destination> = ports.keys.toSet()

    /** 実機BLTのTracked式が比較する24キーの状態(playing含む)。 */
    private data class State(
        val playing: Boolean,
        val beatNumber: Int,
        val beatWithinBar: Int,
        val bpm: Float,
        val pitchMultiplier: Float,
        val title: String,
        val artist: String,
        val album: String,
        val genre: String,
        val key: String,
        val label: String,
        val originalArtist: String,
        val remixer: String,
        val colorName: String,
        val comment: String,
        val dateAdded: String,
        val durationSec: Float,
        val bitRate: Int,
        val rating: Int,
        val tempoMeta: Int,
        val year: Int,
        val artworkId: Int,
        val hasCues: Boolean,
    )

    private fun stateOf(deck: DeckSnapshot) = State(
        playing = deck.playing,
        beatNumber = deck.beatNumber,
        beatWithinBar = deck.beatWithinBar,
        bpm = deck.bpm.toFloat(),
        pitchMultiplier = deck.pitchMultiplier.toFloat(),
        title = deck.title ?: "",
        artist = deck.artist ?: "",
        album = deck.album ?: "",
        genre = deck.genre ?: "",
        key = deck.key ?: "",
        label = deck.label ?: "",
        originalArtist = deck.originalArtist ?: "",
        remixer = deck.remixer ?: "",
        colorName = deck.colorName ?: "",
        comment = deck.comment ?: "",
        dateAdded = deck.dateAdded ?: "",
        durationSec = (deck.durationMs ?: 0L) / 1000.0f,
        bitRate = deck.bitRate ?: 0,
        rating = deck.rating ?: 0,
        tempoMeta = deck.tempoMetaRaw ?: 0,
        year = deck.year ?: 0,
        artworkId = deck.artworkId ?: 0,
        hasCues = deck.cues.isNotEmpty(),
    )

    /**
     * 実機BLTの"Beat"式に相当: ビートの瞬間に`bpm`/`beat`/`beatWithinBar`を即座に送る。
     * [update]は100ms間隔のポーリングから呼ばれるため、これが無いとビートが最大150ms程度遅れていた。
     * 直後の[update]でもTracked一括送信にビート系が重複して乗るが、これも実機と同じ挙動。
     */
    @Synchronized
    fun sendBeat(playerNumber: Int, bpm: Double, beatNumber: Int, beatWithinBar: Int) {
        val base = "/deck/$playerNumber"
        ports.forEach { (destination, port) ->
            try {
                port.send(OSCMessage("$base/bpm", listOf(bpm.toFloat())))
                port.send(OSCMessage("$base/beat", listOf(beatNumber)))
                port.send(OSCMessage("$base/beatWithinBar", listOf(beatWithinBar)))
            } catch (e: Exception) {
                logger.warn("failed to send StreamMix OSC beat to {}", destination, e)
            }
        }
    }

    /** デッキ1台分の最新状態を全宛先に配信する。前回送信時から状態が変わった宛先にのみ送る。 */
    // OSCPortOutは送信バッファを共有しておりスレッドセーフではないため、sendBeat(ビート受信スレッド)と排他にする
    @Synchronized
    fun update(deck: DeckSnapshot) {
        val base = "/deck/${deck.playerNumber}"
        val newState = stateOf(deck)

        ports.forEach { (destination, port) ->
            val stateKey = destination to deck.playerNumber
            val previous = lastState[stateKey]
            try {
                // Activation/Deactivation相当: playingだけでも変化した瞬間は単独で即座に送る
                // (実機は専用式で即時送信し、直後のTracked一括送信にもplayingが重複して乗る)。
                if (previous != null && previous.playing != newState.playing) {
                    port.send(OSCMessage("$base/playing", listOf(if (newState.playing) 1 else 0)))
                }
                if (previous != newState) {
                    sendBatch(port, base, deck, newState)
                    lastState[stateKey] = newState
                }
            } catch (e: Exception) {
                logger.warn("failed to send StreamMix OSC to {}", destination, e)
            }
        }
    }

    private fun sendBatch(port: OSCPortOut, base: String, deck: DeckSnapshot, s: State) {
        val messages = mutableListOf(
            OSCMessage("$base/playing", listOf(if (s.playing) 1 else 0)),
            OSCMessage("$base/bpm", listOf(s.bpm)),
            OSCMessage("$base/beat", listOf(s.beatNumber)),
            OSCMessage("$base/beatWithinBar", listOf(s.beatWithinBar)),
            OSCMessage("$base/pitchMultiplier", listOf(s.pitchMultiplier)),
            OSCMessage("$base/title", listOf(s.title)),
            OSCMessage("$base/artist", listOf(s.artist)),
            OSCMessage("$base/album", listOf(s.album)),
            OSCMessage("$base/genre", listOf(s.genre)),
            OSCMessage("$base/key", listOf(s.key)),
            OSCMessage("$base/label", listOf(s.label)),
            OSCMessage("$base/originalArtist", listOf(s.originalArtist)),
            OSCMessage("$base/remixer", listOf(s.remixer)),
            OSCMessage("$base/color", listOf(s.colorName)),
            OSCMessage("$base/comment", listOf(s.comment)),
            OSCMessage("$base/dateAdded", listOf(s.dateAdded)),
            OSCMessage("$base/duration", listOf(s.durationSec)),
            OSCMessage("$base/bitRate", listOf(s.bitRate)),
            OSCMessage("$base/rating", listOf(s.rating)),
            OSCMessage("$base/tempoMeta", listOf(s.tempoMeta)),
            OSCMessage("$base/year", listOf(s.year)),
            OSCMessage("$base/artworkId", listOf(s.artworkId)),
            OSCMessage("$base/hasCues", listOf(if (s.hasCues) 1 else 0)),
        )
        // track-metadataがnilの時は送らない(実機の `(when tm ...)` ガードを再現、23通になる)
        deck.metadataRawDump?.let { messages.add(OSCMessage("$base/metadata/raw", listOf(it))) }
        messages.forEach { port.send(it) }
    }

    @Synchronized
    fun close() {
        ports.values.forEach { it.close() }
        ports.clear()
        lastState.clear()
    }
}
