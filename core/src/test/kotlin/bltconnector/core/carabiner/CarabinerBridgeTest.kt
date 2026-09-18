package bltconnector.core.carabiner

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * 実際に同梱されたCarabinerバイナリを起動し、TCPプロトコルで status を受け取れることを確認する。
 * CDJ/Ableton Live等の外部Link参加者が居なくてもCarabiner単体で検証できる。
 */
class CarabinerBridgeTest {
    @Test
    fun `starts the bundled Carabiner binary and receives a status message`() {
        val bridge = CarabinerBridge(port = 17123)
        assertTrue(bridge.isSupported(), "このプラットフォーム向けのCarabinerバイナリが見つかりません")

        bridge.start()
        try {
            var status: Map<String, Any?>? = null
            for (i in 1..40) {
                status = bridge.lastStatus
                if (status != null) break
                Thread.sleep(250)
            }
            assertNotNull(status, "Carabinerからstatusを受信できませんでした")
            assertTrue(status.containsKey("bpm"), "statusにbpmが含まれていません: $status")

            bridge.updateMasterTempo(140.0)
            var updated: Map<String, Any?>? = null
            for (i in 1..40) {
                val current = bridge.lastStatus
                if (current != null && (current["bpm"] as? Number)?.toDouble()?.let { Math.abs(it - 140.0) < 0.01 } == true) {
                    updated = current
                    break
                }
                Thread.sleep(250)
            }
            assertNotNull(updated, "bpm送信後にLinkセッションのテンポが140.0へ変わりませんでした: last=${bridge.lastStatus}")
        } finally {
            bridge.stop()
        }
    }
}
