package bltconnector.core.carabiner

import org.deepsymmetry.libcarabiner.Message
import org.deepsymmetry.libcarabiner.Runner
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * lib-carabinerが同梱する汎用Linux ARM版バイナリ(`Carabiner_Linux_arm`)は、実機の
 * Raspberry Pi(32bit ARM, armv7l)ではセグメンテーション違反で即座に終了することを実機で確認した。
 * Deep Symmetryは同じCarabinerのリリースに、Raspberry Pi専用ビルド`Carabiner_Rpi`も
 * 別途公開しており、そちらは実機で問題なく動作する
 * (https://github.com/Deep-Symmetry/carabiner/releases)。
 *
 * lib-carabinerの`Runner`はOS/アーキテクチャの文字列から使うバイナリ名を機械的に決めるだけで、
 * 差し替える手段が公開APIに無い。そのため、この組み合わせ(Linux + 32bit ARM)の時だけ
 * `Runner`を使わず、本プロジェクトに同梱した`Carabiner_Rpi`(`core/src/main/resources/native/`)を
 * 自前で抽出・起動する。それ以外のプラットフォーム(Win/Mac/Linux x64)は`Runner`をそのまま使う。
 *
 * さらに実機検証で、この`Carabiner_Rpi`(本家の公式ビルド、および同一ソースをarmhf上で
 * ネイティブ再ビルドしたものの両方)は、bpmセット命令を継続的に送り続けると起動から概ね20〜30秒
 * 程度でセグメンテーション違反を起こして終了することを確認した。ソースを直接armhf上で
 * ビルドし直しても同じ箇所で再現したため、クロスコンパイル起因の問題ではなく、Ableton Link本体
 * (またはCarabinerのラッパー部分)がこのプラットフォームで抱えている実際のバグと判断した。
 * Carabiner/Linkは事実上メンテナンスが止まっているため、根本修正を待つのではなく、プロセスが
 * 落ちたら即座に再起動して復旧するsupervisor方式で運用する(下記`supervisorLoop`)。
 * 瞬間的な再同期は発生するが、手動介入なしで動作を継続できる。
 */
private class RpiCarabinerProcess(private val port: Int) {
    private val logger = LoggerFactory.getLogger(RpiCarabinerProcess::class.java)

    @Volatile private var process: Process? = null
    @Volatile private var shouldRun = false
    private var binaryFile: File? = null

    fun start() {
        shouldRun = true
        binaryFile = extractBinary()
        Thread({ supervisorLoop() }, "Carabiner(Rpi) Supervisor").apply { isDaemon = true }.start()
    }

    private fun supervisorLoop() {
        var restartCount = 0
        while (shouldRun) {
            val binary = binaryFile ?: return
            val command = listOf(binary.absolutePath, "--daemon", "--port", port.toString(), "--poll", "20")
            val started = try {
                ProcessBuilder(command).start()
            } catch (e: IOException) {
                logger.error("Carabiner(Rpi)プロセスの起動に失敗しました", e)
                Thread.sleep(1000)
                continue
            }
            process = started
            if (restartCount > 0) {
                logger.warn("Carabiner(Rpi)プロセスを再起動しました(累計{}回目)", restartCount)
            }

            Thread({
                started.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { logger.info(it.trim()) }
                }
            }, "Carabiner(Rpi) Output Logger").apply { isDaemon = true }.start()

            Thread({
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { logger.error(it.trim()) }
                }
            }, "Carabiner(Rpi) Error Logger").apply { isDaemon = true }.start()

            val exitCode = started.waitFor()
            process = null
            if (!shouldRun) return
            restartCount++
            logger.warn("Carabiner(Rpi)プロセスが終了しました(exit={})。再起動します", exitCode)
            Thread.sleep(500)
        }
    }

    fun stop() {
        shouldRun = false
        process?.destroy()
        process = null
    }

    private fun extractBinary(): File {
        val resource = RpiCarabinerProcess::class.java.getResourceAsStream("/native/Carabiner_Rpi")
            ?: error("native/Carabiner_Rpi resource not found on classpath")
        val file = File.createTempFile("Carabiner_Rpi", ".exe")
        file.deleteOnExit()
        resource.use { input -> Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        if (!file.setExecutable(true)) error("Unable to make $file executable")
        return file
    }

    companion object {
        /** Linuxの32bit ARM(armhf/arm/aarch32/armv7l)かどうか。lib-carabiner本家の判定条件と同じ。 */
        fun appliesToCurrentPlatform(): Boolean {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            return os.contains("linux") && arch in setOf("arm", "armhf", "aarch32", "armv7l")
        }
    }
}

/**
 * Pro DJ LinkのMaster BPMをCarabiner経由でAbleton Linkセッションへブリッジする。
 *
 * Carabiner本体(C++バイナリ)は基本的にDeep Symmetryの`lib-carabiner`がWin/Mac/Linux向けを
 * 同梱しており、現在のOS/アーキテクチャに合ったものを自動抽出・起動してくれる
 * ([RpiCarabinerProcess]参照のRaspberry Pi向けの例外あり)。
 * プロトコルは127.0.0.1宛のTCPで、改行区切りのテキストコマンド/応答
 * (詳細は https://github.com/Deep-Symmetry/carabiner#protocol )。
 * Carabinerは仕様上ループバック以外からの接続を受け付けない(Linkのタイムスタンプがホストのクロックに
 * 依存するため、Carabinerと本アプリは同一マシン上で動く必要がある)。
 */
class CarabinerBridge(private val port: Int = 17000) {
    private val logger = LoggerFactory.getLogger(CarabinerBridge::class.java)
    private val runner = Runner.getInstance()
    private val rpiProcess: RpiCarabinerProcess? =
        if (RpiCarabinerProcess.appliesToCurrentPlatform()) RpiCarabinerProcess(port) else null

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var lastSentBpm: Double? = null
    @Volatile private var running = false

    /** Carabinerから最後に受け取った status メッセージの内容(peers/bpm/beat等)。UIでの参考表示用。 */
    @Volatile var lastStatus: Map<String, Any?>? = null
        private set

    /** このプラットフォーム向けのCarabinerバイナリが使えるか。 */
    fun isSupported(): Boolean = rpiProcess != null || runner.canRunCarabiner()

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
        if (rpiProcess != null) {
            rpiProcess.start()
        } else {
            runner.setPort(port)
            runner.start()
        }

        Thread({ connectorLoop() }, "Carabiner Connector").apply { isDaemon = true }.start()
    }

    /**
     * 接続 → 読み取り(切断されるまでブロック) → 少し待って再接続、を`running`の間繰り返す。
     * [RpiCarabinerProcess]がプロセスをsupervisorで再起動する運用と組み合わせるため、
     * 起動直後の接続失敗だけでなく、稼働中の切断(プロセスクラッシュ等)からも自動復旧する。
     */
    private fun connectorLoop() {
        while (running) {
            try {
                connectAndListen()
            } catch (e: IOException) {
                logger.debug("Carabinerへの接続に失敗しました。再試行します(port={})", port)
            }
            writer = null
            socket = null
            if (running) Thread.sleep(250)
        }
    }

    /** 接続し、切断されるまで応答を読み続ける(呼び出しスレッドをブロックする)。 */
    private fun connectAndListen() {
        val s = Socket("127.0.0.1", port)
        socket = s
        writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
        val reader = s.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        lastSentBpm = null

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                handleLine(line!!)
            }
        } catch (e: IOException) {
            if (running) logger.debug("Carabiner接続が切断されました", e)
        }
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
        if (rpiProcess != null) rpiProcess.stop() else runner.stop()
    }
}
