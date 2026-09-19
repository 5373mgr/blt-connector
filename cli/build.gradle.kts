plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20250517")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass.set("bltconnector.cli.MainKt")
    // Wi-Fi+イーサネット等、複数NICが同時に有効な環境でVirtualCdjが自分自身のパケットを
    // 別デバイスと誤認し、デバイス番号を奪い合って再起動を繰り返す問題を避けるため、
    // IPv4スタックを強制する(実機CDJで確認済み)。
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

kotlin {
    jvmToolchain(21)
}
