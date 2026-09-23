package com.unciv.godot

import com.unciv.Constants
import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.*
import com.unciv.view.GameView
import com.unciv.view.TradeView
import java.io.File

/** 测试专用原格式场景；期望端只使用原生入口，不依赖外交网关。 */
internal object DiplomacyFixtures {
    val root get() = BattleFixtures.root
    fun enemy(game: GameInfo) = BattleFixtures.enemy(game)

    fun game(war: Boolean = false): GameInfo = BattleFixtures.game().also { game ->
        native(game) {
            val player = game.currentPlayerCiv
            val other = enemy(game)
            if (!war) {
                val logic = TradeLogic(player, other)
                logic.currentTrade.set(peace(game))
                logic.acceptTrade()
                player.getDiplomacyManager(other)!!.trades.clear()
                other.getDiplomacyManager(player)!!.trades.clear()
            }
            for (civ in game.civilizations) {
                civ.addGold(1000 - civ.gold)
                civ.popupAlerts.clear()
                civ.notifications.clear()
                civ.tradeRequests.clear()
                civ.cities.forEach { it.cityConstructions.constructionQueue = arrayListOf("Nothing") }
                for (diplo in civ.diplomacy.values) {
                    diplo.removeFlag(DiplomacyFlags.DeclaredWar)
                    diplo.removeFlag(DiplomacyFlags.DeclinedPeace)
                }
                civ.updateStatsForNextTurn()
            }
        }
    }

