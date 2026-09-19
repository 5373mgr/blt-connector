plugins {
    kotlin("jvm")
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

dependencies {
    implementation(project(":core"))
}

javafx {
    version = "21.0.7"
    modules = listOf("javafx.controls")
}

application {
    mainClass.set("bltconnector.gui.MainKt")
    // Wi-Fi+イーサネット等、複数NICが同時に有効な環境でVirtualCdjが自分自身のパケットを
    // 別デバイスと誤認し、デバイス番号を奪い合って再起動を繰り返す問題を避けるため、
    // IPv4スタックを強制する(実機CDJで確認済み)。
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

kotlin {
    jvmToolchain(21)
}
