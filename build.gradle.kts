plugins {
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

// プロジェクトパスに非ASCII文字(このリポジトリ名の「個人開発」等)が含まれると、
// GradleのTestワーカーのクラスローディングがWindows上でClassNotFoundExceptionを起こす既知の問題を回避するため、
// ビルド出力先をOSの一時ディレクトリ配下(ASCIIパス)に逃がす。コンパイル/実行自体はプロジェクトパスのままで問題ない。
subprojects {
    layout.buildDirectory.set(
        File(System.getProperty("java.io.tmpdir"), "blt-connector-build/${project.name}")
    )
}
