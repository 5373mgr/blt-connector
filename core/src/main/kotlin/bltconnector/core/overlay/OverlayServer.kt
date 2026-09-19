package bltconnector.core.overlay

import bltconnector.core.receiver.DeckSnapshot
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * OBSのBrowser Source等、配信画面向けのローカルオーバーレイを提供するサーバー。
 * OSC(外部VJソフト向け)とは独立した経路。localhost想定(必要ならbindAddressで変更可)。
 *
 * - `GET  /`                        -- 埋め込み用HTML(resources/overlay/index.html、[htmlOverridePath]があればそちらを優先)。
 *                                      保存済みレイアウトの各要素(テキスト/画像)に、`{deck2-track-name}`等の
 *                                      変数を埋め込んで表示する
 * - `GET  /editor`                  -- Overlayの表示位置・フォントをドラッグ&ドロップで調整するレイアウトエディタ
 * - `GET  /monitor`                 -- VJが自分のブラウザで確認するための非透過モニターページ(波形描画つき、全デッキ表示)
 * - `GET  /art/deck/{n}`            -- 現在のジャケット画像(JPEG)
 * - `GET  /waveform/deck/{n}`       -- 現在の波形プレビューの生バイト列(mono/color、`waveformColor`で判別)
 * - `GET  /waveform-detail/deck/{n}` -- 現在の高解像度波形の生バイト列(`waveformDetailColor`で判別)
 * - `GET  /layout`                  -- 保存済みのOverlayレイアウト設定(JSON)
 * - `POST /layout`                  -- レイアウト設定(JSON)を保存する
 * - `GET  /fonts`                   -- インストール済みフォント一覧(JSON配列)
 * - `POST /fonts`                   -- フォントファイル(ttf/otf/woff/woff2)をアップロードして登録する
 * - `GET  /fonts/{file}`            -- 登録済みフォントファイルそのもの(`@font-face`の`src`用)
 * - `WS   /ws`                      -- デッキ状態をJSONでプッシュ配信
 *
 * [bindAddress] を指定すると待受NICを固定できる(未指定時は全NICで待受)。
 * [htmlOverridePath] を指定すると、そのパスにファイルが存在する限り同梱HTMLの代わりに
 * そちらを毎リクエスト読み込んで返す(リビルド不要でオーバーレイの見た目を差し替えられるようにするため)。
 * ファイルが存在しない場合は同梱HTMLにフォールバックする。
 * [assetsDir] にレイアウト設定(`overlay-layout.json`)とアップロードされたフォント(`fonts/`)を保存する。
 */
