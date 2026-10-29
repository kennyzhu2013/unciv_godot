package com.unciv.godot

import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.VictoryData
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import kotlinx.serialization.json.*
import java.io.File

/** 原生期望独立于胜利网关：按原画面时序检查胜利，不从 DTO 推导期望。 */
internal object VictoryFixtures {
    val root get() = BattleFixtures.root
    fun copy(game: GameInfo) = DiplomaticVoteFixtures.copy(game)
    fun game(rules: BaseRuleset = BaseRuleset.Civ_V_GnK): GameInfo = DiplomaticVoteFixtures.game(rules).also {
        it.gameParameters.victoryTypes = ArrayList(it.ruleset.victories.keys.filter { name -> name != "Neutral" })
        it.gameParameters.maxTurns = 500
    }
    fun screenUpdate(game: GameInfo) = native(game) {
        if (!game.currentPlayerCiv.shouldShowDiplomaticVotingResults() && !game.oneMoreTurnMode && !game.currentPlayerCiv.isDefeated())
            game.checkForVictory()
    }
    fun recorded(playerWins: Boolean, type: String = "Scientific", rules: BaseRuleset = BaseRuleset.Civ_V_GnK): GameInfo = game(rules).also {
        it.victoryData = VictoryData(if (playerWins) it.currentPlayerCiv else BattleFixtures.enemy(it), type, it.turns)
    }
    fun defeated(): GameInfo = game().also { game -> native(game) {
        val player = game.currentPlayerCiv
        player.cities.toList().forEach { it.destroyCity(true) }
        player.units.getCivUnits().toList().forEach { it.destroy() }
        player.popupAlerts.clear(); player.notifications.clear()
        game.victoryData = null
        game.turns = 12
    } }
    fun conquest(): GameInfo = GreatPersonFixtures.game(free = 0).also { game -> native(game) {
        game.gameParameters.victoryTypes = arrayListOf("Domination")
        val player = game.currentPlayerCiv
        val enemy = BattleFixtures.enemy(game)
        if (!player.knows(enemy)) player.diplomacyFunctions.makeCivilizationsMeet(enemy)
        player.getDiplomacyManager(enemy)!!.declareWar()
        enemy.cities.first().health = 1
        BattleFixtures.add(game, "Warrior", -5, 0)
        game.setTransients()
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
    } }
    fun export(game: GameInfo, name: String): File = DiplomaticVoteFixtures.export(game, "victory-$name")

    /** 第五分支（传统）只差一项时留给真实政策命令的那一项：requires 图里没有后继依赖的汇点。 */
    fun traditionPolicy(game: GameInfo): String {
        val normal = game.ruleset.policyBranches["Tradition"]!!.policies.dropLast(1)
        val requiredByOthers = normal.flatMap { it.requires.orEmpty() }.toSet()
        return normal.last { it.name !in requiredByOthers }.name
    }

    /** 文化“动作前”边界：四项政策分支由原生 adopt 预置完成，第五分支只差一项，乌托邦工程差 1 点生产力。 */
    fun cultural(): GameInfo = game().also { game -> native(game) {
        game.gameParameters.victoryTypes = arrayListOf("Cultural")
        val player = game.currentPlayerCiv
        player.policies.freePolicies = 1000
        for (name in listOf("Honor", "Piety", "Commerce", "Order")) {
            val branch = game.ruleset.policyBranches[name]!!
            if (!player.policies.isAdopted(branch.name)) player.policies.adopt(branch)
            for (item in branch.policies.dropLast(1))
                if (!player.policies.isAdopted(item.name)) player.policies.adopt(item)
        }
        val tradition = game.ruleset.policyBranches["Tradition"]!!
        if (!player.policies.isAdopted(tradition.name)) player.policies.adopt(tradition)
        val last = traditionPolicy(game)
        while (true) {
            val next = tradition.policies.dropLast(1).firstOrNull {
                it.name != last && !player.policies.isAdopted(it.name) && player.policies.isAdoptable(it)
            } ?: break
            player.policies.adopt(next)
        }
        check(player.policies.isAdoptable(game.ruleset.policies[last]!!)) { "第五分支应只差 $last" }
        check(player.policies.completedBranches.size == 4) { "预置后应正好四个完整分支" }
        val city = player.cities.first()
        val utopia = game.ruleset.buildings["Utopia Project"]!!
        city.cityConstructions.inProgressConstructions[utopia.name] = utopia.getProductionCost(player, city) - 1
        player.policies.freePolicies = 1
        player.policies.storedCulture = 0
        player.policies.shouldOpenPolicyPicker = false
        game.setTransients()
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
    } }

