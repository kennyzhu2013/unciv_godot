package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*

/** 开局选项仅适配原生参数；不接受任意规则、Mod、联机或自定义超大地图。 */
internal object NewGameSetup {
    private val sizes = listOf("Tiny", "Small", "Medium")
    private fun ruleset() = RulesetCache.getComplexRuleset(GameParameters())
    private fun nations() = ruleset().nations.values.filter {
        it.isMajorCiv && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames)
    }.map { it.name }.sorted()

    fun options(): JsonObject {
        val rules = ruleset()
        return dto(
            "nations" to listOf(Constants.random) + nations(),
            "difficulties" to rules.difficulties.keys.toList(),
            "speeds" to rules.speeds.keys.toList(),
            "mapSizes" to sizes, "mapTypes" to MapType.allValues,
            "maxAiPlayers" to minOf(7, nations().size - 1),
            "maxCityStates" to minOf(12, rules.nations.values.count {
                it.isCityState && !it.hasUnique(UniqueType.WillNotBeChosenForNewGames)
            }),
            "defaults" to dto("nation" to Constants.random, "difficulty" to "Prince", "speed" to GameParameters().speed,
                "mapSize" to "Medium", "mapType" to MapType.pangaea, "aiPlayers" to 3, "cityStates" to 6,
                "barbarians" to true, "ruins" to true, "seed" to ""),
            "baseRuleset" to GameParameters().baseRuleset,
            "startingEra" to GameParameters().startingEra,
            "victories" to rules.selectableVictories().map { it.name }, "worldWrap" to false)
    }

    fun parse(request: JsonObject): GameSetupInfo {
        val data = request["setup"] as? JsonObject ?: throw GatewayError("GAME_SETUP", "缺少开局配置")
        val options = options()
        val defaults = options["defaults"]!!.jsonObject
        fun value(key: String) = data[key] ?: defaults[key]!!
        fun choice(key: String, choices: String): String {
            val entry = value(key) as? JsonPrimitive
            val name = entry?.takeIf { it.isString }?.content
            if (name == null || options[choices]!!.jsonArray.none { it.jsonPrimitive.content == name })
                throw GatewayError("GAME_SETUP", "无效的开局选项：$key")
            return name
        }
        fun count(key: String, min: Int, max: Int): Int {
            val entry = value(key) as? JsonPrimitive
            val number = entry?.takeUnless { it.isString }?.intOrNull
            if (number == null || number !in min..max) throw GatewayError("GAME_SETUP", "$key 必须在 $min 到 $max 之间")
            return number
        }
        fun enabled(key: String): Boolean {
            val entry = value(key) as? JsonPrimitive
            return entry?.takeUnless { it.isString }?.booleanOrNull
                ?: throw GatewayError("GAME_SETUP", "$key 必须为布尔值")
        }
        if (data.keys.any { it !in defaults.keys }) throw GatewayError("GAME_SETUP", "开局配置包含尚未接入的选项")
        val nation = choice("nation", "nations")
        val parameters = GameParameters().apply {
            difficulty = choice("difficulty", "difficulties")
            speed = choice("speed", "speeds")
            players = arrayListOf(Player(nation, PlayerType.Human))
            repeat(count("aiPlayers", 1, options.integer("maxAiPlayers"))) { players.add(Player()) }
            numberOfCityStates = count("cityStates", 0, options.integer("maxCityStates"))
            noBarbarians = !enabled("barbarians")
            // 与原版 NewGameScreen 初始化相同，不能沿用演示局的空胜利条件。
            victoryTypes = ArrayList(ruleset().selectableVictories().map { it.name })
        }
        val map = MapParameters().apply {
            mapSize = MapSize(choice("mapSize", "mapSizes"))
            type = choice("mapType", "mapTypes")
            noRuins = !enabled("ruins")
            val seedValue = value("seed") as? JsonPrimitive
            if (seedValue == null || !seedValue.isString) throw GatewayError("GAME_SETUP", "地图种子须为整数文本或留空")
            if (seedValue.content.isNotBlank()) seed = seedValue.content.toLongOrNull()
                ?: throw GatewayError("GAME_SETUP", "地图种子超出 64 位整数范围")
        }
        return GameSetupInfo(parameters, map)
    }
}