class OverlayServer(
    port: Int = 8090,
    bindAddress: String? = null,
    private val htmlOverridePath: String? = null,
    private val assetsDir: String = System.getProperty("user.dir"),
) : NanoWSD(bindAddress, port) {
    private val logger = LoggerFactory.getLogger(OverlayServer::class.java)
    private val clients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val latestArt = ConcurrentHashMap<Int, ByteArray>()
    private val latestWaveform = ConcurrentHashMap<Int, ByteArray>()
    private val latestWaveformDetail = ConcurrentHashMap<Int, ByteArray>()
    private val trackVersions = ConcurrentHashMap<Int, Int>()
    private val lastTrackKey = ConcurrentHashMap<Int, Any>()
    private val latestDeck = ConcurrentHashMap<Int, DeckSnapshot>()
    private val fontLibrary = FontLibrary(assetsDir)
    private val layoutStore = LayoutStore(assetsDir)

    private val bundledIndexHtml: ByteArray by lazy {
        OverlayServer::class.java.getResourceAsStream("/overlay/index.html")?.use { it.readBytes() }
            ?: error("overlay/index.html not found on classpath")
    }

    private val monitorHtml: ByteArray by lazy {
        OverlayServer::class.java.getResourceAsStream("/overlay/monitor.html")?.use { it.readBytes() }
            ?: error("overlay/monitor.html not found on classpath")
    }

    private val editorHtml: ByteArray by lazy {
        OverlayServer::class.java.getResourceAsStream("/overlay/editor.html")?.use { it.readBytes() }
            ?: error("overlay/editor.html not found on classpath")
    }

    /** [htmlOverridePath]のファイルがあればそれを、なければ同梱HTMLを返す。差し替えを都度反映するため毎回読み直す。 */
    private fun currentIndexHtml(): ByteArray {
        val overrideFile = htmlOverridePath?.let { File(it) }
        if (overrideFile != null && overrideFile.isFile) {
            try {
                return overrideFile.readBytes()
            } catch (e: IOException) {
                logger.warn("差し替え用HTML({})の読み込みに失敗したため、同梱HTMLを使用します", htmlOverridePath, e)
            }
        }
        return bundledIndexHtml
    }

    /** サーバーを起動する(daemonスレッドで待受)。 */
    fun startServer() {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
    }

    /** サーバーを停止する。 */
    fun stopServer() {
        stop()
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return object : WebSocket(handshake) {
            override fun onOpen() {
                clients.add(this)
            }

            override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                clients.remove(this)
            }

            override fun onMessage(message: WebSocketFrame?) {
                // クライアントからの送信は使わない
            }

            override fun onPong(pong: WebSocketFrame?) {
                // 特に何もしない
            }

            override fun onException(exception: IOException?) {
                clients.remove(this)
            }
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" -> {
                val html = currentIndexHtml()
                NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK, "text/html; charset=utf-8",
                    ByteArrayInputStream(html), html.size.toLong(),
                )
            }

            uri == "/editor" -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(editorHtml), editorHtml.size.toLong(),
            )

            uri == "/monitor" -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(monitorHtml), monitorHtml.size.toLong(),
            )

            uri.startsWith("/art/deck/") -> {
                val playerNumber = uri.removePrefix("/art/deck/").substringBefore("?").toIntOrNull()
                val bytes = playerNumber?.let { latestArt[it] }
                if (bytes != null) {
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.OK, "image/jpeg",
                        ByteArrayInputStream(bytes), bytes.size.toLong(),
                    )
                } else {
                    NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "no artwork")
                }
            }

            uri.startsWith("/waveform/deck/") -> {
                val playerNumber = uri.removePrefix("/waveform/deck/").substringBefore("?").toIntOrNull()
                val bytes = playerNumber?.let { latestWaveform[it] }
                if (bytes != null) {
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.OK, "application/octet-stream",
                        ByteArrayInputStream(bytes), bytes.size.toLong(),
                    )
                } else {
                    NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "no waveform")
                }
            }

            uri.startsWith("/waveform-detail/deck/") -> {
                val playerNumber = uri.removePrefix("/waveform-detail/deck/").substringBefore("?").toIntOrNull()
                val bytes = playerNumber?.let { latestWaveformDetail[it] }
                if (bytes != null) {
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.OK, "application/octet-stream",
                        ByteArrayInputStream(bytes), bytes.size.toLong(),
                    )
                } else {
                    NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "no waveform detail")
                }
            }

            uri == "/layout" && session.method == Method.GET -> handleGetLayout()
            uri == "/layout" && session.method == Method.POST -> handleSaveLayout(session)

            uri == "/fonts" && session.method == Method.GET -> handleListFonts()
            uri == "/fonts" && session.method == Method.POST -> handleUploadFont(session)

            uri.startsWith("/fonts/") -> handleFontFile(uri.removePrefix("/fonts/").substringBefore("?"))

            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
        }
    }

    private fun handleGetLayout(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", layoutStore.load())

    private fun handleSaveLayout(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "invalid request: ${e.message}")
        }
        val content = body["postData"]
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "empty body")
        if (!layoutStore.save(content)) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "invalid json")
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleListFonts(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", fontLibrary.listAsJson().toString())

    /**
     * フォントファイルをアップロードして登録する。クライアントが送るファイル名は信用せず、
     * ファイル先頭のマジックバイトから実際の形式(ttf/otf/woff/woff2)を判定してから保存する。
     */
    private fun handleUploadFont(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "invalid upload: ${e.message}")
        }
        val displayName = session.parameters["name"]?.firstOrNull()?.trim().orEmpty().ifBlank { "custom-font" }
        val tempPath = files["fontFile"]
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "no file uploaded")
        val entry = fontLibrary.register(File(tempPath), displayName)
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "unsupported font file")
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", entry.toJson().toString())
    }

    private fun handleFontFile(fileName: String): Response {
        val file = fontLibrary.fileFor(fileName)
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
        val bytes = file.readBytes()
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, FontLibrary.contentTypeFor(file),
            ByteArrayInputStream(bytes), bytes.size.toLong(),
        )
    }

    /** デッキ1台分の最新状態を全WebSocketクライアントへ配信する。 */
    fun update(deck: DeckSnapshot) {
        latestDeck[deck.playerNumber] = deck

        // ConcurrentHashMapはnull値を許容しないため、「曲情報なし」を表すのに実際のnullではなく
        // このセンチネルを使う(trackKeyがnullの間に更新が来ると put(key, null) でNPEになっていたため)。
        val trackKey = deck.trackKey ?: NO_TRACK
        if (lastTrackKey[deck.playerNumber] != trackKey) {
            lastTrackKey[deck.playerNumber] = trackKey
            trackVersions.merge(deck.playerNumber, 1, Int::plus)
            if (deck.artwork != null) {
                latestArt[deck.playerNumber] = deck.artwork
            } else {
                latestArt.remove(deck.playerNumber)
            }
            if (deck.waveform != null) {
                latestWaveform[deck.playerNumber] = deck.waveform
            } else {
                latestWaveform.remove(deck.playerNumber)
            }
            if (deck.waveformDetail != null) {
                latestWaveformDetail[deck.playerNumber] = deck.waveformDetail
            } else {
                latestWaveformDetail.remove(deck.playerNumber)
            }
        }

        val json = JSONObject().apply {
            put("playerNumber", deck.playerNumber)
            put("positionMs", deck.positionMs ?: -1)
            put("playing", deck.playing)
            put("bpm", deck.bpm)
            put("master", deck.master)
            put("title", deck.title ?: "")
            put("artist", deck.artist ?: "")
            put("album", deck.album ?: "")
            put("durationMs", deck.durationMs ?: -1)
            put("originalBpm", deck.originalBpm ?: -1.0)
            put("hasArt", latestArt.containsKey(deck.playerNumber))
            put("hasWaveform", latestWaveform.containsKey(deck.playerNumber))
            put("waveformColor", deck.waveformColor)
            put("hasWaveformDetail", latestWaveformDetail.containsKey(deck.playerNumber))
            put("waveformDetailColor", deck.waveformDetailColor)
            put("trackVersion", trackVersions[deck.playerNumber] ?: 0)
            put(
                "cues",
                JSONArray(
                    deck.cues.map { cue ->
                        JSONObject().apply {
                            put("timeMs", cue.timeMs)
                            put("hotCue", cue.hotCueNumber)
                            put("isLoop", cue.isLoop)
                            put("color", cue.colorRgb ?: JSONObject.NULL)
                            put("comment", cue.comment)
                        }
                    },
                ),
            )
            put("vars", buildVariablesJson())
        }.toString()

        clients.forEach { client ->
            try {
                if (client.isOpen) client.send(json)
            } catch (e: Exception) {
                logger.warn("failed to send overlay update", e)
            }
        }
    }

    /**
     * Overlayのテキストテンプレート(`{deck2-track-name}`等)や画像要素から参照できる変数の
     * フラットなマップを組み立てる。デッキ1〜4それぞれと、役割ベースのエイリアス
     * (`master-*`: Masterデッキ、`onair-*`: 実際にオンエア中かつ再生中のデッキ、
     * 複数該当時はMaster優先)を用意する。該当デッキが無い場合、文字列系は空文字、
     * `has-art`は"false"を返す(テンプレート置換で例外にならないようにするため)。
     */
    private fun buildVariablesJson(): JSONObject {
        val json = JSONObject()
        fun putDeckVars(prefix: String, deck: DeckSnapshot?) {
            json.put("$prefix-track-name", deck?.title ?: "")
            json.put("$prefix-artist-name", deck?.artist ?: "")
            json.put("$prefix-album-name", deck?.album ?: "")
            json.put("$prefix-comment", deck?.comment ?: "")
            json.put("$prefix-player-number", deck?.playerNumber?.toString() ?: "")
            json.put("$prefix-has-art", (deck != null && latestArt.containsKey(deck.playerNumber)).toString())
            json.put("$prefix-art-version", deck?.let { (trackVersions[it.playerNumber] ?: 0).toString() } ?: "0")
        }

        for (n in 1..4) {
            putDeckVars("deck$n", latestDeck[n])
        }

        val masterDeck = latestDeck.values.firstOrNull { it.master }
        putDeckVars("master", masterDeck)

        val onAirCandidates = latestDeck.values.filter { it.playing && it.onAir }
        val onAirDeck = onAirCandidates.firstOrNull { it.master } ?: onAirCandidates.firstOrNull()
        putDeckVars("onair", onAirDeck)

        return json
    }

    private companion object {
        /** [lastTrackKey]で「曲情報なし」を表すためのセンチネル(ConcurrentHashMapはnull値を格納できないため)。 */
        val NO_TRACK = Any()
    }
}