    fun file(name: String = "diplomacy", war: Boolean = false, configure: (GameInfo) -> Unit = {}): File {
        val game = game(war)
        native(game) { configure(game) }
        return File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    fun addCiv(game: GameInfo, name: String, x: Int, y: Int, meetPlayer: Boolean = true): Civilization = native(game) {
        val civ = Civilization(game.ruleset.nations[name]!!)
        civ.gameInfo = game
        game.civilizations.add(civ)
        civ.setTransients()
        civ.tech.addTechnology("Agriculture")
        if (civ.isCityState) civ.cityStateFunctions.initCityState(game.ruleset, "Ancient era", emptySequence(), kotlin.random.Random(4602))
        civ.addCity(HexCoord(x, y)).cityConstructions.constructionQueue = arrayListOf("Nothing")
        if (meetPlayer) game.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(civ)
        civ.addGold(1000 - civ.gold)
        civ
    }

    /** 同时包含防御条约、未知盟友、双方城邦盟友和存量开放边界。 */
    fun alliances(game: GameInfo) = native(game) {
        val player = game.currentPlayerCiv
        val other = enemy(game)
        val known = addCiv(game, "Egypt", -6, -6)
        val hidden = addCiv(game, "China", 6, 6, false)
        val ours = addCiv(game, "Geneva", -2, 5)
        val theirs = addCiv(game, "Sidon", 2, -5)
        other.diplomacyFunctions.makeCivilizationsMeet(hidden)
        other.diplomacyFunctions.makeCivilizationsMeet(known)
        other.diplomacyFunctions.makeCivilizationsMeet(theirs)
        player.getDiplomacyManager(known)!!.signDefensivePact(20)
        other.getDiplomacyManager(known)!!.signDefensivePact(20)
        other.getDiplomacyManager(hidden)!!.signDefensivePact(20)
        ours.getDiplomacyManager(player)!!.setInfluence(100f)
        theirs.getDiplomacyManager(other)!!.setInfluence(100f)
        TradeLogic(player, other).apply {
            currentTrade.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = game.speed))
            currentTrade.theirOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = game.speed))
        }.acceptTrade()
        game.tileMap.placeUnitNearTile(HexCoord(5, 0), "Scout", player)
        for (civ in game.civilizations) { civ.popupAlerts.clear(); civ.notifications.clear() }
    }

    /** 每一步保存完整原生状态，供真实窗口流程写盘后逐字段差分。 */
    fun emit() {
        val states = linkedMapOf<String, Any?>()
        fun record(name: String, game: GameInfo) {
            check(game.unitNamesTaken.isEmpty())
            states[name] = GameplayAssertions.gameplay(game)
        }
        fun start(name: String, war: Boolean = false, configure: (GameInfo) -> Unit = {}): GameInfo {
            val saved = file("diplomacy-$name", war, configure)
            return UncivFiles.gameInfoFromString(saved.readText()).also { record("$name-initial", it) }
        }
        val peace = start("peace") {
            addCiv(it, "Egypt", -6, -6)
            addCiv(it, "Geneva", -2, 5)
            it.civilizations.forEach { c -> c.popupAlerts.clear(); c.notifications.clear() }
        }
        native(peace) { peace.currentPlayerCiv.getDiplomacyManager(enemy(peace))!!.declareWar() }
        record("peace-war", peace)
        var war = start("war", true)
        propose(war)
        record("war-pure", war)
        native(war) { check(TradeView(war.currentPlayerCiv, enemy(war), GameView(war, war.currentPlayerCiv)).tryRetractOffer()) }
        record("war-retract", war)
        propose(war, 1000)
        record("war-gold", war)
        war = nextTurn(war)
        check(!war.currentPlayerCiv.isAtWarWith(enemy(war)))
        record("war-nextTurn", war)
        for (choice in listOf("accept", "decline", "dismiss", "mixed")) {
            val g = start("trade-$choice", true) {
                incoming(it, if (choice == "dismiss") 2000 else 100, 25)
                if (choice == "mixed") it.currentPlayerCiv.tradeRequests.first().trade.theirOffers.add(
                    TradeOffer("hidden-city-id", TradeOfferType.City, duration = -1))
            }
            tradeDecision(g, if (choice == "mixed") "decline" else choice)
            record("trade-$choice-done", g)
        }
        for ((type, choices) in listOf(
            AlertType.DeclarationOfFriendship to listOf("accept", "decline"),
            AlertType.DemandToStopSettlingCitiesNear to listOf("agree", "refuse"),
            AlertType.DemandToNotAttackUs to listOf("agree", "refuseAndDeclareWar"),
            AlertType.Denounced to listOf("dismiss", "declareWar"))) {
            val file = file("diplomacy-$type") { alert(it, type) }
            for (choice in choices) {
                val g = UncivFiles.gameInfoFromString(file.readText())
                record("$type-initial", g)
                alertDecision(g, choice)
                record("$type-$choice", g)
            }
        }
        File(root, "godot/.local/tests/diplomacy-expected.json").writeText(dto(
            "states" to dto(*states.toList().toTypedArray()),
            "setFields" to GameplayAssertions.setFields.toList(),
            "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString())
    }

    fun nextTurn(game: GameInfo): GameInfo = native(game) {
        game.clone().also {
            it.setTransients()
            com.unciv.UncivGame.Current.gameInfo = it
            it.nextTurn()
        }
    }

    fun peace(game: GameInfo, ourGold: Int = 0, theirGold: Int = 0): Trade = Trade().apply {
        ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        if (ourGold > 0) ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, ourGold, game.speed))
        if (theirGold > 0) theirOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, theirGold, game.speed))
    }

    fun incoming(game: GameInfo, ourGold: Int = 0, theirGold: Int = 0) {
        game.currentPlayerCiv.tradeRequests.add(TradeRequest(enemy(game).civID, peace(game, ourGold, theirGold)))
    }

    fun alert(game: GameInfo, type: AlertType) {
        game.currentPlayerCiv.popupAlerts.add(PopupAlert(type, enemy(game).civID))
    }

    fun propose(game: GameInfo, ourGold: Int = 0, theirGold: Int = 0) = native(game) {
        val player = game.currentPlayerCiv
        val view = TradeView(player, enemy(game), GameView(game, player))
        view.setStagedTrade(peace(game, ourGold, theirGold))
        check(view.tryProposeStagedTrade())
    }

    fun tradeDecision(game: GameInfo, choice: String) = native(game) {
        val player = game.currentPlayerCiv
        val request = player.tradeRequests.first()
        val other = game.getCivilization(request.requestingCiv)
        when (choice) {
            "accept" -> TradeLogic(player, other).apply { currentTrade.set(request.trade) }.acceptTrade()
            "decline" -> request.decline(player)
            "dismiss" -> Unit
            else -> error(choice)
        }
        player.tradeRequests.remove(request)
        if (choice != "dismiss") other.addNotification(
            "[${player.civName}] has ${if (choice == "accept") "accepted" else "denied"} your trade request",
            NotificationCategory.Trade, player.civName, NotificationIcon.Trade)
    }

    fun alertDecision(game: GameInfo, choice: String) = native(game) {
        val player = game.currentPlayerCiv
        val alert = player.popupAlerts.first()
        val diplo = player.getDiplomacyManager(game.getCivilization(alert.value))!!
        when (alert.type) {
            AlertType.DeclarationOfFriendship -> when (choice) {
                "accept" -> diplo.signDeclarationOfFriendship()
                "decline" -> diplo.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
            }
            AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToNotAttackUs -> {
                val demand = if (alert.type == AlertType.DemandToNotAttackUs) Demand.DoNotAttackUs else Demand.DoNotSettleNearUs
                if (choice == "agree") diplo.agreeToDemand(demand)
                else if (choice != "dismiss") diplo.refuseDemand(demand)
            }
            AlertType.Denounced -> if (choice == "declareWar") diplo.declareWar()
            else -> error("未接入的测试事件：${alert.type}")
        }
        player.popupAlerts.remove(alert)
    }
}
