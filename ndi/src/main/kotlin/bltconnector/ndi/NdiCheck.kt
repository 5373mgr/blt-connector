package bltconnector.ndi

import me.walkerknapp.devolay.Devolay
import me.walkerknapp.devolay.DevolayFinder
import me.walkerknapp.devolay.DevolayFrameType
import me.walkerknapp.devolay.DevolayReceiver
import me.walkerknapp.devolay.DevolayVideoFrame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 動作確認用: ネットワーク上のNDIソースを探して受信し、解像度・実測フレームレートを表示して
 * 1フレームをPNGに保存する。`./gradlew :ndi:checkNdi --args="--source=BLT Overlay --out=x.png"`
 */
fun main(args: Array<String>) {
    val opts = args.filter { it.startsWith("--") }.associate {
        it.removePrefix("--").substringBefore("=") to it.substringAfter("=", "")
    }
    val wanted = opts["source"] ?: "BLT Overlay"
    val out = opts["out"] ?: "ndi-check.png"
    val seconds = opts["seconds"]?.toIntOrNull() ?: 5

    Devolay.loadLibraries()
    val finder = DevolayFinder()
    val deadline = System.currentTimeMillis() + 15_000
    var source = finder.currentSources.firstOrNull { it.sourceName.contains(wanted) }
    while (source == null && System.currentTimeMillis() < deadline) {
        finder.waitForSources(1000)
        source = finder.currentSources.firstOrNull { it.sourceName.contains(wanted) }
    }
    if (source == null) {
        println("NDIソースが見つかりません: \"$wanted\" (見つかったソース: ${finder.currentSources.joinToString { it.sourceName }})")
        return
    }
    println("受信します: ${source.sourceName}")

    val receiver = DevolayReceiver(source, DevolayReceiver.ColorFormat.BGRX_BGRA,
        DevolayReceiver.RECEIVE_BANDWIDTH_HIGHEST, false, "blt-ndi-check")
    val frame = DevolayVideoFrame()
    var count = 0
    var saved = false
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < seconds * 1000L) {
        if (receiver.receiveCapture(frame, null, null, 1000) != DevolayFrameType.VIDEO) continue
        count++
        if (!saved) {
            saved = true
            val w = frame.xResolution
            val h = frame.yResolution
            println("解像度: ${w}x$h fourCC=${frame.fourCCType} 宣言fps=${frame.frameRateN}/${frame.frameRateD}")
            val data = frame.data
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * frame.lineStride + x * 4
                val b = data.get(i).toInt() and 0xff
                val g = data.get(i + 1).toInt() and 0xff
                val r = data.get(i + 2).toInt() and 0xff
                val a = data.get(i + 3).toInt() and 0xff
                img.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
            ImageIO.write(img, "png", File(out))
            println("1フレームを保存しました: $out")
        }
    }
    println("実測: ${count}フレーム / ${seconds}秒 = ${"%.1f".format(count.toDouble() / seconds)}fps")
    receiver.close()
    finder.close()
}
