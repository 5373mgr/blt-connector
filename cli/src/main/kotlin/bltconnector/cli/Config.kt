package bltconnector.cli

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** OSC配信先1件分の設定。 */
data class DestinationConfig(val name: String, val host: String, val port: Int)

/**
 * 配信先のhostにこの値を指定すると、起動時にPro DJ Link側NICのブロードキャストアドレス
 * (例: 192.168.50.255)に置き換えて送信する。
 */
const val BROADCAST_HOST = "broadcast"

/** StreamMix Displayのデフォルト受信ポート(docs/BTL_OSC_Documentation.md)へのブロードキャスト。 */
val DEFAULT_STREAM_MIX_DESTINATIONS = listOf(DestinationConfig("StreamMix (ブロードキャスト)", BROADCAST_HOST, 8000))

/**
 * OPUS-QUAD利用時、USBスロット(1〜3)に紐付けるメタデータアーカイブファイルの設定。
 * アーカイブファイル自体はBeat Link Triggerの「File > Archive Metadata」等で別途作成したものを
 * このPCに配置し、そのパスを指定する(アーカイブ生成はこのプロジェクトでは行わない)。
 */
data class OpusArchiveConfig(val slot: Int, val path: String)

/**
 * Sender/Overlayの設定。Web GUIで編集し、ファイルに保存する。
 * 反映は起動中のプロセスへのホットリロードではなく、プロセス再起動時に読み込む方式(機能を絞る方針)。
 */
data class Config(
    /** Sender(OSC)・OverlayのバインドNIC名(未指定なら全NIC/OS任せ)。 */
    val outputInterface: String? = null,
    val overlayPort: Int = 8090,
    val webGuiPort: Int = 8081,
    /** CarabinerBridge(Ableton Link)がCarabinerバイナリと通信するローカルポート。 */
    val carabinerPort: Int = 17000,
    /**
     * 波形の取得方式("RGB"=色波形, "THREE_BAND"=3Band波形)。beat-linkの`WaveformFinder`は
     * アプリ全体でどちらか一方しか同時に扱えない(デッキ毎/要素毎の切り替えは不可)。
     */
    val waveformStyle: String = "RGB",
    val destinations: List<DestinationConfig> = emptyList(),
    /**
     * 外部ソフト「StreamMix Display」互換のOSCスキーマ(`/deck/{n}/...`)での配信先。
     * 既存の`destinations`(独自スキーマ`/blt/deck/{n}/...`)とは別経路で、両方を同時に使える。
     * 詳細は docs/BTL_OSC_Documentation.md 参照。
     *
     * 初期設定はPro DJ Linkネットワーク全体へのブロードキャスト(host=[BROADCAST_HOST])。
     * VJ側のMac/PCのIPを知らなくても、同じネットワーク上のStreamMix Displayならどれでも受信できる
     * (受信側は「受信ホスト」を0.0.0.0にしておく必要がある)。
     */
    val streamMixDestinations: List<DestinationConfig> = DEFAULT_STREAM_MIX_DESTINATIONS,
    val opusArchives: List<OpusArchiveConfig> = emptyList(),
    /**
     * trueの場合、eth0上に他のDHCPサーバーが存在するかを定期的にプローブし、
     * 存在すればPro DJ Link用DHCPサーバー(dnsmasq)を自動でOFF、いなければ自動でONにする
     * ([bltconnector.cli.DhcpService]参照)。falseの場合は設定画面からの手動切り替えのみ。
     */
    val dhcpAutoMode: Boolean = false,
    /**
     * VirtualCdjに明示的に要求させるプレイヤー番号(1〜4)。0(既定)ならbeat-link側の自動選択に任せる。
     * 実機CDJの台数より大きい番号を指定することで、起動時の自動選択が後から電源投入された実機CDJの
     * 番号と衝突し続ける問題([bltconnector.core.receiver.Receiver.start]のコメント参照)を避けられる。
     */
    val virtualCdjDeviceNumber: Int = 0,
) {
    fun toJson(): String {
        val json = JSONObject().apply {
            put("outputInterface", outputInterface ?: JSONObject.NULL)
            put("overlayPort", overlayPort)
            put("webGuiPort", webGuiPort)
            put("carabinerPort", carabinerPort)
            put("waveformStyle", waveformStyle)
            put("destinations", JSONArray(destinations.map {
                JSONObject().apply {
                    put("name", it.name)
                    put("host", it.host)
                    put("port", it.port)
                }
            }))
            put("streamMixDestinations", JSONArray(streamMixDestinations.map {
                JSONObject().apply {
                    put("name", it.name)
                    put("host", it.host)
                    put("port", it.port)
                }
            }))
            put("opusArchives", JSONArray(opusArchives.map {
                JSONObject().apply {
                    put("slot", it.slot)
                    put("path", it.path)
                }
            }))
            put("dhcpAutoMode", dhcpAutoMode)
            put("virtualCdjDeviceNumber", virtualCdjDeviceNumber)
        }
        return json.toString(2)
    }

    companion object {
        fun load(path: String): Config {
            val file = File(path)
            if (!file.exists()) return Config()
            return try {
                val json = JSONObject(file.readText())
                Config(
                    outputInterface = json.optString("outputInterface", null).takeUnless { it.isNullOrBlank() },
                    overlayPort = json.optInt("overlayPort", 8090),
                    webGuiPort = json.optInt("webGuiPort", 8081),
                    carabinerPort = json.optInt("carabinerPort", 17000),
                    waveformStyle = json.optString("waveformStyle", "RGB").takeIf { it == "THREE_BAND" } ?: "RGB",
                    destinations = json.optJSONArray("destinations")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val d = arr.getJSONObject(i)
                            DestinationConfig(d.getString("name"), d.getString("host"), d.getInt("port"))
                        }
                    } ?: emptyList(),
                    streamMixDestinations = json.optJSONArray("streamMixDestinations")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val d = arr.getJSONObject(i)
                            DestinationConfig(d.getString("name"), d.getString("host"), d.getInt("port"))
                        }
                    } ?: DEFAULT_STREAM_MIX_DESTINATIONS,
                    opusArchives = json.optJSONArray("opusArchives")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            OpusArchiveConfig(o.getInt("slot"), o.getString("path"))
                        }
                    } ?: emptyList(),
                    dhcpAutoMode = json.optBoolean("dhcpAutoMode", false),
                    virtualCdjDeviceNumber = json.optInt("virtualCdjDeviceNumber", 0),
                )
            } catch (e: Exception) {
                Config()
            }
        }

        fun save(path: String, config: Config) {
            File(path).writeText(config.toJson())
        }
    }
}
