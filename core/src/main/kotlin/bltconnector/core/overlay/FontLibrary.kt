package bltconnector.core.overlay

import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 登録済みフォント1件分。
 * [file] は保管ディレクトリ内の実ファイル名、[name] はCSSの`font-family`に使う表示名。
 */
data class FontEntry(val file: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("file", file)
        put("name", name)
    }
}

/**
 * Overlayで使うフォントの保管庫。[baseDir]`/fonts/` にフォントファイル本体を置き、
 * ファイル名と表示名の対応を同ディレクトリの `fonts.json` に記録する。
 *
 * 表示名はアップロード時にユーザーが入力したものをそのまま使う
 * (フォントファイル内部のname tableは解析しない)。
 */
class FontLibrary(private val baseDir: String) {
    private val logger = LoggerFactory.getLogger(FontLibrary::class.java)

    private fun dir() = File(baseDir, "fonts").apply { mkdirs() }

    private fun listFile() = File(dir(), "fonts.json")

    fun list(): List<FontEntry> {
        val file = listFile()
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                FontEntry(entry.getString("file"), entry.getString("name"))
            }
        } catch (e: Exception) {
            logger.warn("フォント一覧の読み込みに失敗しました", e)
            emptyList()
        }
    }

    fun listAsJson(): JSONArray = JSONArray(list().map { it.toJson() })

    /**
     * アップロードされたファイルをフォントとして登録する。
     * クライアントが送るファイル名は信用せず、先頭のマジックバイトから実際の形式を判定し、
     * こちらで決めたファイル名で保存する。対応形式でなければnullを返す。
     */
    fun register(uploaded: File, displayName: String): FontEntry? {
        val extension = detectExtension(uploaded) ?: return null
        val entry = FontEntry("font-${System.currentTimeMillis()}.$extension", displayName)
        uploaded.copyTo(File(dir(), entry.file), overwrite = true)
        save(list() + entry)
        return entry
    }

    /** 登録済みフォントの実ファイル。保管ディレクトリ外を指すパスや存在しないファイルならnull。 */
    fun fileFor(fileName: String): File? {
        val dir = dir()
        val file = File(dir, fileName)
        if (!file.canonicalPath.startsWith(dir.canonicalPath) || !file.isFile) return null
        return file
    }

    private fun save(entries: List<FontEntry>) {
        listFile().writeText(JSONArray(entries.map { it.toJson() }).toString())
    }

    private fun detectExtension(file: File): String? {
        val magic = file.inputStream().use { it.readNBytes(4) }
        if (magic.size < 4) return null
        return when {
            magic.contentEquals(byteArrayOf(0x77, 0x4f, 0x46, 0x32)) -> "woff2" // "wOF2"
            magic.contentEquals(byteArrayOf(0x77, 0x4f, 0x46, 0x46)) -> "woff" // "wOFF"
            magic.contentEquals(byteArrayOf(0x4f, 0x54, 0x54, 0x4f)) -> "otf" // "OTTO"
            magic.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) -> "ttf"
            else -> null
        }
    }

    companion object {
        fun contentTypeFor(file: File): String = when (file.extension.lowercase()) {
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "otf" -> "font/otf"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}
