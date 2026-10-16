// HTML(PiのOBS向けオーバーレイ`/`やVJ用モニター画面`/monitor`)をJavaFX WebViewで描画し、NDIで送出するWindows向けツール。
// ResolumeのようにHTMLを直接読み込めないVJソフトへ、オーバーレイをアルファ付きのNDIソースとして渡すために使う。
// NDIの送出にはDevolay(NDI SDKのJavaバインディング)を使い、実体のNDIライブラリは
// 実行PCにインストール済みのNDI Runtime(NDI Tools同梱)を読み込む。
plugins {
    kotlin("jvm")
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

dependencies {
    implementation("me.walkerknapp:devolay:2.1.1")
}

javafx {
    version = "21.0.7"
    // javafx.swingは動作確認用のPNG書き出し(--snapshot)でSwingFXUtilsを使うためだけに必要
    modules = listOf("javafx.controls", "javafx.web", "javafx.swing")
}

application {
    mainClass.set("bltconnector.ndi.MainKt")
}

// 送出中のNDIソースを受信して解像度・フレームレートを確認し、1フレームをPNGに保存する確認用ツール。
// 例: ./gradlew :ndi:checkNdi --args="--source=BLT Overlay --out=ndi-check.png"
tasks.register<JavaExec>("checkNdi") {
    group = "verification"
    description = "送出中のNDIソースを受信して動作確認する"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("bltconnector.ndi.NdiCheckKt")
}

kotlin {
    jvmToolchain(21)
}
