package bltconnector.cli

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Pro DJ Linkセグメント(eth0)向けのDHCPサーバー(`dnsmasq.service`、
 * `/etc/dnsmasq.d/prodjlink.conf`で設定済み)の起動/停止/状態取得と、
 * 他のDHCPサーバーがすでにネットワーク上に存在するかのプローブをまとめたもの。
 *
 * アプリはroot権限を持たない`blt-admin`ユーザーで動くため、以下だけに限定した
 * NOPASSWD sudoルール(`/etc/sudoers.d/blt-connector-dnsmasq`)を前提にしている:
 * - `systemctl start/stop/enable/disable/is-active/is-enabled dnsmasq.service`
 * - `python3 <dhcpProbeScript>`(引数無しの完全一致)
 * 他のコマンドへは昇格できない。
 */
class DhcpService(private val dhcpProbeScript: File) {
    private val logger = LoggerFactory.getLogger(DhcpService::class.java)

    /** `cli/src/main/resources/dhcp-probe.py`を[dhcpProbeScript]へ展開する。起動のたびに上書きし、常に最新版を使う。 */
    fun extractProbeScript() {
        val resource = javaClass.getResourceAsStream("/dhcp-probe.py")
            ?: error("dhcp-probe.py resource not found on classpath")
        dhcpProbeScript.parentFile?.mkdirs()
        resource.use { input -> dhcpProbeScript.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun runSudo(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("sudo", "-n") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return exitCode to output
    }

    fun isActive(): Boolean? = try {
        runSudo("systemctl", "is-active", "dnsmasq.service").second.trim() == "active"
    } catch (e: Exception) {
        logger.debug("DHCPサーバーの状態取得(is-active)に失敗しました", e)
        null
    }

    fun isEnabled(): Boolean? = try {
        runSudo("systemctl", "is-enabled", "dnsmasq.service").second.trim() == "enabled"
    } catch (e: Exception) {
        logger.debug("DHCPサーバーの状態取得(is-enabled)に失敗しました", e)
        null
    }

    /** `dnsmasq.service`を起動(enable含む)/停止(disable含む)する。失敗時は例外を投げる。 */
    fun setEnabled(enable: Boolean) {
        val startStop = if (enable) "start" else "stop"
        val enableDisable = if (enable) "enable" else "disable"
        val (code1, out1) = runSudo("systemctl", startStop, "dnsmasq.service")
        if (code1 != 0) error("systemctl $startStop dnsmasq.service failed (exit=$code1): $out1")
        val (code2, out2) = runSudo("systemctl", enableDisable, "dnsmasq.service")
        if (code2 != 0) error("systemctl $enableDisable dnsmasq.service failed (exit=$code2): $out2")
    }

    /**
     * eth0上に自分(このPiのdnsmasq)以外のDHCPサーバーが存在するかをDHCPDISCOVERで探る。
     * 判定できなかった場合はnullを返す(sudoers未設定・スクリプト未展開・タイムアウト等)。
     */
    fun probeForeignDhcp(): Boolean? {
        return try {
            val process = ProcessBuilder("sudo", "-n", "python3", dhcpProbeScript.absolutePath)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("DHCPプローブの実行に失敗しました(exit={}): {}", exitCode, stderr.trim())
                return null
            }
            when {
                stdout.contains("FOREIGN_DHCP=true") -> true
                stdout.contains("FOREIGN_DHCP=false") -> false
                else -> {
                    logger.warn("DHCPプローブの出力を解釈できませんでした: {}", stdout.trim())
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("DHCPプローブの実行に失敗しました", e)
            null
        }
    }
}
