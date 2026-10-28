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
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
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
    val cityStateAlerts = listOf(AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor)
    val spyAlerts = listOf(AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise)
    val alertChoices = listOf(
        AlertType.DeclarationOfFriendship to listOf("accept", "decline"),
        AlertType.DemandToStopSettlingCitiesNear to listOf("agree", "refuse"),
        AlertType.DemandToNotAttackUs to listOf("agree", "refuseAndDeclareWar"),
        AlertType.Denounced to listOf("dismiss", "declareWar"),
        AlertType.DemandToStopSpreadingReligion to listOf("agree", "refuse")) +
        spyAlerts.map { it to listOf("agree", "refuse") } + cityStateAlerts.map { it to listOf("declareWar", "support", "withdrawProtection") }

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
        // 第 4 批：多条款、城市与第三方关系的来件交易，供真实窗口逐条核对并整笔接受。
        val rich = start("trade-rich") { game ->
            resource(game, game.currentPlayerCiv, "Iron", 2)
            resource(game, enemy(game), "Furs", 2)
            incomingTrade(game, trade(
                our = listOf(
                    TradeOffer(Constants.flatGold, TradeOfferType.Gold, 25, duration = -1),
                    TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, 1, 5),
                    TradeOffer("Iron", TradeOfferType.Strategic_Resource, 1, 10)),
                their = listOf(
                    TradeOffer(Constants.flatGold, TradeOfferType.Gold, 75, duration = -1),
                    TradeOffer("Furs", TradeOfferType.Luxury_Resource, 1, 10),
                    TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, 1, duration = -1),
                    TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 12))))
        }
        tradeDecision(rich, "accept")
        record("trade-rich-done", rich)
        val cityTrade = start("trade-city") { game ->
            val founder = third(game)
            val exchanged = enemy(game).cities.first { it.name == "陆战测试城" }
            exchanged.foundingCivObject = founder
            incomingTrade(game, trade(their = listOf(
                TradeOffer(exchanged.id, TradeOfferType.City, 1, duration = -1))))
            // 队列中的下一笔：接收城市后的解放待决应先阻塞它，而不是自动推进。
            incomingTrade(game, trade(their = listOf(
                TradeOffer(Constants.flatGold, TradeOfferType.Gold, 5, duration = -1))))
        }
        tradeDecision(cityTrade, "accept")
        record("trade-city-done", cityTrade)
        val thirdWar = start("trade-third") { game ->
            val egypt = third(game)
            incomingTrade(game, trade(their = listOf(
                TradeOffer(egypt.civID, TradeOfferType.WarDeclaration, 1, duration = -1))))
        }
        tradeDecision(thirdWar, "accept")
        record("trade-third-done", thirdWar)
        val thirdPeace = start("trade-third-peace") { game ->
            val egypt = third(game)
            enemy(game).getDiplomacyManager(egypt)!!.declareWar()
            game.currentPlayerCiv.popupAlerts.clear()
            game.currentPlayerCiv.notifications.clear()
            incomingTrade(game, trade(their = listOf(
                TradeOffer(egypt.civID, TradeOfferType.PeaceProposal, 1, duration = -1))))
        }
        tradeDecision(thirdPeace, "accept")
        record("trade-third-peace-done", thirdPeace)
        // 第 5 批：主动报价、撤回、还价预填与再报价。
        val send = start("trade-send")
        native(send) {
            val player = send.currentPlayerCiv
            val view = TradeView(player, enemy(send), GameView(send, player))
            view.setStagedTrade(Trade().apply {
                ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, 500, duration = -1))
            })
            check(view.tryProposeStagedTrade())
        }
        record("trade-send-sent", send)
        record("trade-send-nextTurn", nextTurn(send))
        val withdraw = start("trade-withdraw") { game -> resource(game, game.currentPlayerCiv, "Iron", 2) }
        native(withdraw) {
            val player = withdraw.currentPlayerCiv
            val view = TradeView(player, enemy(withdraw), GameView(withdraw, player))
            // 期限取原生可用报价，与原客户端加入资源条款的方式一致。
            val source = view.ourAvailableOffers().single { it.name == "Iron" }
            view.setStagedTrade(Trade().apply {
                ourOffers.add(TradeOffer(source.name, source.type, 1, source.duration))
            })
            check(view.tryProposeStagedTrade())
        }
        record("trade-withdraw-sent", withdraw)
        native(withdraw) {
            check(TradeView(withdraw.currentPlayerCiv, enemy(withdraw),
                GameView(withdraw, withdraw.currentPlayerCiv)).tryRetractOffer())
        }
        record("trade-withdraw-done", withdraw)
        val renegotiate = start("trade-renegotiate") { game ->
            incomingTrade(game, trade(their = listOf(
                TradeOffer(Constants.flatGold, TradeOfferType.Gold, 40, duration = -1))))
        }
        native(renegotiate) {
            renegotiate.currentPlayerCiv.tradeRequests.clear()
            renegotiate.currentPlayerCiv.cache.updateCivResources()
        }
        record("trade-renegotiate-done", renegotiate)
        native(renegotiate) {
            val player = renegotiate.currentPlayerCiv
            val view = TradeView(player, enemy(renegotiate), GameView(renegotiate, player))
            view.setStagedTrade(Trade().apply {
                theirOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, 40, duration = -1))
            })
            check(view.tryProposeStagedTrade())
        }
        record("trade-renegotiate-sent", renegotiate)
        for ((type, choices) in alertChoices) {
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

    /** 任意条款的来件提案；[our] 为接收方（玩家）给出的条款，[their] 为提案方给出的条款。 */
    fun trade(our: List<TradeOffer> = emptyList(), their: List<TradeOffer> = emptyList()): Trade = Trade().apply {
        ourOffers.addAll(our)
        theirOffers.addAll(their)
    }

    fun incomingTrade(game: GameInfo, trade: Trade, from: Civilization = enemy(game)) {
        game.currentPlayerCiv.tradeRequests.add(TradeRequest(from.civID, trade))
    }

    /** 向首都中心格注入资源并刷新原生统计；自动补齐可见与开采科技，避免地块被判定为无法供给。 */
    fun resource(game: GameInfo, civ: Civilization, name: String, amount: Int = 2) = native(game) {
        val resource = game.ruleset.tileResources[name] ?: error("未知资源：$name")
        resource.revealedBy?.let { civ.tech.addTechnology(it) }
        for (improvement in resource.getImprovements())
            game.ruleset.tileImprovements[improvement]?.techRequired?.let { civ.tech.addTechnology(it) }
        (civ.getCapital() ?: civ.cities.first()).getCenterTile().apply {
            tileResource = resource
            resourceAmount = amount
            setTerrainTransients()
        }
        civ.cache.updateCivResources()
        civ.updateStatsForNextTurn()
        // 注入资源需要补科技，给人类玩家的科技弹窗会阻断交易队列；与原生无弹窗的稳定状态对齐。
        game.currentPlayerCiv.popupAlerts.clear()
        game.currentPlayerCiv.notifications.clear()
        Unit
    }

    /** 双方均已接触的主要文明第三方，并清掉接触产生的弹窗，便于直接处置交易。 */
    fun third(game: GameInfo, name: String = "Egypt"): Civilization = native(game) {
        val civ = addCiv(game, name, -6, -6)
        enemy(game).diplomacyFunctions.makeCivilizationsMeet(civ)
        game.currentPlayerCiv.popupAlerts.clear()
        game.currentPlayerCiv.notifications.clear()
        civ
    }

    /** 仅在初始场景建立城邦关系；连续回合测试不会调用此初始化或清空中途事件。 */
    fun protectedCityState(game: GameInfo, allied: Boolean = false): Civilization = native(game) {
        val player = game.currentPlayerCiv
        val cityState = addCiv(game, "Geneva", -2, 5)
        enemy(game).diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.getDiplomacyManager(player)!!.setInfluence(if (allied) 100f else 45f)
        if (!allied) cityState.cityStateFunctions.addProtectorCiv(player)
        player.popupAlerts.removeAll { it.type == AlertType.FirstContact && it.value == cityState.civID }
        cityState
    }

    fun alert(game: GameInfo, type: AlertType) {
        // 固定选项矩阵使用显式注入，不声称该提示由间谍行动自然产生。
        if (type in spyAlerts) {
            val player = game.currentPlayerCiv
            if (player.espionageManager.spyList.isEmpty())
                player.espionageManager.addSpy().moveTo(enemy(game).cities.first())
            if (type == AlertType.SpyingOnUsDespiteOurPromise) {
                val theirs = enemy(game).getDiplomacyManager(player)!!
                theirs.removeFlag(DiplomacyFlags.AgreedToNotSendSpies)
                theirs.setFlag(DiplomacyFlags.IgnoreThemSendingSpies, 100, true)
                theirs.setModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.BetrayedPromiseToNotSendingSpiesToUs, -20f)
            }
        }
        val value = if (type in cityStateAlerts) {
            val cityState = game.civilizations.firstOrNull { it.civName == "Geneva" }
                ?: protectedCityState(game, type == AlertType.AttackedAllyMinor)
            "${enemy(game).civID}@${cityState.civID}"
        } else enemy(game).civID
        game.currentPlayerCiv.popupAlerts.add(PopupAlert(type, value))
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
        val ids = alert.value.split('@')
        val diplo = player.getDiplomacyManager(game.getCivilization(ids.first()))!!
        when (alert.type) {
            AlertType.DeclarationOfFriendship -> when (choice) {
                "accept" -> diplo.signDeclarationOfFriendship()
                "decline" -> diplo.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
            }
            AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToNotAttackUs, AlertType.DemandToStopSpreadingReligion,
            AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise -> {
                val demand = when (alert.type) {
                    AlertType.DemandToNotAttackUs -> Demand.DoNotAttackUs
                    AlertType.DemandToStopSpreadingReligion -> Demand.DoNotSpreadReligion
                    AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise -> Demand.DontSpyOnUs
                    else -> Demand.DoNotSettleNearUs
                }
                if (choice == "agree") diplo.agreeToDemand(demand)
                else if (choice != "dismiss") diplo.refuseDemand(demand)
            }
            AlertType.Denounced -> if (choice == "declareWar") diplo.declareWar()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> {
                if (choice != "dismiss") {
                    val cityState = game.getCivilization(ids[1])
                    // 独立重现 AlertPopup 的三个回调，不借用网关选项或执行器。
                    when (choice) {
                        "declareWar" -> {
                            diplo.sideWithCityState()
                            diplo.declareWar(DeclareWarReason(
                                if (alert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar, cityState))
                            val manager = cityState.getDiplomacyManager(player)!!
                            manager.setInfluenceWithoutSideEffects(NativeDiplomacyBridge.rawInfluence(manager) + 20f)
                        }
                        "support" -> diplo.sideWithCityState()
                        "withdrawProtection" -> {
                            player.addNotification("You have broken your Pledge to Protect [${cityState.civName}]!",
                                cityState.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cityState.civName)
                            cityState.cityStateFunctions.removeProtectorCiv(player, forced = true)
                        }
                        else -> error(choice)
                    }
                }
            }
            else -> error("未接入的测试事件：${alert.type}")
        }
        player.popupAlerts.remove(alert)
    }
}
