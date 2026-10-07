package bltconnector.cli

import bltconnector.core.carabiner.CarabinerBridge
import bltconnector.core.overlay.OverlayServer
import bltconnector.core.receiver.Receiver
import bltconnector.core.sender.Destination
import bltconnector.core.sender.Sender
import bltconnector.core.sender.StreamMixSender
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("bltconnector.cli.Main")

/**
 * BLT Connector CLI版のエントリポイント。
 *
 * GUI版(Win/Mac)と役割は同じ(Receiver + Sender + Overlay)で、二者択一の代替構成として使う
 * (同じProDJLinkネットワーク上でGUI版とCLI版を同時に動かさないこと。Receiverが複数になり
 * 冒頭の「BLTを2台立てると壊れる」問題に逆戻りするため)。
 *
 * 設定はJSONファイル(デフォルト: ./blt-connector-cli.json)で管理し、組み込みWeb GUIから編集する。
 * 保存内容の反映にはプロセスの再起動が必要(ホットリロードはしない)。
 */
fun main(args: Array<String>) {
    val configPath = args.firstOrNull { it.startsWith("--config=") }
        ?.substringAfter("=")
        ?: "blt-connector-cli.json"

    val config = Config.load(configPath)
    logger.info("設定ファイル: {}", configPath)

    val outputAddress = config.outputInterface?.let { resolveInterfaceAddress(it) }
    if (config.outputInterface != null && outputAddress == null) {
        logger.warn("指定された送信用NIC '{}' のIPv4アドレスが見つかりませんでした。自動選択にフォールバックします。", config.outputInterface)
    }

    // 設定ファイルと同じディレクトリに overlay.html を置くと、リビルドなしでOverlayの見た目を差し替えられる。
    val configDir = File(configPath).absoluteFile.parentFile
    val overlayHtmlPath = configDir.resolve("overlay.html").path

    val receiver = Receiver()
    val sender = Sender(localBindAddress = outputAddress)
    val streamMixSender = StreamMixSender(localBindAddress = outputAddress)
    val overlay = OverlayServer(
        port = config.overlayPort,
        bindAddress = outputAddress?.hostAddress,
        htmlOverridePath = overlayHtmlPath,
        // Overlayのレイアウト設定(overlay-layout.json)とアップロードしたフォント(fonts/)の保存先。
        assetsDir = configDir.path,
    )

    config.destinations.forEach { d ->
        try {
            sender.addDestination(Destination(d.name, InetAddress.getByName(d.host), d.port))
            logger.info("OSC配信先を追加: {} ({}:{})", d.name, d.host, d.port)
        } catch (e: Exception) {
            logger.warn("配信先の追加に失敗しました: {}", d, e)
        }
    }
    config.streamMixDestinations.forEach { d ->
        try {
            streamMixSender.addDestination(Destination(d.name, InetAddress.getByName(d.host), d.port))
            logger.info("StreamMix Display配信先を追加: {} ({}:{})", d.name, d.host, d.port)
        } catch (e: Exception) {
            logger.warn("StreamMix Display配信先の追加に失敗しました: {}", d, e)
        }
    }

    // Master BPMをAbleton Linkへブリッジする(GUI版には元々あったがCLI版に組み込み漏れしていた)。
    // Masterデッキの実効BPMを毎tick送り続けるので、他のLink参加ソフト側を「Sync専用(ローカルで
    // テンポを変更しない)」設定にしておけば、実質的にCDJのMasterがテンポを主導する形になる。
    val carabinerBridge = CarabinerBridge(port = config.carabinerPort)
    carabinerBridge.start()

    // beat-link(VirtualCdj等)はCDJが見つかるまで内部で数十秒ブロックすることがあるため、
    // Overlay/設定Web GUIを先に起動してからReceiverは別スレッドで起動する
    // (CDJの電源投入前にCLIを起動しても設定画面にアクセスできるようにするため)。
    overlay.startServer()

    val dhcpService = DhcpService(configDir.resolve("dhcp-probe.py"))
    dhcpService.extractProbeScript()

    val webServer = ConfigWebServer(config.webGuiPort, configPath, dhcpService)
    webServer.startServer()

    logger.info("Overlay:   http://localhost:{}/", config.overlayPort)
    logger.info("Overlayレイアウトエディタ: http://localhost:{}/editor", config.overlayPort)
    logger.info("設定画面:  http://localhost:{}/", config.webGuiPort)
    logger.info("Overlay見た目を差し替えるには {} を配置してください(なければ同梱HTMLを使用)", overlayHtmlPath)
    logger.info(
        "Ableton Link: {}(port={})",
        if (carabinerBridge.isSupported()) "有効" else "このプラットフォーム向けCarabinerバイナリが無いため無効",
        config.carabinerPort,
    )

    val waveformStyle = if (config.waveformStyle == "THREE_BAND") {
        WaveformFinder.WaveformStyle.THREE_BAND
    } else {
        WaveformFinder.WaveformStyle.RGB
    }
    logger.info("波形取得方式: {}", waveformStyle)

    if (config.virtualCdjDeviceNumber in 1..4) {
        logger.info("VirtualCdjのプレイヤー番号を{}に固定します(実機CDJとの番号衝突回避)", config.virtualCdjDeviceNumber)
    }

    Thread({
        logger.info("Receiver starting; waiting for Pro DJ Link devices...")
        receiver.start(waveformStyle, config.virtualCdjDeviceNumber)
        val opusArchives = config.opusArchives.mapNotNull { entry ->
            val file = File(entry.path)
            if (!file.exists()) {
                logger.warn("設定されたメタデータアーカイブが見つかりません(slot={}): {}", entry.slot, file)
                null
            } else {
                entry.slot to file
            }
        }.toMap()
        receiver.attachOpusArchives(opusArchives)
    }, "Receiver Startup").apply { isDaemon = true }.start()

    val scheduler = Executors.newSingleThreadScheduledExecutor()
    scheduler.scheduleAtFixedRate({
        try {
            (1..4).mapNotNull { receiver.pollDeck(it) }.forEach {
                sender.update(it)
                streamMixSender.update(it)
                overlay.update(it)
                if (it.master) carabinerBridge.updateMasterTempo(it.bpm)
            }
        } catch (e: Exception) {
            logger.warn("デッキ状態の更新中にエラーが発生しました", e)
        }
    }, 0, 100, TimeUnit.MILLISECONDS)

    // 他のDHCPサーバーの有無を定期的にプローブし、Pro DJ Link用DHCPサーバー(dnsmasq)を自動でON/OFFする
    // (config.dhcpAutoModeがtrueの場合のみ。他の設定同様、この値自体は起動時に読み込んだものを使い、
    // 保存後の反映にはプロセス再起動が必要という方針に合わせている)。
    val dhcpScheduler = Executors.newSingleThreadScheduledExecutor()
    if (config.dhcpAutoMode) {
        logger.info("DHCP自動切り替えが有効です(約1分間隔で他のDHCPサーバーの有無をチェックします)")
        dhcpScheduler.scheduleAtFixedRate({
            try {
                val foreign = dhcpService.probeForeignDhcp()
                val active = dhcpService.isActive()
                if (foreign == null || active == null) {
                    logger.warn("DHCP自動切り替え: 状態の取得に失敗したため、今回はスキップします")
                    return@scheduleAtFixedRate
                }
                if (foreign && active) {
                    logger.info("DHCP自動切り替え: 他のDHCPサーバーを検知したため、DHCPサーバーを無効化します")
                    dhcpService.setEnabled(false)
                } else if (!foreign && !active) {
                    logger.info("DHCP自動切り替え: 他のDHCPサーバーが見当たらないため、DHCPサーバーを有効化します")
                    dhcpService.setEnabled(true)
                }
            } catch (e: Exception) {
                logger.warn("DHCP自動切り替えのチェック中にエラーが発生しました", e)
            }
        }, 10, 60, TimeUnit.SECONDS)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("shutting down...")
        scheduler.shutdownNow()
        dhcpScheduler.shutdownNow()
        receiver.stop()
        sender.close()
        streamMixSender.close()
        overlay.stopServer()
        webServer.stopServer()
        carabinerBridge.stop()
    })

    Thread.currentThread().join()
}

private fun resolveInterfaceAddress(name: String): InetAddress? {
    val ni = NetworkInterface.getByName(name) ?: return null
    return ni.inetAddresses.asSequence().firstOrNull { it is Inet4Address }
}
