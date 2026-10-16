package bltconnector.ndi

import javafx.animation.AnimationTimer
import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.PixelFormat
import javafx.scene.input.KeyCode
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.text.FontSmoothingType
import javafx.scene.transform.Scale
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.util.Duration
import me.walkerknapp.devolay.Devolay
import me.walkerknapp.devolay.DevolayFrameFourCCType
import me.walkerknapp.devolay.DevolaySender
import me.walkerknapp.devolay.DevolayVideoFrame
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 起動オプション。`--key=value`形式で指定する。
 *
 * - `--url`: 描画するページ(既定: PiのOBS向けオーバーレイ`/`。VJ用モニター画面なら`/monitor`)
 * - `--name`: NDIソース名(受信側にはこの名前で見える)
 * - `--width`/`--height`: 送出解像度
 * - `--fps`: 送出フレームレート
 * - `--transparent`: ページ背景を透過してアルファ付きで送出する(既定: true。オーバーレイを
 *   VJソフト側で映像に重ねるため。`/monitor`のように不透明な画面ならfalseでよい)
 * - `--zoom`: ページの拡大率(1.0=等倍)。モニター画面は横幅に応じてカードを並べるため、
 *   拡大すると1枚あたりのカードが大きくなり画面全体を使うようになる
 * - `--preview-scale`: プレビューウィンドウの表示倍率(送出解像度には影響しない)
 * - `--snapshot`: 起動から数秒後の1フレームをPNGに保存する(動作確認用)
 * - `--exit-after`: 指定秒数後に終了する(動作確認用)
 */
