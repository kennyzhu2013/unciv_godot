package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import com.unciv.view.CityCombatantView
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import java.io.File

/**
 * 单位晋升／升级、城市轰击与工作船水上改良的原格式场景。
 * 期望重放只调用 core 原生入口（UnitPromotions、UnitActionsUpgrade、ForeignCityView.tryBombard、
 * UnitActions 的 CreateImprovement 映射），不使用网关候选或准备动作。
 */
internal object UnitCityActionsFixtures {
    val root get() = BattleFixtures.root
    fun game() = NavalFixtures.game()
    fun copy(game: GameInfo) = NavalFixtures.copy(game)
    fun unit(game: GameInfo, id: Int): MapUnit = game.currentPlayerCiv.units.getCivUnits().first { it.id == id }
    fun export(game: GameInfo, name: String) = GreatPersonFixtures.export(game, "unitcity-$name")

    /** 陆军 Shock I 与海军 Boarding Party I 各一次逐级晋升；XP 恰好够第一次。 */
    fun promotion(): GameInfo = game().also { game -> native(game) {
        add(game, "Warrior", 1, 0).promotions.XP = 15
        add(game, "Trireme", 1, -1).promotions.XP = 15
        NavalFixtures.ready(game)
    } }

    /** 三桅帆船正常付费升级为快帆船；勇士因未研究铁器没有可执行升级。升级要求己方领土，海岸格先入首都边界。 */
    fun upgrade(): GameInfo = game().also { game -> native(game) {
        game.civilizations.forEach { NavalFixtures.research(game, "Astronomy", it) }
        add(game, "Warrior", 1, 0)
        game.currentPlayerCiv.cities.first().expansion.takeOwnership(game.tileMap[HexCoord(1, -1)])
        add(game, "Trireme", 1, -1)
        NavalFixtures.ready(game)
    } }

    /** 己方首都轰击相邻敌方勇士（置 1 HP 保证击杀）。 */
    fun bombard(): GameInfo = game().also { game -> native(game) {
        add(game, "Warrior", 1, 0, enemy(game)).health = 1
        NavalFixtures.ready(game)
    } }

    /** 工作船停在己方领土内带 Fish 资源的海岸格，可立即创建 Fishing Boats（改良不能建在边界外）。 */
    fun workboat(): GameInfo = game().also { game -> native(game) {
        val tile = game.tileMap[HexCoord(1, -1)]
        tile.tileResource = game.ruleset.tileResources["Fish"]!!
        tile.setTerrainTransients()
        game.currentPlayerCiv.cities.first().expansion.takeOwnership(tile)
        add(game, "Work Boats", 1, -1)
        NavalFixtures.ready(game)
    } }

    /** 己方首都已占为傀儡：与占城 select puppet 后的状态一致，可随时吞并。 */
    fun annexPuppet(): GameInfo = game().also { game -> native(game) {
        game.currentPlayerCiv.cities.first().apply {
            isPuppet = true
            cityConstructions.constructionQueue = arrayListOf()
        }
        NavalFixtures.ready(game)
    } }

