package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlinx.serialization.json.*
import java.io.File

/** 海岸与远洋原格式场景；期望重放只调用 core，不使用网关候选或准备动作。 */
internal object NavalFixtures {
    val root get() = BattleFixtures.root
    fun research(game: GameInfo, name: String, civ: Civilization = game.currentPlayerCiv) {
        val tech = civ.tech
        if (tech.isResearched(name)) return
        game.ruleset.technologies[name]!!.prerequisites.forEach { research(game, it, civ) }
        tech.addTechnology(name)
    }
    fun game(rules: BaseRuleset = BaseRuleset.Civ_V_GnK, technology: String? = "Optics",
             nation: String = "Rome"): GameInfo = GreatPersonFixtures.game(nation, rules, free = 0).also { game -> native(game) {
        game.gameParameters.victoryTypes = arrayListOf("Domination")
        for (tile in game.tileMap.tileList) {
            tile.baseTerrain = when { tile.position.y < -1 -> "Ocean"; tile.position.y < 0 -> "Coast"; else -> "Grassland" }
            tile.setTerrainTransients()
        }
        if (technology != null) game.civilizations.forEach { research(game, technology, it) }
        if (!game.currentPlayerCiv.knows(enemy(game))) game.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(enemy(game))
        game.currentPlayerCiv.getDiplomacyManager(enemy(game))!!.declareWar()
        game.civilizations.forEach { civ ->
            civ.addGold(1000)
            civ.cities.forEach { it.avoidGrowth = true }
        }
        game.setTransients()
        clean(game)
    } }
    fun clean(game: GameInfo) {
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear(); it.tradeRequests.clear() }
    }
    fun ready(game: GameInfo): GameInfo = game.also { native(it) { it.setTransients(); clean(it) } }
    fun export(game: GameInfo, name: String) = GreatPersonFixtures.export(game, "naval-$name")
    fun copy(game: GameInfo) = GreatPersonFixtures.copy(game)
    fun unit(game: GameInfo, id: Int) = game.currentPlayerCiv.units.getCivUnits().first { it.id == id }
    fun attack(game: GameInfo, id: Int, x: Int, y: Int): Battle.DamageDealt = native(game) {
        val unit = unit(game, id)
        val target = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .first { it.tileToAttack.position == HexCoord(x, y) }
        val attacker = MapUnitCombatant(unit)
        if (Battle.movePreparingAttack(attacker, target, false)) Battle.attackOrNuke(attacker, target)
        else Battle.DamageDealt.None
    }
    fun apply(original: GameInfo, action: String, params: JsonObject = dto()): GameInfo = native(original) {
        var game = original
        when (action) {
            "reload" -> game = copy(game)
            "nextTurn" -> game = GreatPersonFixtures.nextTurn(game)
            "move" -> {
                val unit = unit(game, params.integer("unitId"))
                unit.action = null
                unit.movement.moveToTile(game.tileMap[params.integer("x"), params.integer("y")])
            }
            "attack" -> attack(game, params.integer("unitId"), params.integer("x"), params.integer("y"))
            "unitAction" -> {
                val type = when (params.text("type")) {
                    "skip" -> UnitActionType.Skip
                    "sleep" -> UnitActionType.Sleep
                    "sleepUntilHealed" -> UnitActionType.SleepUntilHealed
                    else -> error("未列入原生重放的基础动作")
                }
                UnitActions.getBasicActions(unit(game, params.integer("unitId"))).first { it.type == type }.action!!.invoke()
            }
            "cityDecision" -> {
                val alert = game.currentPlayerCiv.popupAlerts.first()
                check(alert.type == AlertType.CityConquered && alert.value == params.text("cityId"))
                game.getCities().first { it.id == alert.value }.puppetCity(game.currentPlayerCiv)
                game.currentPlayerCiv.popupAlerts.remove(alert)
            }
            "victoryContinue" -> game.oneMoreTurnMode = true
            "acknowledge" -> game.currentPlayerCiv.popupAlerts.removeAt(0)
            else -> error("未知原生重放操作：$action")
        }
        VictoryFixtures.screenUpdate(game)
        game
    }

    fun duel(attacker: String = "Trireme", defender: String = "Trireme", health: Int = 100,
             rules: BaseRuleset = BaseRuleset.Civ_V_GnK): GameInfo = game(rules).also { game -> native(game) {
        add(game, attacker, 0, -1)
        add(game, defender, 1, -1, enemy(game)).health = health
        ready(game)
    } }
    fun conquest(): GameInfo = game().also { game -> native(game) {
        add(game, "Trireme", -6, -1)
        enemy(game).cities.first().health = 1
        ready(game)
    } }
    fun embarkation(): GameInfo = game(nation = "Denmark").also { game -> native(game) {
        add(game, "Warrior", 2, 0)
        add(game, "Worker", 2, 0)
        ready(game)
    } }

    fun emit() {
        val scenarios = arrayListOf<JsonObject>()
        fun record(name: String, initial: GameInfo, steps: List<Pair<String, JsonObject>>) {
            val file = export(initial, name)
            var expected = copy(initial)
            VictoryFixtures.screenUpdate(expected)
            val states = linkedMapOf("initial" to GameplayAssertions.gameplay(expected))
            val rawStates = linkedMapOf<String, String>()
            fun raw(key: String) {
                val file = File(root, "godot/.local/tests/naval-native/$name-$key.json")
                file.parentFile.mkdirs()
                file.writeText(com.unciv.logic.files.UncivFiles.gameInfoToString(expected, false))
                rawStates[key] = file.absolutePath
            }
            raw("initial")
            val actions = steps.mapIndexed { index, (action, params) ->
                expected = apply(expected, action, params)
                val key = "${index + 1}-$action"
                states[key] = GameplayAssertions.gameplay(expected)
                raw(key)
                dto("action" to action, "params" to params, "step" to key)
            }
            scenarios.add(dto("name" to name, "fixture" to file.absolutePath, "steps" to actions, "states" to dto(*states.toList().toTypedArray()), "rawStates" to dto(*rawStates.toList().toTypedArray())))
        }
        val battle = duel(health = 1)
        val ship = battle.currentPlayerCiv.units.getCivUnits().first().id
        record("battle", battle, listOf("move" to dto("unitId" to ship, "x" to -1, "y" to -1),
            "attack" to dto("unitId" to ship, "x" to 1, "y" to -1), "reload" to dto()))
        val conquest = conquest()
        record("conquest", conquest, listOf(
            "attack" to dto("unitId" to conquest.currentPlayerCiv.units.getCivUnits().first().id, "x" to -6, "y" to 0),
            "cityDecision" to dto("cityId" to enemy(conquest).cities.first().id, "choice" to "puppet"),
            "victoryContinue" to dto(), "acknowledge" to dto(), "nextTurn" to dto(), "reload" to dto()))
        // 构造器会重建 transient；按原格式重载后的单位列表生成指令，避免重复登记同一单位。
        val embark = copy(embarkation())
        check(embark.currentPlayerCiv.units.getCivUnits().map { it.id }.toList().let { it.size == 2 && it.toSet().size == 2 })
        val orders = arrayListOf<Pair<String, JsonObject>>()
        for (unit in embark.currentPlayerCiv.units.getCivUnits())
            orders.add("move" to dto("unitId" to unit.id, "x" to 2, "y" to -1))
        orders.add("nextTurn" to dto())
        for (unit in embark.currentPlayerCiv.units.getCivUnits())
            orders.add("move" to dto("unitId" to unit.id, "x" to 3, "y" to 0))
        orders.add("reload" to dto())
        orders.add("nextTurn" to dto())
        record("embarkation", embark, orders)
        File(root, "godot/.local/tests/naval-expected.json").writeText(dto("scenarios" to scenarios,
            "setFields" to GameplayAssertions.setFields.toList(), "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString())
    }
}
