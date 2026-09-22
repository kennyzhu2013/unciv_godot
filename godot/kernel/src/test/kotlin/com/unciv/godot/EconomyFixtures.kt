package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.view.GameView
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.roundToInt

/** 经济测试的原格式场景和独立原生预期，不向网关提供作弊命令。 */
internal object EconomyFixtures {
    val root get() = DevelopmentFixtures.root
    val target = HexCoord(2, 0)
    val secondTarget = HexCoord(3, 0)
    fun capital(game: GameInfo) = game.currentPlayerCiv.cities.first { it.location == DevelopmentFixtures.center }

    fun game(): GameInfo = DevelopmentFixtures.game().also { game ->
        BattleFixtures.native(game) {
            val player = game.currentPlayerCiv
            val city = capital(game)
            city.getTiles().filter { it.aerialDistanceTo(city.getCenterTile()) >= 2 }.toList()
                .forEach { city.expansion.relinquishOwnership(it) }
            player.addGold(2000 - player.gold)
            player.addCity(DevelopmentFixtures.secondCity).cityConstructions.constructionQueue = arrayListOf("Nothing")
            city.cityConstructions.constructionQueue = arrayListOf("Warrior", "Worker", "Warrior", "Nothing")
            city.cityConstructions.currentConstructionIsUserSet = true
            val cv = GameView(game, player).civView.cities().first { it.id == city.id }
            city.cityConstructions.addBuilding(game.ruleset.buildings["Bank"]!!)
            cv.tryEnableManualSpecialists()
            city.getWorkedTiles().filter { !it.isCityCenter() }.take(2).toList().forEach { city.stopWorkingTile(it) }
            check(cv.tryAssignSpecialist("Merchant"))
            check(cv.tryAssignSpecialist("Merchant"))
            city.cityStats.update()
            // 侦察员提供下一格可见性，城中心两个单位槽位保持空闲。
            BattleFixtures.add(game, "Scout", 2, 1)
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            game.setTransients()
            player.cities.forEach { it.cityStats.update() }
        }
    }

    fun file(name: String = "economy", configure: (GameInfo) -> Unit = {}): File {
        val game = game()
        BattleFixtures.native(game) { configure(game) }
        return File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    private fun stats(values: Stats): JsonObject = dto(*values.mapNotNull { (stat, value) ->
        val rounded = (value * 10).roundToInt() * 0.1f
        if (rounded == 0f) null else stat.name to rounded
    }.toTypedArray())

    /** 直接读取原 City／Civilization，不经过城市或经济 DTO，供跨端逐步断言。 */
    fun summary(game: GameInfo): JsonObject = BattleFixtures.native(game) {
        val city = capital(game)
        val player = game.currentPlayerCiv
        fun coordinates(values: Collection<HexCoord>) = values.sortedWith(compareBy({ it.x }, { it.y })).map { it.dto() }
        dto("cityId" to city.id, "gold" to player.gold, "turn" to game.turns,
            "tiles" to coordinates(city.tiles), "worked" to coordinates(city.workedTiles),
            "locked" to coordinates(city.lockedTiles), "buildings" to city.cityConstructions.builtBuildings.sorted(),
            "population" to city.population.population, "food" to city.population.foodStored,
            "freePopulation" to city.population.getFreePopulation(), "manual" to city.manualSpecialists,
            "specialists" to dto(*(city.population.getMaxSpecialists().keys + city.population.getNewSpecialists().keys).sorted().map {
                it to city.population.getNewSpecialists()[it]
            }.toTypedArray()),
            "specialistMax" to dto(*city.population.getMaxSpecialists().toSortedMap().toList().toTypedArray()),
            "workDone" to dto(*city.cityConstructions.inProgressConstructions.toSortedMap().toList().toTypedArray()),
            "overflow" to city.cityConstructions.productionOverflow,
            "resources" to dto(*player.getCivResourcesByName().toSortedMap().toList().toTypedArray()),
            "stats" to stats(city.cityStats.currentCityStats),
            "queue" to city.cityConstructions.constructionQueue.toList(),
            "sold" to city.hasSoldBuildingThisTurn,
            "units" to player.units.getCivUnits().sortedBy { it.id }.map {
                dto("id" to it.id, "name" to it.name, "x" to it.currentTile.position.x,
                    "y" to it.currentTile.position.y, "movement" to it.currentMovement, "health" to it.health)
            }.toList())
    }

    /** 原生端执行 smoke 固定动作。禁止调用经济命令或共用其校验构造期望。 */
    fun nativeStep(game: GameInfo, step: String) = BattleFixtures.native(game) {
        val city = capital(game)
        val view = GameView(game, game.currentPlayerCiv)
        val cv = view.civView.cities().first { it.id == city.id }
        when (step) {
            "buyTile" -> check(cv.tryBuyTile(view.tileMapView.getTile(target)!!))
            "buyBuilding" -> check(cv.constructions.purchaseConstruction(game.ruleset.buildings["Monument"]!!, -1, Stat.Gold, null))
            "buyQueue" -> check(cv.constructions.purchaseConstruction(game.ruleset.units["Warrior"]!!, 2, Stat.Gold, null))
            "sell" -> check(cv.trySellBuilding(game.ruleset.buildings["Market"]!!))
            else -> error("未知原生经济步骤：$step")
        }
        cv.updateCityStats()
    }

    fun nextTurn(game: GameInfo): GameInfo = BattleFixtures.native(game) {
        game.clone().also {
            it.setTransients()
            UncivGame.Current.gameInfo = it
            it.nextTurn()
        }
    }

    fun emit() {
        val file = file()
        var native = UncivFiles.gameInfoFromString(file.readText())
        val expected = linkedMapOf<String, Any?>("initial" to summary(native))
        for (step in listOf("buyTile", "buyBuilding", "buyQueue", "sell")) {
            nativeStep(native, step)
            expected[step] = summary(native)
        }
        native = nextTurn(native)
        expected["nextTurn"] = summary(native)
        check(native.unitNamesTaken.isEmpty()) // 本场景不触发随机伟人姓名。
        expected["gameplay"] = GameplayAssertions.gameplay(native)
        expected["setFields"] = GameplayAssertions.setFields.toList()
        expected["mapOfSetFields"] = GameplayAssertions.mapOfSetFields.toList()
        File(root, "godot/.local/tests/economy-expected.json").writeText(dto(*expected.toList().toTypedArray()).toString())
        file("economy-poor") { it.currentPlayerCiv.addGold(-it.currentPlayerCiv.gold) }
    }
}
