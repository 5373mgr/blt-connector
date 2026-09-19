package bltconnector.core.overlay

import org.json.JSONObject
import java.io.File

/**
 * Overlayのレイアウト設定(要素の位置・サイズ・フォント・テンプレート文字列)の保存先。
 * [baseDir]`/overlay-layout.json` に保存し、未保存ならデフォルトレイアウトを返す。
 *
 * レイアウトの中身はエディタ(`/editor`)と表示側(`/`)だけが解釈するため、
 * ここではJSONとして妥当かどうかだけを検証してそのまま読み書きする。
 */
class LayoutStore(private val baseDir: String) {

    private fun file() = File(baseDir, "overlay-layout.json")

    /** 保存済みのレイアウトJSON。未保存ならデフォルトを返す。 */
    fun load(): String {
        val file = file()
        return if (file.isFile) file.readText() else DEFAULT_LAYOUT_JSON
    }

    /** レイアウトJSONを保存する。JSONとして解釈できなければfalseを返し、保存しない。 */
    fun save(json: String): Boolean {
        try {
            JSONObject(json)
        } catch (e: Exception) {
            return false
        }
        File(baseDir).mkdirs()
        file().writeText(json)
        return true
    }

    private companion object {
        /** 左下にオンエア中の曲(ジャケット/曲名/アーティスト/アルバム/コメント)を並べた初期配置。 */
        val DEFAULT_LAYOUT_JSON = """
            {"elements":[
              {"id":"art1","type":"image","variable":"onair","x":2,"y":68,"width":12,"height":21,"visible":true},
              {"id":"text1","type":"text","template":"{onair-track-name}","x":15,"y":70,"width":50,"height":8,"fontFamily":"sans-serif","fontSize":28,"color":"#ffffff","visible":true},
              {"id":"text2","type":"text","template":"{onair-artist-name}","x":15,"y":78,"width":50,"height":6,"fontFamily":"sans-serif","fontSize":18,"color":"#cccccc","visible":true},
              {"id":"text3","type":"text","template":"{onair-album-name}","x":15,"y":85,"width":50,"height":6,"fontFamily":"sans-serif","fontSize":16,"color":"#aaaaaa","visible":true},
              {"id":"text4","type":"text","template":"{onair-comment}","x":15,"y":91,"width":50,"height":6,"fontFamily":"sans-serif","fontSize":16,"color":"#9fe870","visible":true}
            ]}
        """.trimIndent()
    }
}