    /** 己方首都处于焚毁中：与占城 raze 后 isBeingRazed=true 的状态一致。 */
    fun stopRazing(): GameInfo = game().also { game -> native(game) {
        game.currentPlayerCiv.cities.first().isBeingRazed = true
        NavalFixtures.ready(game)
    } }

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
            "promote" -> {
                val unit = unit(game, params.integer("unitId"))
                val name = params.text("promotion")
                // 复刻 PromotionPickerScreen 的整体门槛与逐项费用检查；执行只走原生 addPromotion。
                check(unit.promotions.canBePromoted() && unit.hasMovement() && unit.attacksThisTurn == 0)
                val promotion = unit.promotions.getAvailablePromotions().first { it.name == name }
                check(promotion.hasUnique(UniqueType.FreePromotion) || unit.promotions.XP >= unit.promotions.xpForNextPromotion())
                unit.promotions.addPromotion(name)
            }
            "upgrade" -> {
                val unit = unit(game, params.integer("unitId"))
                val target = params.text("targetUnit")
                val upgrade = UnitActionsUpgrade.getUpgradeActions(unit)
                    .filterIsInstance<UpgradeUnitAction>().first { it.unitToUpgradeTo.name == target }
                check(upgrade.action != null)
                upgrade.action!!.invoke()
            }
            "bombard" -> {
                val view = GameView(game, game.currentPlayerCiv)
                val city = game.currentPlayerCiv.cities.first { it.id == params.text("cityId") }
                val cityView = view.getCityView(city)
                check(cityView.canBombard())
                val tile = cityView.getBombardableTiles()
                    .first { it.position() == HexCoord(params.integer("x"), params.integer("y")) }
                val defender = tile.getCombatant()
                check(defender != null && defender !is CityCombatantView)
                cityView.tryBombard((cityView.asCombatant()).buildBombardAttackableTile(tile, defender, game.currentPlayerCiv, false, view))
            }
            "createWaterImprovement" -> {
                val unit = unit(game, params.integer("unitId"))
                val name = params.text("name")
                check(unit.cache.hasUniqueToCreateWaterImprovements)
                val tile = unit.currentTile
                check(tile.tileResource?.getImprovingImprovement(tile, unit.cache.state)?.name == name)
                // CreateImprovement 的映射入口无 GUI；水上资源动作以原生标题模板 "Create [X]" 区分。
                val waterAction = UnitActions.getUnitActions(unit, UnitActionType.CreateImprovement)
                    .first { it.title == "Create [$name]" }
                check(waterAction.action != null)
                waterAction.action!!.invoke()
            }
            "annexPuppet" -> {
                val city = game.currentPlayerCiv.cities.first { it.id == params.text("cityId") }
                check(city.isPuppet && !game.currentPlayerCiv.hasUnique(UniqueType.MayNotAnnexCities))
                city.annexCity()
            }
            "stopRazing" -> {
                val city = game.currentPlayerCiv.cities.first { it.id == params.text("cityId") }
                check(city.isBeingRazed)
                city.isBeingRazed = false
            }
            else -> error("未知原生重放操作：$action")
        }
        VictoryFixtures.screenUpdate(game)
        game
    }

    fun emit() {
        val scenarios = arrayListOf<JsonObject>()
        fun record(name: String, initial: GameInfo, steps: List<Pair<String, JsonObject>>) {
            val file = export(initial, name)
            var expected = copy(initial)
            VictoryFixtures.screenUpdate(expected)
            val states = linkedMapOf("initial" to GameplayAssertions.gameplay(expected))
            val rawStates = linkedMapOf<String, String>()
            fun raw(key: String) {
                val file = File(root, "godot/.local/tests/unitcity-native/$name-$key.json")
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
            scenarios.add(dto("name" to name, "fixture" to file.absolutePath, "steps" to actions,
                "states" to dto(*states.toList().toTypedArray()), "rawStates" to dto(*rawStates.toList().toTypedArray())))
        }
        val promotion = promotion()
        val warrior = promotion.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" }
        val trireme = promotion.currentPlayerCiv.units.getCivUnits().first { it.name == "Trireme" }
        record("promotion", promotion, listOf(
            "promote" to dto("unitId" to warrior.id, "promotion" to "Shock I"),
            "reload" to dto(),
            "promote" to dto("unitId" to trireme.id, "promotion" to "Boarding Party I"),
            "nextTurn" to dto()))
        val upgrade = upgrade()
        val ship = upgrade.currentPlayerCiv.units.getCivUnits().first { it.name == "Trireme" }
        record("upgrade", upgrade, listOf(
            "upgrade" to dto("unitId" to ship.id, "targetUnit" to "Caravel"),
            "reload" to dto(), "nextTurn" to dto()))
        val bombard = bombard()
        record("bombard", bombard, listOf(
            "bombard" to dto("cityId" to bombard.currentPlayerCiv.cities.first().id, "x" to 1, "y" to 0),
            "reload" to dto(), "nextTurn" to dto()))
        val workboat = workboat()
        val boat = workboat.currentPlayerCiv.units.getCivUnits().first { it.name == "Work Boats" }
        record("workboat", workboat, listOf(
            "createWaterImprovement" to dto("unitId" to boat.id, "name" to "Fishing Boats"),
            "reload" to dto(), "nextTurn" to dto()))
        val puppet = annexPuppet()
        record("annex-puppet", puppet, listOf(
            "annexPuppet" to dto("cityId" to puppet.currentPlayerCiv.cities.first().id),
            "reload" to dto(), "nextTurn" to dto()))
        val razing = stopRazing()
        record("stop-razing", razing, listOf(
            "stopRazing" to dto("cityId" to razing.currentPlayerCiv.cities.first().id),
            "reload" to dto(), "nextTurn" to dto()))
        File(root, "godot/.local/tests/unitcity-expected.json").writeText(dto("scenarios" to scenarios,
            "setFields" to GameplayAssertions.setFields.toList(), "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString())
    }
}
