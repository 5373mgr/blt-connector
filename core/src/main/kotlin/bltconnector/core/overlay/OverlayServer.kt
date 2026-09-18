package bltconnector.core.overlay

import bltconnector.core.receiver.DeckSnapshot
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * OBSのBrowser Source等、配信画面向けのローカルオーバーレイを提供するサーバー。
 * OSC(外部VJソフト向け)とは独立した経路。localhost想定(必要ならbindAddressで変更可)。
 *
 * - `GET /`             -- 埋め込み用HTML(resources/overlay/index.html)
 * - `GET /art/deck/{n}` -- 現在のジャケット画像(JPEG)
 * - `WS  /ws`           -- デッキ状態をJSONでプッシュ配信
 *
 * [bindAddress] を指定すると待受NICを固定できる(未指定時は全NICで待受)。
 */
class OverlayServer(port: Int = 8090, bindAddress: String? = null) :
    NanoWSD(bindAddress, port) {
    private val logger = LoggerFactory.getLogger(OverlayServer::class.java)
    private val clients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val latestArt = ConcurrentHashMap<Int, ByteArray>()
    private val trackVersions = ConcurrentHashMap<Int, Int>()
    private val lastTrackKey = ConcurrentHashMap<Int, Any?>()

    private val indexHtml: ByteArray by lazy {
        OverlayServer::class.java.getResourceAsStream("/overlay/index.html")?.use { it.readBytes() }
            ?: error("overlay/index.html not found on classpath")
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
            uri == "/" -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(indexHtml), indexHtml.size.toLong(),
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

            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
        }
    }

    /** デッキ1台分の最新状態を全WebSocketクライアントへ配信する。 */
    fun update(deck: DeckSnapshot) {
        if (lastTrackKey[deck.playerNumber] != deck.trackKey) {
            lastTrackKey[deck.playerNumber] = deck.trackKey
            trackVersions.merge(deck.playerNumber, 1, Int::plus)
            if (deck.artwork != null) {
                latestArt[deck.playerNumber] = deck.artwork
            } else {
                latestArt.remove(deck.playerNumber)
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
            put("trackVersion", trackVersions[deck.playerNumber] ?: 0)
        }.toString()

        clients.forEach { client ->
            try {
                if (client.isOpen) client.send(json)
            } catch (e: Exception) {
                logger.warn("failed to send overlay update", e)
            }
        }
    }
}