    /** 时间“动作前”边界：只差一次真实 nextTurn 跨过回合上限；领先方由原生分数严格决定。 */
    fun time(playerLeads: Boolean): GameInfo = GreatPersonFixtures.game(free = 0).also { game -> native(game) {
        game.gameParameters.victoryTypes = arrayListOf("Time")
        game.gameParameters.maxTurns = game.turns + 1
        val leader = if (playerLeads) game.currentPlayerCiv else BattleFixtures.enemy(game)
        leader.cities.first().population.setPopulation(6)
        game.setTransients()
        check(game.civilizations.filter { it != leader }.all { it.calculateTotalScore() < leader.calculateTotalScore() })
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
    } }

    /** 灭亡“动作前”边界：唯一城市 1 点血量，相邻敌军已宣战；夺取发生在真实原生回合里。 */
    fun lastStand(): GameInfo = GreatPersonFixtures.game(free = 0).also { game -> native(game) {
        game.gameParameters.victoryTypes = arrayListOf("Domination")
        val player = game.currentPlayerCiv
        val rival = BattleFixtures.enemy(game)
        if (!player.knows(rival)) player.diplomacyFunctions.makeCivilizationsMeet(rival)
        player.getDiplomacyManager(rival)!!.declareWar()
        player.cities.first().health = 1
        BattleFixtures.add(game, "Warrior", -1, 0, rival)
        game.setTransients()
        game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
    } }
    fun apply(original: GameInfo, action: String, params: JsonObject = dto()): GameInfo = native(original) {
        var game = original
        when (action) {
            "reload" -> game = copy(game)
            "nextTurn" -> game = DiplomaticVoteFixtures.nextTurn(game)
            // 与政策面板选择同一原生入口；分支完成时的 Complete 政策由 adopt 内部递归采纳。
            "policy" -> game.currentPlayerCiv.policies.adopt(game.ruleset.policies[params.text("name")]!!)
            "victoryContinue" -> game.oneMoreTurnMode = true
            "attack" -> {
                val unit = game.currentPlayerCiv.units.getCivUnits().first { it.id == params.integer("unitId") }
                val target = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
                    .first { it.tileToAttack.position == HexCoord(params.integer("x"), params.integer("y")) }
                val attacker = MapUnitCombatant(unit)
                check(Battle.movePreparingAttack(attacker, target, false))
                Battle.attackOrNuke(attacker, target)
            }
            "cityDecision" -> {
                val player = game.currentPlayerCiv
                val alert = player.popupAlerts.first()
                check(alert.type == AlertType.CityConquered && alert.value == params.text("cityId"))
                game.getCities().first { it.id == alert.value }.puppetCity(player)
                player.popupAlerts.remove(alert)
            }
            else -> game = DiplomaticVoteFixtures.apply(game, action, params)
        }
        screenUpdate(game)
        game
    }
    val runId = "victory-${System.currentTimeMillis()}"
    val runDir get() = File(root, "godot/.local/tests/$runId").apply { mkdirs() }
    fun emit() {
        val scenarios = arrayListOf<JsonObject>()
        fun add(name: String, initial: GameInfo, actions: List<Pair<String, JsonObject>>) {
            val file = export(initial, name)
            var expected = copy(initial)
            screenUpdate(expected)
            val states = linkedMapOf<String, JsonObject>()
            fun record(key: String) {
                val raw = File(runDir, "$name-$key-raw.json")
                val normalized = File(runDir, "$name-$key-normalized.json")
                raw.writeText(UncivFiles.gameInfoToString(expected, false))
                normalized.writeText(GameplayAssertions.gameplay(expected).toString())
                states[key] = dto("raw" to raw.absolutePath, "normalized" to normalized.absolutePath)
            }
            record("initial")
            val steps = actions.mapIndexed { index, (action, params) ->
                expected = apply(expected, action, params)
                val key = "${index + 1}-$action"
                record(key)
                dto("action" to action, "params" to params, "step" to key)
            }
            scenarios.add(dto("name" to name, "fixture" to file.absolutePath, "steps" to steps, "initial" to "initial",
                "states" to dto(*states.map { it.key to it.value }.toTypedArray())))
        }
        add("progress", game(), emptyList())
        for ((name, initial) in listOf("won" to recorded(true), "lost" to recorded(false), "defeated" to defeated())) {
            add(name, initial, listOf("victoryContinue" to dto(), "reload" to dto(), "nextTurn" to dto(), "reload" to dto()))
        }
        val conquest = conquest()
        add("conquest", conquest, listOf(
            "attack" to dto("unitId" to BattleFixtures.unit(conquest, "Warrior").id, "x" to -6, "y" to 0),
            "cityDecision" to dto("cityId" to BattleFixtures.enemy(conquest).cities.first().id, "choice" to "puppet"),
            "victoryContinue" to dto(), "reload" to dto()))
        File(root, "godot/.local/tests/victory-expected.json").writeText(dto("scenarios" to scenarios,
            "setFields" to GameplayAssertions.setFields.toList(), "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString())
    }
}
