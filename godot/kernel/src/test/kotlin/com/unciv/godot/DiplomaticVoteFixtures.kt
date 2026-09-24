package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import java.io.File

/** 测试专用原格式场景；导出后的期望序列只走原生入口，不读取投票网关或其 DTO。 */
internal object DiplomaticVoteFixtures {
    val root get() = BattleFixtures.root
    val runId = "diplomatic-vote-${System.currentTimeMillis()}"
    val runDir get() = File(root, "godot/.local/tests/$runId").apply { mkdirs() }
    val flags = listOf(CivFlags.TurnsTillNextDiplomaticVote, CivFlags.ShowDiplomaticVotingResults, CivFlags.ShouldResetDiplomaticVotes)
    fun <T> native(game: GameInfo, action: () -> T): T = BattleFixtures.native(game, action)
    fun other(game: GameInfo) = BattleFixtures.enemy(game)
    fun copy(game: GameInfo) = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(game, true))
    fun export(game: GameInfo, name: String): File = File(root, "godot/.local/tests/$name.json").apply {
        parentFile.mkdirs(); writeText(UncivFiles.gameInfoToString(game, true))
    }
    fun cast(game: GameInfo, target: String?) = native(game) { game.currentPlayerCiv.diplomaticVoteForCiv(target) }
    fun acknowledge(game: GameInfo) = native(game) { game.currentPlayerCiv.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1) }
    fun nextTurn(game: GameInfo): GameInfo = native(game) {
        game.clone().also { it.setTransients(); UncivGame.Current.gameInfo = it; it.nextTurn() }
    }

    fun game(rules: BaseRuleset = BaseRuleset.Civ_V_GnK, meet: Boolean = true, cityStates: Boolean = false): GameInfo {
        val game = GreatPersonFixtures.game(rules = rules, free = 0)
        game.gameParameters.victoryTypes = arrayListOf("Diplomatic")
        native(game) {
            val player = game.currentPlayerCiv
            if (cityStates) {
                DiplomacyFixtures.addCiv(game, "Geneva", 6, 0, false)
                DiplomacyFixtures.addCiv(game, "Sidon", 0, 6, false)
            }
            val cities = game.civilizations.flatMap { it.cities }
            // 每座城市独立的陆地小盆地；提前分配全地图领土，避免长周期扩地的等价候选迭代差异。
            for (tile in game.tileMap.tileList) {
                tile.baseTerrain = if (cities.any { it.getCenterTile().aerialDistanceTo(tile) <= 1 }) "Grassland" else "Mountain"
                tile.setTerrainTransients()
                if (meet && !tile.isCityCenter()) cities.minBy { it.getCenterTile().aerialDistanceTo(tile) }.expansion.takeOwnership(tile)
            }
            for (civ in game.civilizations) {
                for (tech in game.ruleset.technologies.values) if (tech.name != "Future Tech") {
                    // 无接触边界样本直接预置研究集合，避免 Satellites 的揭图副作用制造接触。
                    if (meet) civ.tech.addTechnology(tech.name) else civ.tech.techsResearched.add(tech.name)
                }
                civ.tech.techsToResearch = arrayListOf("Future Tech")
                civ.addGold(-civ.gold)
                for (city in civ.cities) {
                    city.avoidGrowth = true
                    city.cityConstructions.constructionQueue = arrayListOf("Nothing")
                    city.expansion.cultureStored = 0
                }
            }
            if (meet && !player.knows(other(game))) player.diplomacyFunctions.makeCivilizationsMeet(other(game))
            for (civ in game.civilizations) for (diplo in civ.diplomacy.values) {
                diplo.setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 100)
                diplo.setModifier(DiplomaticModifiers.DeclarationOfFriendship, 100f)
            }
            game.setTransients()
            for (civ in game.civilizations) {
                civ.popupAlerts.clear(); civ.notifications.clear(); civ.tradeRequests.clear()
                flags.forEach { civ.removeFlag(it.name) }
            }
            game.diplomaticVictoryVotesCast.clear()
            game.diplomaticVictoryVotesProcessed = false
            game.victoryData = null
        }
        return game
    }

    fun voting(rules: BaseRuleset = BaseRuleset.Civ_V_GnK, meet: Boolean = true): GameInfo = game(rules, meet).also { game ->
        game.civilizations.filter { !it.isBarbarian && !it.isSpectator() }.forEach {
            it.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, if (it == game.currentPlayerCiv) 0 else 1)
            if (it == game.currentPlayerCiv) it.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 1)
        }
    }

    fun results(kind: String = "tie", rules: BaseRuleset = BaseRuleset.Civ_V_GnK): GameInfo = voting(rules, meet = kind != "hidden").also { game -> native(game) {
        val player = game.currentPlayerCiv
        val other = other(game)
        when (kind) {
            "tie" -> { player.diplomaticVoteForCiv(other.civID); other.diplomaticVoteForCiv(player.civID) }
            "abstain" -> { player.diplomaticVoteForCiv(null); other.diplomaticVoteForCiv(null) }
            "empty" -> Unit
            "dead" -> {
                val dead = DiplomacyFixtures.addCiv(game, "China", -6, -6)
                dead.cities = emptyList()
                player.diplomaticVoteForCiv(dead.civID)
                other.diplomaticVoteForCiv(player.civID)
                player.popupAlerts.clear()
            }
            "win" -> { player.diplomaticVoteForCiv(other.civID); other.diplomaticVoteForCiv(other.civID) }
            "hidden" -> {
                val hidden = DiplomacyFixtures.addCiv(game, "Egypt", 6, 6, false)
                hidden.diplomaticVoteForCiv(other.civID)
                player.diplomaticVoteForCiv(null)
            }
            else -> error(kind)
        }
        player.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 0)
        player.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, player.getTurnsBetweenDiplomaticVotes())
        player.addFlag(CivFlags.ShouldResetDiplomaticVotes.name, 1)
    } }

    fun natural(rules: BaseRuleset = BaseRuleset.Civ_V_GnK): GameInfo = game(rules, cityStates = true).also { game -> native(game) {
        val city = game.currentPlayerCiv.cities.first()
        val un = game.ruleset.buildings["United Nations"]!!
        city.cityConstructions.inProgressConstructions[un.name] = un.getProductionCost(city.civ, city) - 1
        // 联合国通过正式 production 选择后下一回合完工；完工后由脚本合法选择持续项目。
        city.cityConstructions.constructionQueue = arrayListOf(PerpetualConstruction.Idle.name)
    } }

    fun winning(playerWins: Boolean, rules: BaseRuleset = BaseRuleset.Civ_V_GnK): GameInfo = game(rules, cityStates = true).also { game -> native(game) {
        val winner = if (playerWins) game.currentPlayerCiv else other(game)
        for (cs in game.civilizations.filter { it.isCityState }) {
            if (!cs.knows(winner)) cs.diplomacyFunctions.makeCivilizationsMeet(winner)
            cs.getDiplomacyManager(winner)!!.setInfluence(200f)
            cs.cityStateFunctions.updateAllyCivForCityState()
        }
        val unOwner = if (playerWins) other(game) else game.currentPlayerCiv
        unOwner.cities.first().cityConstructions.addBuilding("United Nations")
        for (civ in game.civilizations.filter { !it.isBarbarian && !it.isSpectator() }) {
            // 人类已到回合开始；AI 还需经过各自 startTurn 的倒计时递减再投票。
            civ.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, if (civ == game.currentPlayerCiv) 0 else 1)
            if (civ == game.currentPlayerCiv) civ.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 1)
            civ.popupAlerts.clear(); civ.notifications.clear()
        }
    } }

    /** 显式动作脚本同时输出给真窗口；这里只实现原 UI 对应动作，不调用任何新网关。 */
    fun apply(game: GameInfo, action: String, params: JsonObject = dto()): GameInfo = native(game) {
        val player = game.currentPlayerCiv
        when (action) {
            "nextTurn" -> return@native nextTurn(game)
            "reload" -> return@native copy(game)
            "diplomaticVoteCast" -> player.diplomaticVoteForCiv(if (params.text("choice") == "abstain") null else params.text("civId"))
            "diplomaticVoteAcknowledge" -> acknowledge(game)
            "acknowledge" -> {
                check(player.popupAlerts.first().type in setOf(AlertType.StartIntro, AlertType.FirstContact,
                    AlertType.TechResearched, AlertType.WonderBuilt, AlertType.GoldenAge, AlertType.GameHasBeenWon))
                player.popupAlerts.removeAt(0)
            }
            "diplomacyTradeDecision" -> DiplomacyFixtures.tradeDecision(game, params.text("choice"))
            "deferPolicy" -> GameView(game, player).civView.tryDismissPolicyPicker()
            "research" -> player.tech.techsToResearch = arrayListOf(params.text("name"))
            "production" -> {
                val city = player.cities.first { it.id == params.text("cityId") }
                val view = GameView(game, player).getCityView(city)
                val construction = game.ruleset.buildings[params.text("name")] ?: game.ruleset.units[params.text("name")]!!
                val index = view.constructions.constructionQueue.indexOf(construction.name)
                if (index >= 0) view.tryMoveEntryToTop(index)
                else {
                    check(view.constructions.canAddToQueue(construction)) {
                        "原生生产不可用：${construction.name}，${construction.getRejectionReasons(city.cityConstructions).toList()}"
                    }
                    view.tryAddToQueueConstruction(construction, addToTop = true)
                }
                check(city.cityConstructions.currentConstructionName() == construction.name)
                view.tryReassignPopulation(); view.updateCityStats()
            }
            "cityQueue" -> {
                val city = player.cities.first { it.id == params.text("cityId") }
                GameView(game, player).getCityView(city).tryAddToQueueConstruction(
                    PerpetualConstruction.perpetualConstructionsMap[params.text("name")]!!)
            }
            else -> error("未知原生脚本动作：$action")
        }
        game
    }

    class Sequence(initial: GameInfo, val name: String) {
        var game = copy(initial)
        val steps = arrayListOf<JsonObject>()
        val states = linkedMapOf<String, JsonObject>()
        val rawStates = linkedMapOf<String, String>()
        val evidenceDir = File(runDir, "$name-${System.nanoTime()}").apply { mkdirs() }
        init { println("外交投票原生证据：${evidenceDir.absolutePath}"); record("initial") }
        private fun record(label: String) {
            val key = "$name-${steps.size}-$label"
            rawStates[key] = UncivFiles.gameInfoToString(game, false)
            states[key] = GameplayAssertions.gameplay(game)
            File(evidenceDir, "$key-raw.json").writeText(rawStates[key]!!)
            File(evidenceDir, "$key-normalized.json").writeText(states[key].toString())
            File(evidenceDir, "steps.json").writeText(value(steps).toString())
        }
        fun step(action: String, params: JsonObject = dto()) {
            game = apply(game, action, params)
            steps.add(dto("action" to action, "params" to params, "step" to "$name-${steps.size + 1}-$action"))
            record(action)
        }
        fun settlePrompts() {
            val player = game.currentPlayerCiv
            while (player.popupAlerts.isNotEmpty()) step("acknowledge")
            while (player.tradeRequests.isNotEmpty()) step("diplomacyTradeDecision", dto("choice" to "decline"))
            val cv = GameView(game, player).civView
            if (cv.shouldShowPolicyPicker()) step("deferPolicy")
            if (cv.shouldOpenTechPicker()) step("research", dto("name" to "Future Tech"))
            for (city in player.cities) if (city.cityConstructions.currentConstructionName().isEmpty())
                step("cityQueue", dto("cityId" to city.id, "type" to "add", "name" to PerpetualConstruction.Gold.name))
        }
        fun untilVote() {
            repeat(40) {
                if (game.currentPlayerCiv.mayVoteForDiplomaticVictory()) return
                settlePrompts(); step("nextTurn")
            }
            error("自然投票周期未到达")
        }
        fun untilResults() {
            repeat(4) {
                if (game.currentPlayerCiv.shouldShowDiplomaticVotingResults()) return
                settlePrompts(); step("nextTurn")
            }
            error("原生投票结果未公布")
        }
    }

    fun winningSequence(initial: GameInfo, playerWins: Boolean): Sequence = Sequence(initial, if (playerWins) "player-win" else "ai-win").apply {
        val winner = if (playerWins) game.currentPlayerCiv.civID else other(game).civID
        step("reload")
        step("diplomaticVoteCast", if (playerWins) dto("choice" to "abstain")
            else dto("choice" to "civilization", "civId" to winner))
        step("reload"); untilResults(); step("reload")
        check(game.getCivilization(winner).victoryManager.hasEverWonDiplomaticVote) {
            "原生 AI 投票未当选：${game.diplomaticVictoryVotesCast}"
        }
        // AI 的 updateWinningCiv 已在本轮计票前运行；结果确认不代它写 victoryData。
        if (!playerWins) check(game.victoryData == null)
        step("diplomaticVoteAcknowledge"); step("reload")
        if (!playerWins) { settlePrompts(); step("nextTurn"); step("reload") }
        check(game.victoryData?.winningCiv == winner)
        check(game.victoryData?.victoryType == "Diplomatic")
    }

    fun abstainSequence(initial: GameInfo): Sequence = Sequence(initial, "abstain").apply {
        check(game.currentPlayerCiv.mayVoteForDiplomaticVictory())
        check(game.currentPlayerCiv.diplomacyFunctions.getKnownCivsSorted(false).none())
        step("reload")
        step("diplomaticVoteCast", dto("choice" to "abstain"))
        step("reload"); untilResults(); step("reload")
        step("diplomaticVoteAcknowledge"); step("reload")
    }

    /** 原生逐步文件保留在唯一 runId；manifest 只索引文件，不重复膨胀完整地图。 */
    fun emit() {
        val scenarios = arrayListOf<JsonObject>()
        fun register(name: String, initial: GameInfo, sequence: (GameInfo) -> Sequence, boundary: Boolean = false) {
            val fixture = export(initial, "diplomatic-vote-$name")
            val seq = sequence(initial)
            scenarios.add(dto("name" to name, "fixture" to fixture.absolutePath, "boundary" to boundary,
                "steps" to seq.steps, "initial" to seq.states.keys.first(),
                "states" to dto(*seq.states.keys.map { key -> key to dto(
                    "raw" to File(seq.evidenceDir, "$key-raw.json").absolutePath,
                    "normalized" to File(seq.evidenceDir, "$key-normalized.json").absolutePath)
                }.toTypedArray())))
        }
        val choices = voting().also { game -> native(game) {
            DiplomacyFixtures.addCiv(game, "Egypt", 6, 6)
            game.currentPlayerCiv.popupAlerts.clear()
        } }
        register("choices", choices, { initial -> Sequence(initial, "choices").apply {
            step("reload")
            step("diplomaticVoteCast", dto("choice" to "civilization", "civId" to other(game).civID))
            step("reload")
        } }, true)
        register("natural", natural(), { naturalSequence(it, "natural") })
        register("abstain", voting(meet = false), ::abstainSequence)
        register("player-win", winning(true), { winningSequence(it, true) })
        register("ai-win", winning(false), { winningSequence(it, false) })
        for (kind in listOf("tie", "abstain", "empty", "dead", "hidden"))
            register("results-edge-$kind", results(kind), { initial -> Sequence(initial, "results-edge-$kind").apply {
                step("reload"); step("diplomaticVoteAcknowledge"); step("reload")
            } }, true)
        val manifest = dto("runId" to runId, "scenarios" to scenarios,
            "setFields" to GameplayAssertions.setFields.toList(), "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList())
        File(root, "godot/.local/tests/diplomatic-vote-expected.json").writeText(manifest.toString())
        File(runDir, "export-manifest.json").writeText(manifest.toString())
    }

    fun naturalSequence(initial: GameInfo, name: String): Sequence = Sequence(initial, name).apply {
        step("production", dto("cityId" to game.currentPlayerCiv.cities.first().id, "name" to "United Nations"))
        step("nextTurn")
        check(game.currentPlayerCiv.victoryManager.getUNBuildingAndOwnerNames().first == "United Nations") {
            "联合国未于正常生产回合完成；证据：${evidenceDir.absolutePath}"
        }
        untilVote(); step("reload")
        step("diplomaticVoteCast", dto("choice" to "civilization", "civId" to other(game).civID))
        step("reload"); untilResults(); step("reload")
        check(game.victoryData == null) { "下一轮样本不应产生胜者" }
        step("diplomaticVoteAcknowledge"); step("reload")
        untilVote()
        check(game.diplomaticVictoryVotesCast[game.currentPlayerCiv.civID] == null)
        check(!game.diplomaticVictoryVotesCast.containsKey(game.currentPlayerCiv.civID))
        step("diplomaticVoteCast", dto("choice" to "abstain"))
        untilResults(); step("diplomaticVoteAcknowledge"); step("reload")
    }
}