data class Options(
    val url: String = "http://192.168.50.1:8090/",
    val name: String = "BLT Overlay",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val transparent: Boolean = true,
    val zoom: Double = 1.0,
    val previewScale: Double = 0.5,
    val snapshot: String? = null,
    val exitAfterSeconds: Int? = null,
) {
    companion object {
        fun parse(args: List<String>): Options {
            val map = args.filter { it.startsWith("--") }.associate {
                val body = it.removePrefix("--")
                body.substringBefore("=") to (if ("=" in body) body.substringAfter("=") else "true")
            }
            val d = Options()
            return Options(
                url = map["url"] ?: d.url,
                name = map["name"] ?: d.name,
                width = map["width"]?.toIntOrNull() ?: d.width,
                height = map["height"]?.toIntOrNull() ?: d.height,
                fps = map["fps"]?.toIntOrNull()?.coerceIn(1, 60) ?: d.fps,
                transparent = map["transparent"]?.toBoolean() ?: d.transparent,
                zoom = map["zoom"]?.toDoubleOrNull() ?: d.zoom,
                previewScale = map["preview-scale"]?.toDoubleOrNull() ?: d.previewScale,
                snapshot = map["snapshot"],
                exitAfterSeconds = map["exit-after"]?.toIntOrNull(),
            )
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(NdiApp::class.java, *args)
}

private const val LAYOUT_POLL_INTERVAL_MS = 3000L

class NdiApp : Application() {
    private lateinit var options: Options
    private lateinit var sender: DevolaySender
    private val senderThread = Thread(::sendLoop, "NDI Sender").apply { isDaemon = true }

    /** 送出待ちのフレーム。送出が詰まっている間は古いフレームを捨てて最新だけを送る。 */
    private val pending = ArrayBlockingQueue<ByteBuffer>(1)

    /** 再利用するフレームバッファ(描画用と送出用で取り合わないよう複数持つ)。 */
    private val free = ArrayBlockingQueue<ByteBuffer>(3)

    @Volatile
    private var running = true

    override fun start(stage: Stage) {
        options = Options.parse(parameters.raw)
        Devolay.loadLibraries()
        sender = DevolaySender(options.name)
        repeat(3) { free.add(ByteBuffer.allocateDirect(options.width * options.height * 4)) }
        senderThread.start()

        val webView = WebView().apply {
            setMinSize(options.width.toDouble(), options.height.toDouble())
            setPrefSize(options.width.toDouble(), options.height.toDouble())
            setMaxSize(options.width.toDouble(), options.height.toDouble())
            if (options.transparent) pageFill = Color.TRANSPARENT
            zoom = options.zoom
            // 既定のLCD(サブピクセル)アンチエイリアスは物理モニタ向けで、映像として送出すると
            // 文字の縁に色にじみが出る(実機確認済み)ため、グレースケールにする
            fontSmoothingType = FontSmoothingType.GRAY
        }
        val engine = webView.engine
        // Piの再起動中・電源投入前などで読み込みに失敗したら、数秒おきに再試行する
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            if (state == Worker.State.FAILED) {
                println("ページの読み込みに失敗しました。5秒後に再試行します: ${options.url}")
                PauseTransition(Duration.seconds(5.0)).apply { setOnFinished { engine.load(options.url) } }.play()
            }
        }
        engine.load(options.url)
        startLayoutWatcher { engine.reload() }

        // 送出解像度のままではプレビューウィンドウが画面に収まらないため、表示だけ縮小する。
        // スナップショットはWebView自身(親のScaleの影響を受けない)から取るので送出解像度は変わらない。
        val previewRoot = Group(Group(webView).apply {
            transforms.add(Scale(options.previewScale, options.previewScale, 0.0, 0.0))
        })
        stage.scene = Scene(previewRoot, options.width * options.previewScale, options.height * options.previewScale,
            if (options.transparent) Color.web("#202020") else Color.BLACK)
        stage.title = "NDI: ${options.name} (${options.width}x${options.height} ${options.fps}fps) - ${options.url}"
        stage.setOnCloseRequest { stopAll() }
        // F5で手動リロード(レイアウト以外、例えばフォントやPi側HTMLを差し替えた時用)
        stage.scene.setOnKeyPressed { if (it.code == KeyCode.F5) engine.reload() }
        stage.show()

        val image = WritableImage(options.width, options.height)
        val params = SnapshotParameters().apply { fill = if (options.transparent) Color.TRANSPARENT else Color.BLACK }
        val format = PixelFormat.getByteBgraInstance()
        val frameIntervalNanos = 1_000_000_000L / options.fps
        var nextFrameAt = 0L
        val startedAt = System.nanoTime()
        var snapshotSaved = false

        object : AnimationTimer() {
            override fun handle(now: Long) {
                if (now < nextFrameAt) return
                nextFrameAt = if (now - nextFrameAt > frameIntervalNanos) now + frameIntervalNanos else nextFrameAt + frameIntervalNanos
                webView.snapshot(params, image)
                val buffer = free.poll() ?: pending.poll() ?: return
                buffer.clear()
                image.pixelReader.getPixels(0, 0, options.width, options.height, format, buffer, options.width * 4)
                buffer.rewind()
                if (!pending.offer(buffer)) free.offer(buffer)

                val elapsed = (now - startedAt) / 1_000_000_000.0
                if (!snapshotSaved && options.snapshot != null && elapsed >= 5.0) {
                    snapshotSaved = true
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", File(options.snapshot))
                    println("スナップショットを保存しました: ${options.snapshot}")
                }
                options.exitAfterSeconds?.let { if (elapsed >= it) stopAll() }
            }
        }.start()

        println("NDI送出を開始しました: \"${options.name}\" ${options.width}x${options.height} ${options.fps}fps <- ${options.url}")
    }

    /**
     * Overlayのページはレイアウト設定(`/layout`)を読み込み時に一度だけ取得するため、エディタで
     * レイアウトを変えても送出中の映像には反映されない。数秒おきに`/layout`を取得し、内容が
     * 変わっていたらページを再読み込みする。
     */
    private fun startLayoutWatcher(reload: () -> Unit) {
        val layoutUri = URI.create(options.url).resolve("/layout")
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
        Thread({
            var last: String? = null
            while (running) {
                try {
                    val body = client.send(
                        HttpRequest.newBuilder(layoutUri).timeout(java.time.Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).takeIf { it.statusCode() == 200 }?.body()
                    if (body != null) {
                        if (last != null && body != last) {
                            println("レイアウトの変更を検知したため再読み込みします")
                            Platform.runLater(reload)
                        }
                        last = body
                    }
                } catch (_: Exception) {
                    // Piの再起動中など。次回再試行する
                }
                Thread.sleep(LAYOUT_POLL_INTERVAL_MS)
            }
        }, "Layout Watcher").apply { isDaemon = true }.start()
    }

    private fun sendLoop() {
        val frame = DevolayVideoFrame().apply {
            setResolution(options.width, options.height)
            fourCCType = DevolayFrameFourCCType.BGRA
            lineStride = options.width * 4
            setFrameRate(options.fps, 1)
        }
        while (running) {
            val buffer = pending.poll(500, TimeUnit.MILLISECONDS) ?: continue
            frame.setData(buffer)
            sender.sendVideoFrame(frame)
            free.offer(buffer)
        }
        frame.close()
    }

    private fun stopAll() {
        running = false
        senderThread.join(2000)
        sender.close()
        Platform.exit()
    }
}
