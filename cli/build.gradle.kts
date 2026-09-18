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
}

kotlin {
    jvmToolchain(21)
}
