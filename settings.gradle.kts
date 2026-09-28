import java.util.Properties

// Kotlin internal 成员的 JVM 名携带模块名（如 foundReligion$Unciv_core），模块名默认取根工程名，
// 而根工程名默认取目录名。godot/kernel 的 Java 桥接依赖该名称，必须固定，不能随克隆目录漂移。
rootProject.name = "Unciv"

pluginManagement {
    repositories {
        mavenLocal() // To get the compiler plugin locally
        gradlePluginPortal() // So other plugins can be resolved
    }
}

include("desktop", "core", "tests", "server", "godot-kernel")
project(":godot-kernel").projectDir = file("godot/kernel")

private fun getSdkPath(): String? {
    val localProperties = file("local.properties")
    return if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { properties.load(it) }

        properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    } else {
        System.getenv("ANDROID_HOME")
    }
}
if (getSdkPath() != null) include("android")
