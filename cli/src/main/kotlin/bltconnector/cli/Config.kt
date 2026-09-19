package bltconnector.cli

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** OSC配信先1件分の設定。 */
data class DestinationConfig(val name: String, val host: String, val port: Int)

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
    val destinations: List<DestinationConfig> = emptyList(),
) {
    fun toJson(): String {
        val json = JSONObject().apply {
            put("outputInterface", outputInterface ?: JSONObject.NULL)
            put("overlayPort", overlayPort)
            put("webGuiPort", webGuiPort)
            put("carabinerPort", carabinerPort)
            put("destinations", JSONArray(destinations.map {
                JSONObject().apply {
                    put("name", it.name)
                    put("host", it.host)
                    put("port", it.port)
                }
            }))
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
                    destinations = json.optJSONArray("destinations")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val d = arr.getJSONObject(i)
                            DestinationConfig(d.getString("name"), d.getString("host"), d.getInt("port"))
                        }
                    } ?: emptyList(),
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
