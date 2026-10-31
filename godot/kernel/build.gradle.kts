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
    systemProperty("unciv.saveDirectory", rootProject.file("godot/.local/tests/saves").absolutePath)
    // Godot 闭环会读取这些原格式场景；缺失时应重新执行测试以生成存档。
    outputs.files(rootProject.file("godot/.local/tests/start.json"),
        rootProject.file("godot/.local/tests/settlement-promise.json"),
        rootProject.file("godot/.local/tests/combat.json"),
        rootProject.file("godot/.local/tests/development.json"),
        rootProject.file("godot/.local/tests/development-ui.json"),
        rootProject.file("godot/.local/tests/economy.json"),
        rootProject.file("godot/.local/tests/economy-poor.json"),
        rootProject.file("godot/.local/tests/economy-expected.json"),
        rootProject.file("godot/.local/tests/diplomacy-expected.json"),
        rootProject.file("godot/.local/tests/religion-expected.json"),
        rootProject.file("godot/.local/tests/religion-pantheon.json"),
        rootProject.file("godot/.local/tests/religion-found.json"))
    outputs.files(listOf("peace", "war", "trade-accept", "trade-decline", "trade-dismiss", "trade-mixed",
        "DeclarationOfFriendship", "DemandToStopSettlingCitiesNear", "DemandToNotAttackUs", "Denounced",
        "DemandToStopSpreadingReligion", "BulliedProtectedMinor", "AttackedProtectedMinor", "AttackedAllyMinor",
        "DemandToStopSpyingOnUs", "SpyingOnUsDespiteOurPromise")
        .map { rootProject.file("godot/.local/tests/diplomacy-$it.json") })
    outputs.files(listOf("expected", "liberty", "maya", "mixed", "blocked", "admiral-coastal", "admiral-inland")
        .map { rootProject.file("godot/.local/tests/great-person-$it.json") })
    outputs.files(listOf("expected", "choices", "natural", "abstain", "player-win", "ai-win", "results-edge-tie",
        "results-edge-abstain", "results-edge-empty", "results-edge-dead", "results-edge-hidden")
        .map { rootProject.file("godot/.local/tests/diplomatic-vote-$it.json") })
    outputs.files(listOf("expected", "civilian-worker", "civilian-settler", "civilian-city-state", "traded", "marriage", "marriage-occ", "expired", "queue")
        .map { rootProject.file("godot/.local/tests/asset-$it.json") })
    outputs.files(listOf("expected", "progress", "won", "lost", "defeated", "conquest")
        .map { rootProject.file("godot/.local/tests/victory-$it.json") })
    outputs.dir(rootProject.file("godot/.local/tests/naval-native"))
    outputs.files(listOf("expected", "battle", "conquest", "embarkation")
        .map { rootProject.file("godot/.local/tests/naval-$it.json") })
    outputs.dir(rootProject.file("godot/.local/tests/unitcity-native"))
    outputs.files(listOf("expected", "promotion", "upgrade", "bombard", "workboat")
        .map { rootProject.file("godot/.local/tests/unitcity-$it.json") })
    outputs.files(listOf("information", "information-free", "information-expected")
        .map { rootProject.file("godot/.local/tests/$it.json") })
    outputs.file(rootProject.file("godot/.local/tests/endgame-expected.json"))
    outputs.dir(rootProject.file("godot/.local/tests/whole-game"))
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
