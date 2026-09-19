package bltconnector.cli

import fi.iki.elonen.NanoHTTPD
import org.slf4j.LoggerFactory
import java.net.NetworkInterface
import kotlin.system.exitProcess

/**
 * NIC選択・OSC宛先管理を行う組み込みWeb GUI。
 * 保存すると設定ファイルを書き換えるだけで、実行中のプロセスへは反映しない
 * (反映にはプロセス再起動が必要。旧netconfigと同じ「保存後は手動再起動」方針)。
 */
class ConfigWebServer(port: Int, private val configPath: String) : NanoHTTPD(port) {
    private val logger = LoggerFactory.getLogger(ConfigWebServer::class.java)

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.POST && session.uri == "/save" -> handleSave(session)
            session.method == Method.POST && session.uri == "/restart" -> handleRestart()
            else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", renderPage(null))
        }
    }

    /**
     * プロセスを再起動する。SSH端末を開かずに設定変更を反映できるようにするための機能
     * (このアプリは設定保存だけではホットリロードしないため)。
     * `ProcessHandle`から現在のJVM起動コマンドをそのまま再現し、新プロセスを起動してから
     * 自分自身を終了する(systemd等の外部プロセス管理に依存しない)。
     */
    private fun handleRestart(): Response {
        Thread({
            Thread.sleep(500) // レスポンスを書き終える猶予
            restartProcess()
        }, "Restart").apply { isDaemon = true }.start()
        return newFixedLengthResponse(
            Response.Status.OK, "text/html; charset=utf-8",
            renderPage("再起動しています。数秒後にページを再読み込みしてください。"),
        )
    }

    private fun restartProcess() {
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null)
        if (command == null) {
            logger.error("再起動に必要なプロセス起動情報が取得できませんでした")
            return
        }
        val args = info.arguments().orElse(emptyArray())
        try {
            ProcessBuilder(listOf(command) + args)
                .directory(java.io.File(System.getProperty("user.dir")))
                .inheritIO()
                .start()
        } catch (e: Exception) {
            logger.error("新プロセスの起動に失敗しました。再起動を中止します", e)
            return
        }
        exitProcess(0)
    }

    private fun handleSave(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val params = session.parameters

        fun field(name: String) = params[name]?.firstOrNull()?.trim().orEmpty()

        val outputInterface = field("outputInterface").ifBlank { null }
        val overlayPort = field("overlayPort").toIntOrNull() ?: 8090
        val webGuiPort = field("webGuiPort").toIntOrNull() ?: 8081

        val destNames = params["destName"].orEmpty()
        val destHosts = params["destHost"].orEmpty()
        val destPorts = params["destPort"].orEmpty()
        val destinations = destHosts.indices.mapNotNull { i ->
            val host = destHosts.getOrNull(i)?.trim().orEmpty()
            val port = destPorts.getOrNull(i)?.trim()?.toIntOrNull()
            if (host.isBlank() || port == null) return@mapNotNull null
            val name = destNames.getOrNull(i)?.trim()?.ifBlank { "$host:$port" } ?: "$host:$port"
            DestinationConfig(name, host, port)
        }

        val newConfig = Config(
            outputInterface = outputInterface,
            overlayPort = overlayPort,
            webGuiPort = webGuiPort,
            carabinerPort = Config.load(configPath).carabinerPort, // フォームに項目が無いため既存値を渡す
            destinations = destinations,
        )
        Config.save(configPath, newConfig)

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", renderPage("保存しました。反映するにはプロセスを再起動してください。"))
    }

    private fun listInterfaceNames(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .map { it.name }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun renderPage(message: String?): String {
        val config = Config.load(configPath)
        val interfaces = listInterfaceNames()

        val interfaceOptions = buildString {
            append("<option value=\"\">-- 自動(全NIC) --</option>")
            interfaces.forEach { name ->
                val selected = if (name == config.outputInterface) " selected" else ""
                append("<option value=\"${escape(name)}\"$selected>${escape(name)}</option>")
            }
        }

        val destinationRows = buildString {
            config.destinations.forEach { d ->
                append(destinationRow(d.name, d.host, d.port.toString()))
            }
            // 追加用の空行を1つ用意しておく
            append(destinationRow("", "", ""))
        }

        return """
            <!doctype html>
            <html lang="ja">
            <head>
            <meta charset="utf-8">
            <title>BLT Connector CLI - 設定</title>
            <style>
              body { font-family: sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; }
              table { border-collapse: collapse; width: 100%; margin-bottom: 1rem; }
              td, th { border: 1px solid #ccc; padding: 0.4rem; }
              input[type=text] { width: 100%; box-sizing: border-box; }
              .msg { color: #2a7d2a; }
              label { display: inline-block; width: 10rem; }
            </style>
            </head>
            <body>
              <h1>BLT Connector CLI - 設定</h1>
              ${if (message != null) "<p class=\"msg\">${escape(message)}</p>" else ""}
              <form method="post" action="/save">
                <div>
                  <label for="outputInterface">送信用NIC:</label>
                  <select name="outputInterface" id="outputInterface">$interfaceOptions</select>
                </div>
                <div>
                  <label for="overlayPort">Overlayポート:</label>
                  <input type="text" name="overlayPort" id="overlayPort" value="${config.overlayPort}">
                </div>
                <div>
                  <label for="webGuiPort">この設定画面のポート:</label>
                  <input type="text" name="webGuiPort" id="webGuiPort" value="${config.webGuiPort}">
                </div>

                <h2>OSC配信先</h2>
                <table>
                  <tr><th>名前</th><th>ホスト</th><th>ポート</th></tr>
                  $destinationRows
                </table>

                <button type="submit">保存</button>
              </form>

              <h2>プロセス再起動</h2>
              <p>設定を保存しても実行中のプロセスには反映されません。反映するにはここから再起動してください。</p>
              <form method="post" action="/restart" onsubmit="return confirm('プロセスを再起動します。よろしいですか?');">
                <button type="submit">再起動</button>
              </form>
            </body>
            </html>
        """.trimIndent()
    }

    private fun destinationRow(name: String, host: String, port: String): String {
        return """
            <tr>
              <td><input type="text" name="destName" value="${escape(name)}"></td>
              <td><input type="text" name="destHost" value="${escape(host)}"></td>
              <td><input type="text" name="destPort" value="${escape(port)}"></td>
            </tr>
        """.trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, true)
    }

    fun stopServer() {
        stop()
    }
}
