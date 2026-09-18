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
}

kotlin {
    jvmToolchain(21)
}
