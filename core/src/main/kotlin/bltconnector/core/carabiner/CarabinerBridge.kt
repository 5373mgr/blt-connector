package bltconnector.core.carabiner

import org.deepsymmetry.libcarabiner.Message
import org.deepsymmetry.libcarabiner.Runner
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Pro DJ LinkのMaster BPMをCarabiner経由でAbleton Linkセッションへブリッジする。
 *
 * Carabiner本体(C++バイナリ)はDeep Symmetryの`lib-carabiner`がWin/Mac/Linux向けを同梱しており、
 * 現在のOS/アーキテクチャに合ったものを自動抽出・起動してくれる。プロトコルは127.0.0.1宛のTCPで、
 * 改行区切りのテキストコマンド/応答(詳細は https://github.com/Deep-Symmetry/carabiner#protocol )。
 * Carabinerは仕様上ループバック以外からの接続を受け付けない(Linkのタイムスタンプがホストのクロックに
 * 依存するため、Carabinerと本アプリは同一マシン上で動く必要がある)。
 */
class CarabinerBridge(private val port: Int = 17000) {
    private val logger = LoggerFactory.getLogger(CarabinerBridge::class.java)
    private val runner = Runner.getInstance()

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var lastSentBpm: Double? = null
    @Volatile private var running = false

    /** Carabinerから最後に受け取った status メッセージの内容(peers/bpm/beat等)。UIでの参考表示用。 */
    @Volatile var lastStatus: Map<String, Any?>? = null
        private set

    /** このプラットフォーム向けのCarabinerバイナリが同梱されているか。 */
    fun isSupported(): Boolean = runner.canRunCarabiner()

    fun start() {
        if (running) return
        if (!isSupported()) {
            logger.warn(
                "このプラットフォーム({} {})向けのCarabinerバイナリが見つからないため、Ableton Linkブリッジは無効です",
                System.getProperty("os.name"), System.getProperty("os.arch"),
            )
            return
        }
        running = true
        runner.setPort(port)
        runner.start()

        Thread({
            var connected = false
            var attempt = 0
            while (running && !connected && attempt < 40) {
                attempt++
                try {
                    connectAndListen()
                    connected = true
                } catch (e: IOException) {
                    Thread.sleep(250)
                }
            }
            if (!connected && running) {
                logger.error("Carabinerへの接続に失敗しました(port={})", port)
            }
        }, "Carabiner Connector").apply { isDaemon = true }.start()
    }

    private fun connectAndListen() {
        val s = Socket("127.0.0.1", port)
        socket = s
        writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))

        Thread({
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    handleLine(line!!)
                }
            } catch (e: IOException) {
                if (running) logger.debug("Carabiner接続が終了しました", e)
            }
        }, "Carabiner Reader").apply { isDaemon = true }.start()
    }

    private fun handleLine(line: String) {
        if (line.isBlank()) return
        try {
            val message = Message(line)
            if (message.messageType == "status") {
                @Suppress("UNCHECKED_CAST")
                lastStatus = message.details as? Map<String, Any?>
            }
        } catch (e: Exception) {
            logger.warn("Carabinerからの応答の解析に失敗しました: {}", line, e)
        }
    }

    /**
     * Masterデッキの実効BPMをCarabiner(Link)へ反映する。ほぼ同じ値の連続送信は間引く。
     * まだCarabinerに接続できていない場合は無視する(接続完了後の次回呼び出しで反映される)。
     */
    @Synchronized
    fun updateMasterTempo(bpm: Double) {
        val w = writer ?: return
        val last = lastSentBpm
        if (last != null && Math.abs(last - bpm) < 0.05) return
        try {
            w.write("bpm ${"%.4f".format(bpm)}\n")
            w.flush()
            lastSentBpm = bpm
        } catch (e: IOException) {
            logger.warn("Carabinerへのbpm送信に失敗しました", e)
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            logger.debug("Carabinerソケットのcloseに失敗", e)
        }
        socket = null
        writer = null
        runner.stop()
    }
}
