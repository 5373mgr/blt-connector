package bltconnector.gui

import bltconnector.core.carabiner.CarabinerBridge
import bltconnector.core.overlay.OverlayServer
import bltconnector.core.receiver.Receiver
import bltconnector.core.sender.Destination
import bltconnector.core.sender.Sender
import org.deepsymmetry.beatlink.data.WaveformFinder
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 最小限の動作確認用GUI。Receiverを起動してデッキ1〜4の状態をポーリングし、
 * 登録した宛先へSender経由でOSC配信する。宛先は手動追加のみ(mDNS自動検出は未実装)。
 *
 * Receiver/Overlay/CarabinerはそれぞれチェックボックスでON/OFFできる。
 * 他端末(例: Raspberry Pi版CLI)が既にReceiverとして動いている場合、この機の
 * Receiverだけを止めておけば「Receiverが2台になる」問題を避けつつ、Overlay等の
 * 単体動作確認ができる。
 */
class Main : Application() {
    private val receiver = Receiver()
    private val sender = Sender()
    private val overlayServer = OverlayServer(port = 8090)
    private val carabinerBridge = CarabinerBridge(port = 17000)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var receiverRunning = false

    override fun start(stage: Stage) {
        val status = Label("Receiver: 停止中 / Overlay: http://localhost:8090/")

        val log = TextArea().apply { isEditable = false }

        val receiverCheckBox = CheckBox("Receiver").apply { isSelected = false }
        val overlayCheckBox = CheckBox("Overlay").apply { isSelected = true }
        val carabinerCheckBox = CheckBox("Carabiner (Ableton Link)").apply { isSelected = true }
        val waveformStyleChoice = ChoiceBox<String>().apply {
            items.addAll("RGB", "3Band")
            value = "RGB"
        }

        try {
            overlayServer.startServer()
        } catch (e: Exception) {
            status.text = "Overlayサーバー起動失敗: ${e.message}"
            overlayCheckBox.isSelected = false
        }
        carabinerBridge.start()

        // 常時ポーリングし、Receiverが動いていなければpollDeckがnullを返すだけなので害はない
        // (Receiverの有効/無効をこのスケジューラ自体の起動/停止と分離できる)。
        scheduler.scheduleAtFixedRate({
            val decks = (1..4).mapNotNull { receiver.pollDeck(it) }
            decks.forEach {
                sender.update(it)
                if (overlayCheckBox.isSelected) overlayServer.update(it)
                if (carabinerCheckBox.isSelected && it.master) carabinerBridge.updateMasterTempo(it.bpm)
            }
            val lines = decks.joinToString("\n") {
                "deck ${it.playerNumber}: ${it.title ?: "-"} / ${it.artist ?: "-"} " +
                    "(${it.positionMs ?: -1}ms, ${"%.1f".format(it.bpm)}bpm${if (it.master) ", MASTER" else ""})"
            }
            Platform.runLater { log.text = lines }
        }, 0, 100, TimeUnit.MILLISECONDS)

        receiverCheckBox.selectedProperty().addListener { _, _, enabled ->
            if (enabled) {
                if (!receiverRunning) {
                    receiverRunning = true
                    status.text = "Receiver: 起動中(CDJ検出待ち)"
                    // beat-linkはCDJが見つかるまで内部で数十秒ブロックし得るため、JavaFXスレッドを
                    // フリーズさせないよう別スレッドで起動する
                    val style = if (waveformStyleChoice.value == "3Band") {
                        WaveformFinder.WaveformStyle.THREE_BAND
                    } else {
                        WaveformFinder.WaveformStyle.RGB
                    }
                    Thread({ receiver.start(style) }, "Receiver Startup").apply { isDaemon = true }.start()
                }
            } else {
                receiverRunning = false
                status.text = "Receiver: 停止中"
                receiver.stop()
            }
        }

        overlayCheckBox.selectedProperty().addListener { _, _, enabled ->
            try {
                if (enabled) overlayServer.startServer() else overlayServer.stopServer()
            } catch (e: Exception) {
                status.text = "Overlay切り替え失敗: ${e.message}"
            }
        }

        carabinerCheckBox.selectedProperty().addListener { _, _, enabled ->
            if (enabled) carabinerBridge.start() else carabinerBridge.stop()
        }

        val destinationInput = TextField().apply { promptText = "host:port (例: 192.168.1.50:9000)" }
        val addDestinationButton = Button("宛先追加")
        val removeDestinationButton = Button("選択削除")
        val destinationList = ListView<String>()
        val destinationsByLabel = mutableMapOf<String, Destination>()

        addDestinationButton.setOnAction {
            val text = destinationInput.text.trim()
            val parts = text.split(":")
            if (parts.size == 2) {
                val port = parts[1].toIntOrNull()
                if (port != null) {
                    try {
                        val destination = Destination(text, InetAddress.getByName(parts[0]), port)
                        sender.addDestination(destination)
                        destinationsByLabel[text] = destination
                        destinationList.items.add(text)
                        destinationInput.clear()
                    } catch (e: Exception) {
                        status.text = "宛先追加失敗: ${e.message}"
                    }
                }
            }
        }

        removeDestinationButton.setOnAction {
            val selected = destinationList.selectionModel.selectedItem ?: return@setOnAction
            destinationsByLabel.remove(selected)?.let { sender.removeDestination(it) }
            destinationList.items.remove(selected)
        }

        val toggleRow = HBox(12.0, receiverCheckBox, overlayCheckBox, carabinerCheckBox, Label("波形:"), waveformStyleChoice)
        val destinationRow = HBox(8.0, destinationInput, addDestinationButton, removeDestinationButton)
        val root = VBox(10.0, status, toggleRow, destinationRow, destinationList, log).apply {
            padding = Insets(16.0)
        }
        stage.scene = Scene(root, 520.0, 480.0)
        stage.title = "BLT Connector"
        stage.setOnCloseRequest {
            scheduler.shutdownNow()
            receiver.stop()
            sender.close()
            overlayServer.stopServer()
            carabinerBridge.stop()
        }
        stage.show()
    }
}

fun main() {
    Application.launch(Main::class.java)
}
