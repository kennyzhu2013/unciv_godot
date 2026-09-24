package com.unciv.godot

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.ui.components.MayaCalendar
import java.io.File
import kotlinx.serialization.json.*

/** 测试专用状态构造；导出后只走正式原生入口，不依赖网关资格或 DTO。 */
internal object GreatPersonFixtures {
    val root get() = BattleFixtures.root
    fun <T> native(game: GameInfo, action: () -> T): T = BattleFixtures.native(game, action)
    fun manager(game: GameInfo) = game.currentPlayerCiv.greatPeople
    fun game(nation: String = "Rome", rules: BaseRuleset = BaseRuleset.Civ_V_GnK,
             free: Int = 1, blocked: Boolean = false, coastal: Boolean = false): GameInfo {
        KernelRuntime.initialize(root)
        val parameters = GameParameters().apply {
            baseRuleset = rules.fullName
            players = arrayListOf(Player(nation, PlayerType.Human), Player("Greece"))
            numberOfCityStates = 0
            noBarbarians = true
        }
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, MapParameters().apply {
            mapSize = MapSize.Tiny; seed = 4602L; noRuins = true
        }))
        native(game) {
            game.civilizations.forEach { civ -> civ.units.getCivUnits().toList().forEach { it.destroy() } }
            val radius = if (blocked) 14 else 8
            game.tileMap = TileMap(radius, game.ruleset).apply {
                gameInfo = game; mapParameters.mapSize = MapSize(radius); mapParameters.seed = 4602L
            }
            game.setTransients()
            for (tile in game.tileMap.tileList) {
                tile.baseTerrain = when {
                    blocked && !(tile.position.y == 0 && tile.position.x in 0..11) && tile.position != HexCoord(-6, 0) -> "Mountain"
                    coastal && tile.position.x > 0 -> "Coast"
                    else -> Constants.grassland
                }
                tile.setTerrainFeatures(emptyList())
                tile.tileResource = null
                tile.setTerrainTransients()
            }
            val player = game.currentPlayerCiv
            val capital = player.addCity(HexCoord(0, 0))
            BattleFixtures.enemy(game).addCity(HexCoord(-6, 0))
            game.civilizations.forEach { civ ->
                civ.cities.forEach { city ->
                    city.cityConstructions.constructionQueue = arrayListOf("Nothing")
                    // 初始无扩地文化；一步回合探针不夹入无关的扩地选择。
                    city.expansion.cultureStored = 0
                }
                civ.tech.techsToResearch = arrayListOf("Globalization")
                civ.religionManager.storedFaith = 0
            }
            capital.population.setPopulation(1)
            manager(game).freeGreatPeople = free
            if (blocked) for (x in 0..10) BattleFixtures.add(game, "Worker", x, 0)
            game.setTransients()
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
        }
        return game
    }

    fun export(game: GameInfo, name: String): File = File(root, "godot/.local/tests/$name.json").apply {
        parentFile.mkdirs(); writeText(UncivFiles.gameInfoToString(game, true))
    }
    fun copy(game: GameInfo): GameInfo = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(game, true))
    fun lastId(game: GameInfo): Int = Json.parseToJsonElement(UncivFiles.gameInfoToString(game, false))
        .jsonObject["lastUnitId"]?.jsonPrimitive?.int ?: 0
    fun choose(game: GameInfo, name: String) = native(game) { manager(game).chooseFreeGreatPerson(name) }
    fun nextTurn(game: GameInfo): GameInfo = native(game) {
        game.clone().also { it.setTransients(); UncivGame.Current.gameInfo = it; it.nextTurn() }
    }
    fun moveBlocker(game: GameInfo) = native(game) {
        val unit = game.tileMap[HexCoord(10, 0)].civilianUnit!!
        unit.action = null
        unit.movement.moveToTile(game.tileMap[HexCoord(11, 0)])
    }
    fun liberty(): GameInfo = game(free = 0).also { game -> native(game) {
        val player = game.currentPlayerCiv
        for (name in listOf("Liberty", "Republic", "Citizenship", "Collective Rule", "Representation")) {
            player.policies.freePolicies++
            player.policies.adopt(game.ruleset.policies[name]!!)
        }
        player.policies.freePolicies = 1
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
    } }
    fun adoptMeritocracy(game: GameInfo) = native(game) {
        game.currentPlayerCiv.policies.adopt(game.ruleset.policies["Meritocracy"]!!)
    }
    fun maya(): GameInfo = game("The Maya", free = 0).also { game -> native(game) {
        val player = game.currentPlayerCiv
        fun research(name: String) {
            if (player.tech.isResearched(name)) return
            game.ruleset.technologies[name]!!.prerequisites.forEach(::research)
            player.tech.addTechnology(name)
        }
        research("Theology")
        val probe = copy(game)
        val boundary = native(probe) {
            (1..500).first { turn ->
                probe.turns = turn
                MayaCalendar.startTurnForMaya(probe.currentPlayerCiv)
                manager(probe).freeGreatPeople > 0
            }
        }
        game.turns = boundary - 1
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
    } }
    /** 与窗口流程同序：记录保存，按明确检查点重载；不跳过或清空任何中途待决。 */
    fun emit() {
        val states = linkedMapOf<String, Any?>()
        val rawStates = linkedMapOf<String, Any?>()
        val acknowledgements = linkedMapOf<String, Any?>()
        fun record(name: String, game: GameInfo) {
            rawStates[name] = UncivFiles.gameInfoToString(game, false)
            states[name] = GameplayAssertions.gameplay(game)
        }
        fun start(name: String, fixture: GameInfo): GameInfo {
            val file = export(fixture, "great-person-$name")
            return UncivFiles.gameInfoFromString(file.readText()).also { record("$name-initial", it) }
        }
        for (kind in listOf("liberty", "maya")) {
            var current = start(kind, if (kind == "liberty") liberty() else maya())
            if (kind == "liberty") adoptMeritocracy(current) else current = nextTurn(current)
            check(manager(current).freeGreatPeople == 1)
            record("$kind-reward", current)
            current = copy(current)
            record("$kind-reward-reload", current)
            check(choose(current, "Great Scientist") != null)
            record("$kind-granted", current)
            current = copy(current)
            record("$kind-granted-reload", current)
            var count = 0
            while (current.currentPlayerCiv.popupAlerts.isNotEmpty()) {
                check(current.currentPlayerCiv.popupAlerts.first().type in GameSession.informationalAlerts)
                native(current) { current.currentPlayerCiv.popupAlerts.removeAt(0) }
                record("$kind-ack-${++count}", current)
            }
            acknowledgements[kind] = count
            current = nextTurn(current)
            record("$kind-nextTurn", current)
        }
        var mixed = start("mixed", mixed())
        check(choose(mixed, "Great Scientist") != null)
        record("mixed-first", mixed)
        mixed = copy(mixed)
        record("mixed-reload", mixed)
        check(choose(mixed, "Great Engineer") != null)
        record("mixed-second", mixed)
        var blocked = start("blocked", game(blocked = true))
        check(choose(blocked, "Great Scientist") == null)
        record("blocked-notPlaced", blocked)
        blocked = copy(blocked)
        record("blocked-reload", blocked)
        moveBlocker(blocked)
        record("blocked-moved", blocked)
        check(choose(blocked, "Great Scientist") != null)
        record("blocked-granted", blocked)
        blocked = copy(blocked)
        record("blocked-final-reload", blocked)
        for (coastal in listOf(false, true)) {
            val name = "admiral-" + if (coastal) "coastal" else "inland"
            var admiral = start(name, game(coastal = coastal))
            check((choose(admiral, "Great Admiral") != null) == coastal)
            record("$name-attempt", admiral)
            admiral = copy(admiral)
            record("$name-reload", admiral)
        }
        val result = dto("states" to dto(*states.toList().toTypedArray()),
            "rawStates" to dto(*rawStates.toList().toTypedArray()),
            "acknowledgements" to dto(*acknowledgements.toList().toTypedArray()),
            "setFields" to GameplayAssertions.setFields.toList(),
            "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString()
        File(root, "godot/.local/tests/great-person-expected.json").writeText(result)
        File(root, "godot/.local/tests/great-person-expected-${System.currentTimeMillis()}.json").writeText(result)
    }
    fun mixed(): GameInfo = game("The Maya", free = 2).also {
        manager(it).mayaLimitedFreeGP = 1
        manager(it).longCountGPPool = hashSetOf("Great Scientist")
    }
}
