package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import kotlinx.serialization.json.*
import org.junit.Assert.*
import java.io.File

/** 共用完整存档差分；严格保留既有归一化边界，不屏蔽战斗、归属或外交数据。 */
internal object GameplayAssertions {
    /**
     * 比较原存档持久化状态（含 AI、科技、政策、外交、建筑、地块），仅排除实际计时与写盘时才重算的校验和。
     * 以下差异在原引擎内本身就不确定（两份独立加载的原生内核各推进一回合也不同），只对它们做归一化：
     * - 文明 notifications：来源 viewableTiles 是 HashSet<Tile>，Tile 未重写 hashCode，"敌军出现"通知顺序不稳定，按内容排序；
     * - 原引擎以 HashSet 存储、序列化为数组的集合字段（见 [setFields]/[mapOfSetFields]，均已在 core 中核对为 HashSet）：
     *   独立加载及回合 clone() 重建的集合可能有不同迭代顺序，按内容排序；
     * - 伟人姓名：UniqueTriggerActivation 用未播种的 shuffled() 抽名，出生的单位、id、归属都一致，仅名字随机。
     *   unitNamesTaken 是按出生顺序追加的列表，用其下标把名字替换为固定记号（名字同时进入 instanceName、
     *   同名晋升、unitNamesTaken 与通知文本中的 [name]），即使该伟人随后被消耗也能对上。
     * 生产队列、科研队列、历史等有序列表以及未列出的任何数组仍严格按顺序比较。
     */
    fun gameplay(game: GameInfo): JsonObject {
        var text = UncivFiles.gameInfoToString(game, false)
        assertEquals("单位姓名应互不相同", game.unitNamesTaken.size, game.unitNamesTaken.toSet().size)
        for ((index, name) in game.unitNamesTaken.withIndex()) {
            text = text.replace("\"$name\"", "\"<named unit $index>\"").replace("[$name]", "[<named unit $index>]")
        }
        val state = Json.parseToJsonElement(text).jsonObject
        val civilizations = state["civilizations"]!!.jsonArray.map { JsonObject(it.jsonObject - "totalTurnTimeSeconds") }
        return normalizeSets(JsonObject(state - "currentTurnStartTime" - "checksum" + ("civilizations" to JsonArray(civilizations))), null).jsonObject
    }

    /** 序列化为数组的 HashSet 字段：TechManager、PolicyManager、City、CityConstructions、UnitPromotions、Civilization、GameInfo、战报等。 */
    internal val setFields = setOf("notifications", "techsResearched", "adoptedPolicies", "builtBuildings", "disabledConstructions",
        "disabledCityConstructions", "promotions", "spaceResources", "longCountGPPool", "civIdsThatKnowMe",
        "civIdsKnowingAttackSource", "civIdsKnowingAttackTarget", "religionsAtSomePointAdopted", "exploredBy",
        "passableImpassables", "techsToSteal", "tiles", "workedTiles", "lockedTiles", "neutralRoads")
    /** HashMap<String, HashSet<String>> 字段：CivConstructions 与 CityConstructions 的免费建筑记录。 */
    internal val mapOfSetFields = setOf("freeBuildings", "freeStatBuildingsProvided", "freeSpecificBuildingsProvided",
        "freeBuildingsProvidedFromThisCity")

    private fun normalizeSets(element: JsonElement, key: String?): JsonElement = when {
        element is JsonObject && key in mapOfSetFields -> JsonObject(element.mapValues { sortedArray(it.value) })
        element is JsonObject -> JsonObject(element.mapValues { normalizeSets(it.value, it.key) })
        element is JsonArray -> JsonArray(element.map { normalizeSets(it, null) }).let { if (key in setFields) sortedArray(it) else it }
        else -> element
    }

    private fun sortedArray(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonArray(element.sortedBy { it.toString() }) else element

    fun assertGameplayEquals(step: String, expected: GameInfo, actual: GameInfo) {
        val expectedJson = gameplay(expected)
        val actualJson = gameplay(actual)
        try {
            assertJsonEquals(step, expectedJson, actualJson)
        } catch (error: AssertionError) {
            // 失败时把两端归一化状态落盘，便于对照完整上下文而不只是首个差异路径。
            val dir = File(System.getProperty("unciv.root"), "godot/.local/tests/diff").apply { mkdirs() }
            File(dir, "expected-native.json").writeText(expectedJson.toString())
            File(dir, "actual-gateway.json").writeText(actualJson.toString())
            throw error
        }
    }

    /** 首个差异给出具体字段路径，避免整份地图掩盖失败原因；保留队列和历史的顺序。 */
    private fun assertJsonEquals(path: String, expected: JsonElement, actual: JsonElement) {
        if (expected == actual) return
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals("$path 字段", expected.keys, actual.keys)
                for (key in expected.keys) assertJsonEquals("$path.$key", expected[key]!!, actual[key]!!)
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals("$path 长度", expected.size, actual.size)
                for (index in expected.indices) assertJsonEquals("$path[$index]", expected[index], actual[index])
            }
            else -> assertEquals(path, expected, actual)
        }
    }
}
