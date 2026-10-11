// beat-linkのフォーク(third_party/beat-link、gitサブモジュール)をGradleでビルドするためのラッパー。
// 本家はMavenでビルドするが、このリポジトリをクローンしてJDKさえあれば`./gradlew build`だけで
// 済むよう、ソースを直接コンパイルする。依存関係は本家pom.xml(v8.0.0)に合わせている。
// フォークの変更内容・経緯は docs/testing.md の「beat-linkのフォーク」節を参照。
plugins {
    `java-library`
}

val forkDir = rootProject.layout.projectDirectory.dir("third_party/beat-link")

sourceSets {
    main {
        java.setSrcDirs(listOf(forkDir.dir("src/main/java")))
        resources.setSrcDirs(emptyList<Any>())
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    // 本家ソース由来の警告(deprecation等)はここでは扱わない
    options.isWarnings = false
}

dependencies {
    api("org.deepsymmetry:electro:0.1.4")
    api("org.deepsymmetry:crate-digger:0.2.1")
    implementation("io.github.willena:sqlite-jdbc:3.49.0.0")
    implementation("io.kaitai:kaitai-struct-runtime:0.10")
    api("org.slf4j:slf4j-api:2.0.17")
    api("org.apiguardian:apiguardian-api:1.1.2")
}
