plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    // Receiver.start()の引数やDeckSnapshotのフィールド(WaveformDetail等)にbeat-linkの型を
    // そのまま公開しているため、利用側(cli/gui)からも型解決できるようapi扱いにする。
    api("org.deepsymmetry:beat-link:8.0.0")
    implementation("com.illposed.osc:javaosc-core:0.9")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    // OverlayServerがNanoWSDを継承しているため公開API扱い(api)にする。
    // gui等の利用側がOverlayServer.start()/stop()を呼ぶには親クラスの型解決が必要になるため。
    api("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("org.json:json:20250517")
    // Win/Mac/Linux向けCarabinerバイナリを同梱し、プロセス起動・プロトコル応答パースまで面倒を見てくれる
    implementation("org.deepsymmetry:lib-carabiner:1.2.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
