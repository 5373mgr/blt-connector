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
class ConfigWebServer(port: Int, private val configPath: String, private val dhcpService: DhcpService) : NanoHTTPD(port) {
    private val logger = LoggerFactory.getLogger(ConfigWebServer::class.java)

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.POST && session.uri == "/save" -> handleSave(session)
            session.method == Method.POST && session.uri == "/restart" -> handleRestart()
            session.method == Method.POST && session.uri == "/dhcp" -> handleDhcpToggle(session)
            else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", renderPage(null))
        }
    }

    /**
     * Pro DJ Linkセグメント(eth0)向けのDHCPサーバー(`dnsmasq.service`、
     * `/etc/dnsmasq.d/prodjlink.conf`で設定済み)のON/OFFをWeb UIから切り替える。
     *
     * アプリはroot権限を持たない`blt-admin`ユーザーで動くため、このサービスの
     * start/stop/enable/disableだけに限定したNOPASSWD sudoルール
     * (`/etc/sudoers.d/blt-connector-dnsmasq`)を前提にしている。他のコマンドへは昇格できない。
     */
    private fun handleDhcpToggle(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val enable = session.parameters["action"]?.firstOrNull() == "enable"
        val message = try {
            dhcpService.setEnabled(enable)
            if (enable) "DHCPサーバーを有効にしました。" else "DHCPサーバーを無効にしました。"
        } catch (e: Exception) {
            logger.warn("DHCPサーバーの切り替えに失敗しました", e)
            "DHCPサーバーの切り替えに失敗しました: ${e.message}"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", renderPage(message))
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
        val waveformStyle = field("waveformStyle").takeIf { it == "THREE_BAND" } ?: "RGB"
        val virtualCdjDeviceNumber = field("virtualCdjDeviceNumber").toIntOrNull()?.takeIf { it in 0..6 } ?: 0

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

        val smNames = params["smDestName"].orEmpty()
        val smHosts = params["smDestHost"].orEmpty()
        val smPorts = params["smDestPort"].orEmpty()
        val streamMixDestinations = smHosts.indices.mapNotNull { i ->
            val host = smHosts.getOrNull(i)?.trim().orEmpty()
            val port = smPorts.getOrNull(i)?.trim()?.toIntOrNull()
            if (host.isBlank() || port == null) return@mapNotNull null
            val name = smNames.getOrNull(i)?.trim()?.ifBlank { "$host:$port" } ?: "$host:$port"
            DestinationConfig(name, host, port)
        }

        val opusSlots = params["opusSlot"].orEmpty()
        val opusPaths = params["opusPath"].orEmpty()
        val opusArchives = opusSlots.indices.mapNotNull { i ->
            val slot = opusSlots.getOrNull(i)?.trim()?.toIntOrNull()
            val path = opusPaths.getOrNull(i)?.trim().orEmpty()
            if (slot == null || path.isBlank()) return@mapNotNull null
            OpusArchiveConfig(slot, path)
        }

        val dhcpAutoMode = params["dhcpAutoMode"]?.firstOrNull() == "true"

        val newConfig = Config(
            outputInterface = outputInterface,
            overlayPort = overlayPort,
            webGuiPort = webGuiPort,
            carabinerPort = Config.load(configPath).carabinerPort, // フォームに項目が無いため既存値を渡す
            waveformStyle = waveformStyle,
            destinations = destinations,
            streamMixDestinations = streamMixDestinations,
            opusArchives = opusArchives,
            dhcpAutoMode = dhcpAutoMode,
            virtualCdjDeviceNumber = virtualCdjDeviceNumber,
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

        val dhcpIsActive = dhcpService.isActive()
        val dhcpIsEnabled = dhcpService.isEnabled()
        val dhcpActive = dhcpIsActive ?: false
        val dhcpStatusText = when (dhcpIsActive) {
            null -> "現在の状態: 取得できませんでした"
            else -> "現在の状態: ${if (dhcpIsActive) "有効(稼働中)" else "無効(停止中)"}" +
                if (dhcpIsEnabled != null && dhcpIsActive != dhcpIsEnabled) "(起動状態と自動起動設定が食い違っています)" else ""
        }

        val interfaceOptions = buildString {
            append("<option value=\"\">-- 自動(全NIC) --</option>")
            interfaces.forEach { name ->
                val selected = if (name == config.outputInterface) " selected" else ""
                append("<option value=\"${escape(name)}\"$selected>${escape(name)}</option>")
            }
        }

        val destinationRows = buildString {
            config.destinations.forEach { d ->
                append(destinationRow("destName", "destHost", "destPort", d.name, d.host, d.port.toString()))
            }
            // 追加用の空行を1つ用意しておく
            append(destinationRow("destName", "destHost", "destPort", "", "", ""))
        }

        val streamMixDestinationRows = buildString {
            config.streamMixDestinations.forEach { d ->
                append(destinationRow("smDestName", "smDestHost", "smDestPort", d.name, d.host, d.port.toString()))
            }
            // 追加用の空行を1つ用意しておく
            append(destinationRow("smDestName", "smDestHost", "smDestPort", "", "", ""))
        }

        val opusArchiveRows = buildString {
            config.opusArchives.forEach { a ->
                append(opusArchiveRow(a.slot.toString(), a.path))
            }
            // 追加用の空行を1つ用意しておく
            append(opusArchiveRow("", ""))
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
                <div>
                  <label for="waveformStyle">波形の取得方式:</label>
                  <select name="waveformStyle" id="waveformStyle">
                    <option value="RGB"${if (config.waveformStyle != "THREE_BAND") " selected" else ""}>RGB(色波形)</option>
                    <option value="THREE_BAND"${if (config.waveformStyle == "THREE_BAND") " selected" else ""}>3Band</option>
                  </select>
                </div>
                <div>
                  <label for="virtualCdjDeviceNumber">Receiverのプレイヤー番号:</label>
                  <input type="text" name="virtualCdjDeviceNumber" id="virtualCdjDeviceNumber" value="${config.virtualCdjDeviceNumber}">
                </div>
                <p style="color:#666; font-size:0.9em;">
                  0 = 自動選択(既定、起動時に空いている1〜4の最小番号を使う)。1〜6を指定すると
                  その番号を明示的に要求する。実機CDJの台数より大きい番号(例: CDJが2台なら3〜6の
                  いずれか)を指定すると、Receiver起動時の自動選択が後から電源投入された実機CDJの
                  番号と衝突し続けて接続が不安定になる問題を避けられる。CDJが4台(1〜4番)ある場合は
                  5か6を指定する(CDJ-3000世代のみ対応。Nexus等の旧世代機器が混在する場合は
                  5・6番でメタデータが取得できない可能性がある)。
                </p>

                <h2>OSC配信先</h2>
                <table>
                  <tr><th>名前</th><th>ホスト</th><th>ポート</th></tr>
                  $destinationRows
                </table>

                <h2>StreamMix Display配信先</h2>
                <p>
                  外部ソフト「StreamMix Display」互換のOSCスキーマ(<code>/deck/{n}/...</code>、
                  実機Beat Link Trigger用設定<code>BTL_OSC.blt</code>の送信仕様を再現したもの)で
                  デッキ状態を配信します。上の「OSC配信先」(独自スキーマ)とは別経路で、
                  両方を同時に使えます。詳細は<code>docs/BTL_OSC_Documentation.md</code>参照。
                </p>
                <table>
                  <tr><th>名前</th><th>ホスト</th><th>ポート</th></tr>
                  $streamMixDestinationRows
                </table>

                <h2>OPUS-QUADメタデータアーカイブ</h2>
                <p>
                  OPUS-QUADは曲名/アーティスト/波形/ジャケットを自分では配信できないため、
                  Beat Link Trigger等の「File &gt; Archive Metadata」で事前に作成したアーカイブファイルを
                  このPC上に配置し、USBスロット番号(OPUS-QUADのUSBポートに対応。1〜3)と紐付けてください。
                  USBメモリの中身を変更した場合はアーカイブを作り直して置き換える必要があります。
                </p>
                <table>
                  <tr><th>USBスロット(1〜3)</th><th>アーカイブファイルのパス</th></tr>
                  $opusArchiveRows
                </table>

                <h2>DHCP自動切り替え</h2>
                <div>
                  <label for="dhcpAutoMode">自動切り替え:</label>
                  <input type="checkbox" name="dhcpAutoMode" id="dhcpAutoMode" value="true"${if (config.dhcpAutoMode) " checked" else ""}>
                  他のDHCPサーバーを検知したら約1分間隔のチェックでDHCPサーバーを自動OFF、
                  いなければ自動ONにする(ONの間は下の手動ボタンも次回チェックで上書きされ得ます)
                </div>

                <button type="submit">保存</button>
              </form>

              <h2>プロセス再起動</h2>
              <p>設定を保存しても実行中のプロセスには反映されません。反映するにはここから再起動してください。</p>
              <form method="post" action="/restart" onsubmit="return confirm('プロセスを再起動します。よろしいですか?');">
                <button type="submit">再起動</button>
              </form>

              <h2>Pro DJ Link用DHCPサーバー</h2>
              <p>
                このNIC(eth0)上でDHCPサーバー(192.168.50.10〜200を配布)を動かすかどうかを切り替えます。
                会場の機材が固定IPで運用されている場合など、DHCPが不要/邪魔な場合はここで無効化してください。
                $dhcpStatusText
              </p>
              <form method="post" action="/dhcp">
                <input type="hidden" name="action" value="${if (dhcpActive) "disable" else "enable"}">
                <button type="submit">${if (dhcpActive) "DHCPを無効にする" else "DHCPを有効にする"}</button>
              </form>
              <p style="color:#666; font-size:0.9em;">
                自動切り替え(他のDHCPサーバーを検知したら自動でOFF、いなければ自動でONにする)の
                ON/OFFは上の「OSC配信先」等と同じ設定フォーム内にあります。保存後は再起動が必要です。
              </p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun destinationRow(nameField: String, hostField: String, portField: String, name: String, host: String, port: String): String {
        return """
            <tr>
              <td><input type="text" name="$nameField" value="${escape(name)}"></td>
              <td><input type="text" name="$hostField" value="${escape(host)}"></td>
              <td><input type="text" name="$portField" value="${escape(port)}"></td>
            </tr>
        """.trimIndent()
    }

    private fun opusArchiveRow(slot: String, path: String): String {
        return """
            <tr>
              <td><input type="text" name="opusSlot" value="${escape(slot)}"></td>
              <td><input type="text" name="opusPath" value="${escape(path)}"></td>
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
