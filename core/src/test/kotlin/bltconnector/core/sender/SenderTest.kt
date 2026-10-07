package bltconnector.core.sender

import bltconnector.core.receiver.DeckSnapshot
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

private fun deck(trackKey: Any?, positionMs: Long, title: String = "Test Title") = DeckSnapshot(
    playerNumber = 1,
    positionMs = positionMs,
    playing = true,
    bpm = 128.0,
    master = true,
    trackKey = trackKey,
    title = title,
    artist = "Test Artist",
    album = "Test Album",
    durationMs = 200_000,
    originalBpm = 128.0,
    waveform = null,
    waveformColor = false,
    artwork = null,
)

/** 受信側を用意せずポート番号だけ確保する(実際には送らないので既に閉じたソケットのポートを使い回す)。 */
private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

class SenderTest {
    @Test
    fun `sends track info only when the track changes`() {
        val port = freeUdpPort()
        val received = mutableListOf<DatagramPacket>()
        val listener = DatagramSocket(port)
        val thread = Thread {
            try {
                while (true) {
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    listener.receive(packet)
                    synchronized(received) { received.add(packet) }
                }
            } catch (_ : Exception) {
                // ソケットclose時に例外で抜ける
            }
        }
        thread.isDaemon = true
        thread.start()

        val sender = Sender()
        sender.addDestination(Destination("test", InetAddress.getLoopbackAddress(), port))

        val trackA = Any()
        sender.update(deck(trackA, 1000))
        Thread.sleep(200)
        val countAfterFirst = synchronized(received) { received.size }

        sender.update(deck(trackA, 1100))
        Thread.sleep(200)
        val countAfterSecond = synchronized(received) { received.size }

        sender.update(deck(Any(), 1200))
        Thread.sleep(200)
        val countAfterTrackChange = synchronized(received) { received.size }

        sender.close()
        listener.close()

        // 1回目: tick(4) + waveform_color(1) + 曲情報(5, waveformバイト列なし)
        assertEquals(10, countAfterFirst)
        // 2回目: 同じ曲・同じメタデータで1秒未満なのでtickのみ(4)
        assertEquals(10 + 4, countAfterSecond)
        // 3回目: 曲が変わったので再度 tick(4) + waveform_color(1) + 曲情報(5)
        assertEquals(10 + 4 + 10, countAfterTrackChange)
    }

    @Test
    fun `resends track info after 1 second only if it actually changed`() {
        val port = freeUdpPort()
        val received = mutableListOf<DatagramPacket>()
        val listener = DatagramSocket(port)
        val thread = Thread {
            try {
                while (true) {
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    listener.receive(packet)
                    synchronized(received) { received.add(packet) }
                }
            } catch (_: Exception) {
                // ソケットclose時に例外で抜ける
            }
        }
        thread.isDaemon = true
        thread.start()

        val sender = Sender()
        sender.addDestination(Destination("test", InetAddress.getLoopbackAddress(), port))

        val track = Any()
        // 曲の切り替え検知の瞬間はまだdbserverのメタデータ問い合わせが終わっておらずtitleが空、という状況を模す。
        sender.update(deck(track, 1000, title = ""))
        Thread.sleep(200)
        val countAfterLoad = synchronized(received) { received.size }

        // 1秒未満での再評価では(メタデータが同じ空文字のままなので)送られない。
        sender.update(deck(track, 1100, title = ""))
        Thread.sleep(200)
        val countBeforeInterval = synchronized(received) { received.size }

        // 1秒経過後、メタデータが(dbserverから遅れて届いて)変化していれば送り直される。
        Thread.sleep(900)
        sender.update(deck(track, 1200, title = "Real Title"))
        Thread.sleep(200)
        val countAfterMetadataArrived = synchronized(received) { received.size }

        sender.close()
        listener.close()

        // 1秒未満での再評価ではtick(4)のみ増える(曲情報は送られない)。
        assertEquals(countAfterLoad + 4, countBeforeInterval)
        // 1秒経過後、メタデータが変化していればtick(4)に加えて曲情報(5件)も送られる。
        assertEquals(countBeforeInterval + 4 + 5, countAfterMetadataArrived)
    }
}
