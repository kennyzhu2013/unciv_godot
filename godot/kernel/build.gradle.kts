import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
    application
}

kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_1_8
java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx)
    implementation(libs.gdx.backend.headless)
    implementation(libs.ktor.serialization)
    testImplementation(libs.junit)
}

application.mainClass.set("com.unciv.godot.GatewayMainKt")

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("android/assets")
    args("--root", rootProject.projectDir.absolutePath)
}

tasks.test {
    workingDir = rootProject.file("android/assets")
    systemProperty("unciv.root", rootProject.projectDir.absolutePath)
    // Godot 闭环会读取这些原格式场景；缺失时应重新执行测试以生成存档。
    outputs.files(rootProject.file("godot/.local/tests/start.json"),
        rootProject.file("godot/.local/tests/settlement-promise.json"),
        rootProject.file("godot/.local/tests/combat.json"),
        rootProject.file("godot/.local/tests/development.json"),
        rootProject.file("godot/.local/tests/development-ui.json"),
        rootProject.file("godot/.local/tests/economy.json"),
        rootProject.file("godot/.local/tests/economy-poor.json"),
        rootProject.file("godot/.local/tests/economy-expected.json"),
        rootProject.file("godot/.local/tests/diplomacy-expected.json"))
    outputs.files(listOf("peace", "war", "trade-accept", "trade-decline", "trade-dismiss", "trade-mixed",
        "DeclarationOfFriendship", "DemandToStopSettlingCitiesNear", "DemandToNotAttackUs", "Denounced")
        .map { rootProject.file("godot/.local/tests/diplomacy-$it.json") })
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
