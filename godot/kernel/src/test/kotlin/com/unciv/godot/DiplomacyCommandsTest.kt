package com.unciv.godot

import com.unciv.Constants
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeRequest
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.view.GameView
import com.unciv.view.TradeView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiplomacyCommandsTest {
    private fun scenario(name: String, war: Boolean = false, configure: (GameInfo) -> Unit = {}): Pair<GameSession, GameInfo> {
        val file = DiplomacyFixtures.file("dip-$name", war, configure)
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }
    private fun other(session: GameSession) = DiplomacyFixtures.enemy(session.game!!)
    private fun options(session: GameSession): JsonObject {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
        return data
    }
    private fun row(session: GameSession) = options(session)["civilizations"]!!.jsonArray
        .map { it.jsonObject }.single { it.text("civId") == other(session).civID }
    private fun token(session: GameSession, alert: Boolean = false) = options(session)[if (alert) "pendingAlert" else "incomingTrade"]!!
        .jsonObject.text(if (alert) "alertToken" else "tradeToken")
    private fun outgoingToken(session: GameSession) = row(session)["outgoingTrades"]!!.jsonArray.first().jsonObject.text("tradeToken")

    private fun reject(session: GameSession, code: String, body: JsonObject) {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val response = session.handle(body)
        assertEquals(response.toString(), code, response["error"]?.jsonObject?.text("code"))
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
    }
    private fun apply(session: GameSession, expected: GameInfo, action: String, vararg args: Pair<String, Any?>, operation: () -> Unit) {
        options(session)
        native(expected, operation)
        val body = request(session, action, *args)
        val revision = session.revision
        val current = session.game
        val response = session.handle(body)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertSame(current, session.game)
        assertEquals(revision + 1, session.revision)
        assertGameplayEquals(action, expected, session.game!!)
        assertEquals(response, session.handle(body))
        reject(session, "REQUEST_REUSED", JsonObject(body + ("unexpected" to JsonPrimitive(true))))
        reject(session, "STALE_STATE", JsonObject(body + ("requestId" to JsonPrimitive("new-${session.revision}"))))
        assertGameplayEquals("重放 $action", expected, session.game!!)
    }

    @Test fun queryIsReadOnlyAndUsesOtherCivsOpinion() {
        val (session, expected) = scenario("query")
        val dto = row(session)
        val player = expected.currentPlayerCiv
        val other = DiplomacyFixtures.enemy(expected)
        assertEquals(other.civName, dto.text("name"))
        assertEquals(other.getDiplomacyManager(player)!!.opinionOfOtherCiv().toDouble(), dto["opinion"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(other.getDiplomacyManager(player)!!.relationshipLevel().name, dto.text("relationship"))
        assertEquals(options(session), options(session))
    }

    @Test fun declareWarMatchesNativeAndReplaysOnce() {
        val (session, expected) = scenario("war")
        apply(session, expected, "diplomacyDeclareWar", "civId" to other(session).civID) {
            expected.currentPlayerCiv.getDiplomacyManager(DiplomacyFixtures.enemy(expected))!!.declareWar()
        }
        reject(session, "CANNOT_DECLARE_WAR", request(session, "diplomacyDeclareWar", "civId" to other(session).civID))
    }

    @Test fun proposalsDoNotSignPeaceOrMoveGold() {
        for ((ours, theirs) in listOf(0 to 0, 100 to 0, 0 to 75, 100 to 75)) {
            val (session, expected) = scenario("proposal-$ours-$theirs", true)
            apply(session, expected, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to ours, "theirGold" to theirs) {
                DiplomacyFixtures.propose(expected, ours, theirs)
            }
            assertEquals(1000, session.game!!.currentPlayerCiv.gold)
            assertTrue(session.game!!.currentPlayerCiv.isAtWarWith(other(session)))
            val staged = other(session).tradeRequests.single().trade
            assertEquals(ours, staged.theirOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount })
            assertEquals(theirs, staged.ourOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount })
            reject(session, "CANNOT_PROPOSE_PEACE", request(session, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0))
        }
    }

    @Test fun retractUsesNativeWithoutDeclineFlag() {
        val (session, expected) = scenario("retract", true) { DiplomacyFixtures.propose(it, 100) }
        apply(session, expected, "diplomacyRetractPeace", "civId" to other(session).civID, "tradeToken" to outgoingToken(session)) {
            val player = expected.currentPlayerCiv
            check(TradeView(player, DiplomacyFixtures.enemy(expected), GameView(expected, player)).tryRetractOffer())
        }
        assertTrue(other(session).tradeRequests.isEmpty())
        assertFalse(other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!.hasFlag(DiplomacyFlags.DeclinedPeace))
    }

    @Test fun incomingAcceptAndDeclineMatchNative() {
        for (choice in listOf("accept", "decline")) {
            val (session, expected) = scenario("incoming-$choice", true) { DiplomacyFixtures.incoming(it, 100, 25) }
            apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to choice) {
                DiplomacyFixtures.tradeDecision(expected, choice)
            }
            assertTrue(session.game!!.currentPlayerCiv.tradeRequests.isEmpty())
            assertEquals(if (choice == "accept") 925 else 1000, session.game!!.currentPlayerCiv.gold)
        }
    }

    @Test fun allFourAlertsUseNativeChoices() {
        val choices = listOf(
            AlertType.DeclarationOfFriendship to listOf("accept", "decline"),
            AlertType.DemandToStopSettlingCitiesNear to listOf("agree", "refuse"),
            AlertType.DemandToNotAttackUs to listOf("agree", "refuseAndDeclareWar"),
            AlertType.Denounced to listOf("dismiss", "declareWar"))
        for ((type, options) in choices) for (choice in options) {
            val (session, expected) = scenario("$type-$choice") { DiplomacyFixtures.alert(it, type) }
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice) {
                DiplomacyFixtures.alertDecision(expected, choice)
            }
            assertTrue(session.game!!.currentPlayerCiv.popupAlerts.isEmpty())
        }
    }

    @Test fun malformedAmountsAndTargetsAreRejectedBeforeBackup() {
        val (session, _) = scenario("arguments", true)
        val valid = request(session, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0)
        for (key in listOf("ourGold", "theirGold")) {
            for (value in listOf(JsonNull, JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(-1), JsonPrimitive(true), JsonPrimitive(2147483648L), JsonArray(emptyList())))
                reject(session, "INVALID_ARGUMENT", JsonObject(valid + (key to value)))
            reject(session, "INVALID_ARGUMENT", JsonObject(valid - key))
        }
        reject(session, "CANNOT_PROPOSE_PEACE", JsonObject(valid + ("ourGold" to JsonPrimitive(1001))))
        for (id in listOf("no-such-civ", session.game!!.currentPlayerCiv.civID))
            reject(session, "DIPLOMACY_TARGET", request(session, "diplomacyDeclareWar", "civId" to id))
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyDeclareWar", "civId" to 5))
    }

    @Test fun sendingUsesTheirDeclaredWarFlagNotOurs() {
        val (session, _) = scenario("cooldown", true) {
            DiplomacyFixtures.enemy(it).getDiplomacyManager(it.currentPlayerCiv)!!.setFlag(DiplomacyFlags.DeclaredWar, 3)
        }
        assertEquals(3, row(session).integer("peaceNegotiationBlockedTurns"))
        reject(session, "CANNOT_PROPOSE_PEACE", request(session, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0))
    }

    @Test fun mixedTradeCannotBeAcceptedButCanBeDeclined() {
        val (session, expected) = scenario("mixed", true) {
            DiplomacyFixtures.incoming(it)
            it.currentPlayerCiv.tradeRequests.first().trade.theirOffers.add(TradeOffer("hidden-city-id", TradeOfferType.City, speed = it.speed))
        }
        assertFalse(options(session).toString().contains("hidden-city-id"))
        reject(session, "UNSUPPORTED", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "decline") {
            DiplomacyFixtures.tradeDecision(expected, "decline")
        }
    }

    @Test fun duplicateOutgoingOffersCannotBeRetractedTogether() {
        val (session, _) = scenario("duplicates", true) { DiplomacyFixtures.propose(it); DiplomacyFixtures.propose(it, 100) }
        reject(session, "UNSUPPORTED", request(session, "diplomacyRetractPeace", "civId" to other(session).civID, "tradeToken" to outgoingToken(session)))
        assertEquals(2, other(session).tradeRequests.size)
    }

    @Test fun tokenIncludesDurationAndQueueIdentity() {
        val (session, _) = scenario("token", true) { DiplomacyFixtures.incoming(it); DiplomacyFixtures.incoming(it, 50) }
        val old = token(session)
        session.game!!.currentPlayerCiv.tradeRequests.first().trade.theirOffers.first().duration++
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyTradeDecision", "tradeToken" to old, "choice" to "decline"))
        assertNotEquals(old, token(session))
    }

    @Test fun nativeGoldToleranceIsPreserved() {
        val (session, expected) = scenario("tolerance", true) {
            DiplomacyFixtures.incoming(it, 1050)
        }
        apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept") {
            DiplomacyFixtures.tradeDecision(expected, "accept")
        }
        assertEquals(-50, session.game!!.currentPlayerCiv.gold)
    }

    @Test fun expiredTradeRequiresExplicitDismiss() {
        val (session, expected) = scenario("expired", true) { DiplomacyFixtures.incoming(it, 2000) }
        options(session)
        assertEquals(1, session.game!!.currentPlayerCiv.tradeRequests.size)
        reject(session, "CANNOT_ACCEPT_TRADE", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "dismiss") {
            DiplomacyFixtures.tradeDecision(expected, "dismiss")
        }
    }

    @Test fun nativeAlliancesTradesAndUnitDisplacementArePreserved() {
        val (session, expected) = scenario("alliances", configure = DiplomacyFixtures::alliances)
        val player = session.game!!.currentPlayerCiv
        val hidden = session.game!!.civilizations.first { it.civName == "China" }
        assertFalse(player.knows(hidden))
        val data = options(session).toString()
        assertFalse(data.contains(hidden.civID))
        assertFalse(data.contains(hidden.civName))
        assertTrue(data.contains("未知文明"))
        assertTrue(row(session)["warWarnings"]!!.jsonArray.size >= 2)
        val scout = player.units.getCivUnits().single { it.name == "Scout" }
        val beforePosition = scout.currentTile.position
        apply(session, expected, "diplomacyDeclareWar", "civId" to other(session).civID) {
            expected.currentPlayerCiv.getDiplomacyManager(DiplomacyFixtures.enemy(expected))!!.declareWar()
        }
        assertNotEquals(beforePosition, scout.currentTile.position)
        assertTrue(player.getDiplomacyManager(other(session))!!.trades.isEmpty())
        for (name in listOf("Greece", "Egypt", "China", "Sidon")) assertTrue(name, player.isAtWarWith(session.game!!.civilizations.first { it.civName == name }))
        val geneva = session.game!!.civilizations.first { it.civName == "Geneva" }
        assertTrue(geneva.isAtWarWith(other(session)))
        assertFalse(player.isAtWarWith(geneva))
    }

    @Test fun cityStatesAreReadOnlyAndUnknownTargetsDoNotLeak() {
        val (session, _) = scenario("visibility") {
            DiplomacyFixtures.addCiv(it, "Geneva", -2, 5)
            DiplomacyFixtures.addCiv(it, "China", 6, 6, false)
        }
        val cityState = session.game!!.civilizations.first { it.isCityState }
        val hidden = session.game!!.civilizations.first { it.civName == "China" }
        val data = options(session)
        val cs = data["civilizations"]!!.jsonArray.map { it.jsonObject }.single { it.text("civId") == cityState.civID }
        assertEquals("cityState", cs.text("type"))
        for (key in listOf("gold", "actions", "cities", "units", "quests")) assertFalse(cs.containsKey(key))
        assertFalse(data.toString().contains(hidden.civID))
        reject(session, "UNSUPPORTED", request(session, "diplomacyDeclareWar", "civId" to cityState.civID))
        reject(session, "UNSUPPORTED", request(session, "diplomacyProposePeace", "civId" to cityState.civID, "ourGold" to 0, "theirGold" to 0))
        reject(session, "DIPLOMACY_TARGET", request(session, "diplomacyDeclareWar", "civId" to hidden.civID))
        assertEquals(AlertType.FirstContact, session.game!!.currentPlayerCiv.popupAlerts.first().type)
        session.game!!.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Denounced, hidden.civID))
        assertEquals(JsonNull, options(session)["pendingAlert"])
        session.game!!.currentPlayerCiv.popupAlerts.removeAll { it.type == AlertType.FirstContact }
        assertFalse(options(session).toString().contains(hidden.civID))
        reject(session, "DIPLOMACY_TARGET", request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "dismiss"))
        assertFalse(session.game!!.currentPlayerCiv.knows(hidden))
    }

    @Test fun peaceTreatyAndRulesBlockAllWarChoices() {
        for (type in listOf(AlertType.Denounced, AlertType.DemandToNotAttackUs)) {
            val (session, _) = scenario("treaty-$type") {
                val p = it.currentPlayerCiv
                val o = DiplomacyFixtures.enemy(it)
                p.getDiplomacyManager(o)!!.trades.add(DiplomacyFixtures.peace(it))
                o.getDiplomacyManager(p)!!.trades.add(DiplomacyFixtures.peace(it))
                DiplomacyFixtures.alert(it, type)
            }
            val choice = if (type == AlertType.Denounced) "declareWar" else "refuseAndDeclareWar"
            reject(session, "CANNOT_DECLARE_WAR", request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice))
            assertTrue(options(session)["pendingAlert"]!!.jsonObject["choices"]!!.jsonArray.first().jsonObject.boolean("enabled"))
            session.game!!.currentPlayerCiv.popupAlerts.clear()
            reject(session, "CANNOT_DECLARE_WAR", request(session, "diplomacyDeclareWar", "civId" to other(session).civID))
        }
    }

    @Test fun onlyOurCooldownDoesNotBlockSending() {
        val (session, expected) = scenario("our-cooldown", true) {
            it.currentPlayerCiv.getDiplomacyManager(DiplomacyFixtures.enemy(it))!!.setFlag(DiplomacyFlags.DeclaredWar, 7)
        }
        apply(session, expected, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0) {
            DiplomacyFixtures.propose(expected)
        }
    }

    @Test fun malformedPeaceStructuresNeverAcceptPartialTrades() {
        val mutations: List<(Trade) -> Unit> = listOf(
            { it.theirOffers.clear() },
            { it.ourOffers.first().amount = 2 },
            { it.ourOffers.first().duration = 0 },
            { it.ourOffers.first().duration++ },
            { it.ourOffers.add(it.ourOffers.first().copy()) },
            { it.ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, 0, duration = -1)) },
            { it.theirOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, 5, duration = 5)) },
            { it.theirOffers.add(TradeOffer("hidden-third-civ", TradeOfferType.WarDeclaration, duration = -1)) })
        for ((index, mutate) in mutations.withIndex()) {
            val (session, _) = scenario("shape-$index", true) { g -> DiplomacyFixtures.incoming(g); mutate(g.currentPlayerCiv.tradeRequests.first().trade) }
            reject(session, "UNSUPPORTED", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        }
    }

    @Test fun nondefaultTreatyDurationAndQueueTailAreRetained() {
        val (session, expected) = scenario("duration", true) {
            DiplomacyFixtures.incoming(it, 10, 30)
            it.currentPlayerCiv.tradeRequests.first().trade.let { trade -> (trade.ourOffers + trade.theirOffers).filter { o -> o.type == TradeOfferType.Treaty }.forEach { o -> o.duration = 17 } }
            DiplomacyFixtures.incoming(it, 33)
        }
        apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept") { DiplomacyFixtures.tradeDecision(expected, "accept") }
        assertEquals(17, session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!.turnsToPeaceTreaty())
        assertEquals(1, session.game!!.currentPlayerCiv.tradeRequests.size)
        assertEquals(33, session.game!!.currentPlayerCiv.tradeRequests.first().trade.ourOffers.single { it.type == TradeOfferType.Gold }.amount)
    }

    @Test fun queueReorderAndCrossSessionTokensAreRejected() {
        val file = DiplomacyFixtures.file("dip-token-sessions", true) { DiplomacyFixtures.incoming(it, 5); DiplomacyFixtures.incoming(it, 25) }
        val a = load(file)
        val b = load(file)
        val old = token(a)
        reject(b, "DIPLOMACY_REQUEST", request(b, "diplomacyTradeDecision", "tradeToken" to old, "choice" to "decline"))
        a.game!!.currentPlayerCiv.tradeRequests.reverse()
        reject(a, "DIPLOMACY_REQUEST", request(a, "diplomacyTradeDecision", "tradeToken" to old, "choice" to "decline"))
        val refreshed = token(a)
        run(a, "save", "name" to "dip-token-version")
        reject(a, "DIPLOMACY_REQUEST", request(a, "diplomacyTradeDecision", "tradeToken" to refreshed, "choice" to "decline"))
    }

    @Test fun alertsOnlyConsumeHeadAndRejectInvalidChoices() {
        val (session, expected) = scenario("alert-order") {
            DiplomacyFixtures.alert(it, AlertType.DeclarationOfFriendship)
            DiplomacyFixtures.alert(it, AlertType.Denounced)
        }
        val old = token(session, true)
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyAlertDecision", "alertToken" to old, "choice" to "declareWar"))
        apply(session, expected, "diplomacyAlertDecision", "alertToken" to old, "choice" to "decline") { DiplomacyFixtures.alertDecision(expected, "decline") }
        assertEquals(AlertType.Denounced, session.game!!.currentPlayerCiv.popupAlerts.single().type)
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyAlertDecision", "alertToken" to old, "choice" to "dismiss"))
        reject(session, "UNSUPPORTED", request(session, "acknowledge"))
    }

    @Test fun expiredFriendshipAndUnknownSourcesStayExplicit() {
        val (session, expected) = scenario("friendship-expired", true) { DiplomacyFixtures.alert(it, AlertType.DeclarationOfFriendship) }
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "accept"))
        apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "dismiss") { DiplomacyFixtures.alertDecision(expected, "dismiss") }
        session.game!!.currentPlayerCiv.tradeRequests.add(TradeRequest("unavailable-source", DiplomacyFixtures.peace(session.game!!)))
        reject(session, "DIPLOMACY_TARGET", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "dismiss"))
        assertFalse(options(session).toString().contains("unavailable-source"))
    }

    @Test fun allDiplomacyStatesRoundTripIndependently() {
        for (state in listOf("outgoing", "incoming", "accepted", "declined", "friendship", "promise")) {
            val (session, expected) = scenario("roundtrip-$state", state !in listOf("friendship", "promise")) {
                when (state) {
                    "outgoing" -> DiplomacyFixtures.propose(it, 50, 10)
                    "incoming", "accepted", "declined" -> {
                        DiplomacyFixtures.incoming(it, 50, 10)
                        if (state != "incoming") DiplomacyFixtures.tradeDecision(it, if (state == "accepted") "accept" else "decline")
                    }
                    else -> {
                        DiplomacyFixtures.alert(it, if (state == "friendship") AlertType.DeclarationOfFriendship else AlertType.DemandToNotAttackUs)
                        DiplomacyFixtures.alertDecision(it, if (state == "friendship") "accept" else "agree")
                    }
                }
            }
            val old = options(session)
            val saved = run(session, "save", "name" to "dip-roundtrip-$state")
            run(session, "load", "path" to saved.text("savedPath"))
            val independent = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(expected, true))
            assertGameplayEquals(state, independent, session.game!!)
            if (state == "incoming") assertNotEquals(old["incomingTrade"]!!.jsonObject.text("tradeToken"), token(session))
            if (state == "outgoing") assertNotEquals(old["civilizations"]!!.jsonArray.first().jsonObject["outgoingTrades"]!!.jsonArray.first().jsonObject.text("tradeToken"), outgoingToken(session))
        }
    }

    @Test fun normalAiTurnProcessesProposalsAndMatchesNative() {
        for (accept in listOf(true, false)) {
            val (session, expected) = scenario("ai-$accept", true)
            val ours = if (accept) 1000 else 0
            val theirs = if (accept) 0 else 1000
            apply(session, expected, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to ours, "theirGold" to theirs) {
                DiplomacyFixtures.propose(expected, ours, theirs)
            }
            val next = DiplomacyFixtures.nextTurn(expected)
            run(session, "nextTurn")
            assertGameplayEquals("AI 处理 $accept", next, session.game!!)
            assertTrue(other(session).tradeRequests.none { it.requestingCiv == session.game!!.currentPlayerCiv.civID })
            assertEquals(!accept, session.game!!.currentPlayerCiv.isAtWarWith(other(session)))
            if (!accept) assertTrue(session.game!!.currentPlayerCiv.tradeRequests.isNotEmpty() || session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!.hasFlag(DiplomacyFlags.DeclinedPeace))
        }
    }

    @Test fun emitIndependentDiplomacySmokeFixtures() { DiplomacyFixtures.emit() }

    @Test fun bilateralModifiersHaveExplicitDirection() {
        val (session, _) = scenario("modifiers")
        val ours = session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!
        val theirs = other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!
        val modifiers = row(session)["modifiers"]!!.jsonArray.map { it.jsonObject }
        for ((side, manager) in listOf("us" to ours, "them" to theirs)) {
            assertEquals(manager.diplomaticModifiers.toMap(), modifiers.filter { it.text("observer") == side }
                .associate { it.text("name") to it["value"]!!.jsonPrimitive.float })
        }
    }

    @Test fun basePermissionsRejectEveryDiplomacyWriteBeforeBackup() {
        for (mode in listOf("online", "human", "mods")) {
            val (session, _) = scenario("permissions-$mode", true)
            val g = session.game!!
            when (mode) {
                "online" -> g.gameParameters.isOnlineMultiplayer = true
                "human" -> other(session).playerType = com.unciv.logic.civilization.PlayerType.Human
                else -> g.gameParameters.mods.add("unsupported-test-mod")
            }
            for (action in listOf("diplomacyDeclareWar", "diplomacyProposePeace", "diplomacyRetractPeace", "diplomacyTradeDecision", "diplomacyAlertDecision"))
                reject(session, if (mode == "mods") "UNSUPPORTED_MOD" else "UNSUPPORTED_GAME", request(session, action))
        }
    }

    @Test fun immutableRelationshipsBlockWarAndPeaceWithoutMutation() {
        for (war in listOf(false, true)) {
            val (session, _) = scenario("fixed-relations-$war", war)
            val g = session.game!!
            g.ruleset = g.ruleset.clone()
            g.ruleset.modOptions = com.unciv.models.ruleset.ModOptions().apply {
                uniques.add("Diplomatic relationships cannot change")
            }
            if (war) {
                reject(session, "CANNOT_PROPOSE_PEACE", request(session, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0))
                DiplomacyFixtures.incoming(g)
                reject(session, "CANNOT_ACCEPT_TRADE", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
            } else {
                reject(session, "CANNOT_DECLARE_WAR", request(session, "diplomacyDeclareWar", "civId" to other(session).civID))
                for (type in listOf(AlertType.Denounced, AlertType.DemandToNotAttackUs)) {
                    g.currentPlayerCiv.popupAlerts.clear()
                    DiplomacyFixtures.alert(g, type)
                    reject(session, "CANNOT_DECLARE_WAR", request(session, "diplomacyAlertDecision", "alertToken" to token(session, true),
                        "choice" to if (type == AlertType.Denounced) "declareWar" else "refuseAndDeclareWar"))
                }
            }
        }
    }

    @Test fun defeatedSourcesOnlyAllowExplicitCleanup() {
        for (type in listOf(AlertType.DeclarationOfFriendship, AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToNotAttackUs, AlertType.Denounced)) {
            val (session, expected) = scenario("defeated-$type")
            for (g in listOf(session.game!!, expected)) {
                DiplomacyFixtures.enemy(g).cities = emptyList()
                DiplomacyFixtures.alert(g, type)
            }
            assertTrue(other(session).isDefeated())
            assertTrue(options(session)["civilizations"]!!.jsonArray.none { it.jsonObject.text("civId") == other(session).civID })
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "dismiss") { DiplomacyFixtures.alertDecision(expected, "dismiss") }
            reject(session, "CANNOT_DECLARE_WAR", request(session, "diplomacyDeclareWar", "civId" to other(session).civID))
            reject(session, "CANNOT_PROPOSE_PEACE", request(session, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0))
        }
    }

    @Test fun warUniqueRunsOnceAndPeacePreservesNativeTriggerBehavior() {
        for (branch in listOf("ordinary", "Denounced", "DemandToNotAttackUs")) {
            val (session, expected) = scenario("unique-$branch")
            for (g in listOf(session.game!!, expected)) native(g) {
                g.ruleset = g.ruleset.clone()
                val bonus = com.unciv.models.ruleset.Building().apply {
                    name = "Diplomacy Test Bonus"; cost = 50; ruleset = g.ruleset
                    uniques.add("Gain [17] [Gold] <upon declaring war on [All] Civilizations>")
                    uniques.add("Gain [23] [Gold] <upon signing a peace treaty with [All] Civilizations>")
                }
                g.ruleset.buildings[bonus.name] = bonus
                g.currentPlayerCiv.cities.first().cityConstructions.addBuilding(bonus)
                if (branch != "ordinary") DiplomacyFixtures.alert(g, AlertType.valueOf(branch))
            }
            if (branch == "ordinary") apply(session, expected, "diplomacyDeclareWar", "civId" to other(session).civID) {
                expected.currentPlayerCiv.getDiplomacyManager(DiplomacyFixtures.enemy(expected))!!.declareWar()
            } else apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true),
                "choice" to if (branch == "Denounced") "declareWar" else "refuseAndDeclareWar") {
                DiplomacyFixtures.alertDecision(expected, if (branch == "Denounced") "declareWar" else "refuseAndDeclareWar")
            }
            assertEquals("宣战 Unique 恰好一次：$branch", 1017, session.game!!.currentPlayerCiv.gold)
            for (g in listOf(session.game!!, expected)) { g.currentPlayerCiv.popupAlerts.clear(); DiplomacyFixtures.incoming(g) }
            apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept") { DiplomacyFixtures.tradeDecision(expected, "accept") }
            // 原生 makePeace 以外层奖励参数 23 而非条件 All 过滤文明，当前不会发放该奖励。
            // 完整差分已验证两端一致；不在网关补偿或修改 core。
            assertEquals("保留原生和平触发条件行为", 1017, session.game!!.currentPlayerCiv.gold)
        }
    }

    @Test fun alliedCityStatesMakePeaceThroughNativeTrade() {
        val (session, expected) = scenario("allied-peace") { g ->
            DiplomacyFixtures.alliances(g)
            g.currentPlayerCiv.getDiplomacyManager(DiplomacyFixtures.enemy(g))!!.declareWar()
            g.civilizations.forEach { it.popupAlerts.clear() }
            DiplomacyFixtures.incoming(g, 30, 10)
        }
        apply(session, expected, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept") { DiplomacyFixtures.tradeDecision(expected, "accept") }
        val g = session.game!!
        assertFalse(g.currentPlayerCiv.isAtWarWith(g.civilizations.first { it.civName == "Sidon" }))
        assertFalse(other(session).isAtWarWith(g.civilizations.first { it.civName == "Geneva" }))
    }

    @Test fun thirdPartyInboxesAndMixedOutgoingRemainUntouched() {
        val (session, expected) = scenario("outgoing-private", true) { g ->
            DiplomacyFixtures.addCiv(g, "Egypt", -6, -6)
            g.civilizations.forEach { it.popupAlerts.clear() }
            DiplomacyFixtures.propose(g, 10)
            DiplomacyFixtures.enemy(g).tradeRequests.add(TradeRequest("third-party-secret", Trade()))
        }
        assertFalse(options(session).toString().contains("third-party-secret"))
        val old = outgoingToken(session)
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyRetractPeace", "civId" to other(session).civID, "tradeToken" to "wrong"))
        apply(session, expected, "diplomacyRetractPeace", "civId" to other(session).civID, "tradeToken" to old) {
            check(TradeView(expected.currentPlayerCiv, DiplomacyFixtures.enemy(expected), GameView(expected, expected.currentPlayerCiv)).tryRetractOffer())
        }
        assertEquals("third-party-secret", other(session).tradeRequests.single().requestingCiv)
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyRetractPeace", "civId" to other(session).civID, "tradeToken" to old))
        DiplomacyFixtures.propose(session.game!!)
        other(session).tradeRequests.last().trade.ourOffers.add(TradeOffer("hidden-city", TradeOfferType.City, duration = -1))
        reject(session, "UNSUPPORTED", request(session, "diplomacyRetractPeace", "civId" to other(session).civID, "tradeToken" to outgoingToken(session)))
    }

    @Test fun blockingAlertAllowsQueriesButNotTradeDecisionOrOrdinaryActions() {
        val (session, _) = scenario("blocked", true) {
            DiplomacyFixtures.incoming(it)
            DiplomacyFixtures.alert(it, AlertType.RecapturedCivilian)
        }
        reject(session, "PENDING_DECISION", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        reject(session, "PENDING_DECISION", request(session, "diplomacyProposePeace", "civId" to other(session).civID, "ourGold" to 0, "theirGold" to 0))
        reject(session, "UNSUPPORTED", request(session, "acknowledge"))
    }
}
