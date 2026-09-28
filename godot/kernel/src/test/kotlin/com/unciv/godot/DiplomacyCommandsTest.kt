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
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.view.GameView
import com.unciv.view.TradeView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

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

    @Test fun allTenAlertsUseNativeChoices() {
        assertEquals(DiplomacyCommands.alertTypes, DiplomacyFixtures.alertChoices.map { it.first }.toSet())
        for ((type, options) in DiplomacyFixtures.alertChoices) for (choice in options) {
            val (session, expected) = scenario("$type-$choice") { DiplomacyFixtures.alert(it, type) }
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice) {
                DiplomacyFixtures.alertDecision(expected, choice)
            }
            assertTrue(session.game!!.currentPlayerCiv.popupAlerts.isEmpty())
            val saved = run(session, "save", "name" to "dip-choice-$type-$choice")
            run(session, "load", "path" to saved.text("savedPath"))
            assertGameplayEquals("处置后独立重载 $type/$choice",
                UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(expected, true)), session.game!!)
        }
    }

    @Test fun spyChoicesKeepNativeEffectsSpeedAndExistingAssignments() {
        for (type in DiplomacyFixtures.spyAlerts) for (choice in listOf("agree", "refuse"))
            for (speed in listOf("Standard", "Quick", "Epic")) {
                val (session, expected) = scenario("spy-$type-$choice-$speed") {
                    it.gameParameters.speed = speed
                    DiplomacyFixtures.alert(it, type)
                    val player = it.currentPlayerCiv
                    val ours = player.getDiplomacyManager(DiplomacyFixtures.enemy(it))!!
                    val theirs = ours.otherCivDiplomacy()
                    ours.setModifier(DiplomaticModifiers.UnacceptableDemands, -3f)
                    theirs.setModifier(DiplomaticModifiers.RefusedToNotSendingSpiesToUs, -4f)
                    // 对方收件箱已存在同类型回应，原生只按类型和来源去重弹窗。
                    DiplomacyFixtures.enemy(it).popupAlerts.add(PopupAlert(
                        if (choice == "agree") AlertType.AcceptingDemand else AlertType.RejectingDemand, player.civID))
                }
                val game = session.game!!
                val player = game.currentPlayerCiv
                val ours = player.getDiplomacyManager(other(session))!!
                val theirs = ours.otherCivDiplomacy()
                val spy = player.espionageManager.spyList.single()
                val city = spy.getCity().id
                val action = spy.action
                val turns = spy.turnsRemainingForAction
                val betrayal = theirs.diplomaticModifiers[DiplomaticModifiers.BetrayedPromiseToNotSendingSpiesToUs.name]
                val notifications = other(session).notifications.size
                apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice) {
                    DiplomacyFixtures.alertDecision(expected, choice)
                }
                assertSame(spy, player.espionageManager.spyList.single())
                assertEquals(city, spy.getCity().id)
                assertEquals(action, spy.action)
                assertEquals(turns, spy.turnsRemainingForAction)
                assertFalse(player.isAtWarWith(other(session)))
                assertFalse(ours.hasFlag(Demand.DontSpyOnUs.agreedToDemand))
                assertEquals(betrayal, theirs.diplomaticModifiers[DiplomaticModifiers.BetrayedPromiseToNotSendingSpiesToUs.name])
                assertEquals(if (choice == "agree") -13f else -23f, ours.diplomaticModifiers[DiplomaticModifiers.UnacceptableDemands.name]!!, 0f)
                assertEquals(if (choice == "agree") -4f else -19f, theirs.diplomaticModifiers[DiplomaticModifiers.RefusedToNotSendingSpiesToUs.name]!!, 0f)
                val duration = (100 * game.speed.modifier).roundToInt()
                if (choice == "agree") {
                    assertEquals(duration, theirs.getFlag(Demand.DontSpyOnUs.agreedToDemand))
                    assertEquals(dto("type" to "DontSpyOnUs", "giver" to "us", "turns" to duration), row(session)["promises"]!!.jsonArray.single())
                } else assertEquals(duration, theirs.getFlag(Demand.DontSpyOnUs.willIgnoreViolation))
                if (type == AlertType.SpyingOnUsDespiteOurPromise) assertTrue(theirs.hasFlag(Demand.DontSpyOnUs.willIgnoreViolation))
                assertEquals(1, other(session).popupAlerts.size)
                // 原生 addNotification 对 AI 不保存通知，但外交回应弹窗仍保留。
                assertEquals(notifications, other(session).notifications.size)
            }
    }

    @Test fun discoveredSpyFlagsGenerateBothAlertsThroughNativeAiTurns() {
        for (broken in listOf(false, true)) for (choice in listOf("agree", "refuse")) {
            val (session, initial) = scenario("spy-ai-$broken-$choice") { game ->
                val player = game.currentPlayerCiv
                val other = DiplomacyFixtures.enemy(game)
                // 仅注入发现标志和先前承诺；事件与违约惩罚由完整原生回合产生，不模拟偷科技。
                other.getDiplomacyManager(player)!!.setFlag(DiplomacyFlags.DiscoveredSpiesInOurCities, 30)
                if (broken) other.getDiplomacyManager(player)!!.setFlag(DiplomacyFlags.AgreedToNotSendSpies, 50)
                player.units.getCivUnits().filter { it.name == "Archer" }.toList().forEach { it.destroy() }
                com.unciv.logic.trade.TradeLogic(player, other).apply { currentTrade.set(DiplomacyFixtures.peace(game)) }.acceptTrade()
            }
            val expected = DiplomacyFixtures.nextTurn(initial)
            run(session, "nextTurn")
            assertGameplayEquals("原生发现标志触发 AI 间谍要求", expected, session.game!!)
            val type = if (broken) AlertType.SpyingOnUsDespiteOurPromise else AlertType.DemandToStopSpyingOnUs
            assertEquals(type, session.game!!.currentPlayerCiv.popupAlerts.first().type)
            val manager = other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!
            assertFalse(manager.hasFlag(DiplomacyFlags.DiscoveredSpiesInOurCities))
            if (broken) {
                assertFalse(manager.hasFlag(DiplomacyFlags.AgreedToNotSendSpies))
                assertTrue(manager.hasFlag(DiplomacyFlags.IgnoreThemSendingSpies))
                // 完整 AI 回合在生成 -20 后按原生规则衰减 1/8。
                assertEquals(-20f + 1 / 8f, manager.diplomaticModifiers[DiplomaticModifiers.BetrayedPromiseToNotSendingSpiesToUs.name]!!, 0f)
            }
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice) {
                DiplomacyFixtures.alertDecision(expected, choice)
            }
        }
    }

    @Test fun spyTokensBindTypeSourceQueueSessionAndRevision() {
        val file = DiplomacyFixtures.file("spy-token") {
            DiplomacyFixtures.alert(it, AlertType.DemandToStopSpyingOnUs)
            DiplomacyFixtures.alert(it, AlertType.SpyingOnUsDespiteOurPromise)
        }
        val a = load(file)
        val b = load(file)
        val old = token(a, true)
        reject(b, "DIPLOMACY_REQUEST", request(b, "diplomacyAlertDecision", "alertToken" to old, "choice" to "agree"))
        val queue = a.game!!.currentPlayerCiv.popupAlerts
        queue.reverse()
        reject(a, "DIPLOMACY_REQUEST", request(a, "diplomacyAlertDecision", "alertToken" to old, "choice" to "agree"))
        queue.reverse()
        queue[0] = PopupAlert(AlertType.DemandToStopSpyingOnUs, "unknown-source")
        reject(a, "DIPLOMACY_REQUEST", request(a, "diplomacyAlertDecision", "alertToken" to old, "choice" to "agree"))
        queue[0] = PopupAlert(AlertType.DemandToStopSpyingOnUs, other(a).civID)
        run(a, "save", "name" to "spy-old-revision")
        reject(a, "DIPLOMACY_REQUEST", request(a, "diplomacyAlertDecision", "alertToken" to old, "choice" to "agree"))
    }

    @Test fun invalidSpySourcesAndStrictArgumentsNeverWriteOrLeak() {
        val (session, _) = scenario("spy-invalid") {
            DiplomacyFixtures.addCiv(it, "China", 6, 6, false)
            DiplomacyFixtures.addCiv(it, "Geneva", -2, 5)
            it.currentPlayerCiv.popupAlerts.clear()
        }
        val player = session.game!!.currentPlayerCiv
        for (type in DiplomacyFixtures.spyAlerts) {
            for (source in listOf("China", "missing-secret", player.civID, "Geneva", "Greece@secret", "")) {
                player.popupAlerts.add(PopupAlert(type, source))
                val data = options(session)
                for (hidden in listOf("China", "missing-secret", "@secret")) assertFalse(data.toString().contains(hidden))
                assertTrue(data["pendingAlert"]!!.jsonObject["choices"]!!.jsonArray.none { it.jsonObject.boolean("enabled") })
                for (choice in listOf("agree", "refuse")) reject(session, if (source == "Geneva") "UNSUPPORTED" else "DIPLOMACY_TARGET",
                    request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice))
                player.popupAlerts.removeAt(0)
            }
            player.popupAlerts.add(PopupAlert(type, other(session).civID))
            val valid = request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "agree")
            for (key in listOf("alertToken", "choice")) {
                reject(session, "INVALID_ARGUMENT", JsonObject(valid - key))
                for (value in listOf(JsonNull, JsonPrimitive(""), JsonPrimitive(" \t"), JsonPrimitive(1), JsonPrimitive(false), dto(), JsonArray(emptyList())))
                    reject(session, "INVALID_ARGUMENT", JsonObject(valid + (key to value)))
            }
            for (choice in listOf("dismiss", "declareWar", "acknowledge"))
                reject(session, "INVALID_ARGUMENT", JsonObject(valid + ("choice" to JsonPrimitive(choice))))
            reject(session, "INVALID_ARGUMENT", JsonObject(valid + ("civId" to JsonPrimitive("Greece"))))
            reject(session, "INVALID_ARGUMENT", request(session, "diplomacyOptions", "alertToken" to "forged"))
            reject(session, "PENDING_DECISION", request(session, "nextTurn"))
            reject(session, "UNSUPPORTED", request(session, "acknowledge"))
            player.popupAlerts.removeAt(0)
        }
    }

    @Test fun religionPromiseAndRefusalKeepNativeDirection() {
        for (choice in listOf("agree", "refuse")) {
            val (session, expected) = scenario("religion-$choice") { DiplomacyFixtures.alert(it, AlertType.DemandToStopSpreadingReligion) }
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice) {
                DiplomacyFixtures.alertDecision(expected, choice)
            }
            val player = session.game!!.currentPlayerCiv
            val ours = player.getDiplomacyManager(other(session))!!
            val theirs = other(session).getDiplomacyManager(player)!!
            assertFalse(player.isAtWarWith(other(session)))
            assertFalse(ours.hasFlag(Demand.DoNotSpreadReligion.agreedToDemand))
            val duration = (100 * session.game!!.speed.modifier).roundToInt()
            if (choice == "agree") {
                assertEquals(duration, theirs.getFlag(Demand.DoNotSpreadReligion.agreedToDemand))
                assertEquals(dto("type" to "DoNotSpreadReligion", "giver" to "us", "turns" to duration),
                    row(session)["promises"]!!.jsonArray.single())
            } else {
                assertEquals(duration, theirs.getFlag(Demand.DoNotSpreadReligion.willIgnoreViolation))
                assertEquals(-15f, theirs.diplomaticModifiers[DiplomaticModifiers.RefusedToNotSpreadReligionToUs.name]!!, 0f)
            }
        }
    }

    @Test fun cityStateChoicesPreserveInfluenceProtectionAndWarReason() {
        for (type in DiplomacyFixtures.cityStateAlerts) for (choice in listOf("declareWar", "support", "withdrawProtection")) {
            val (session, expected) = scenario("minor-$type-$choice") { DiplomacyFixtures.alert(it, type) }
            val player = session.game!!.currentPlayerCiv
            val cs = session.game!!.getCivilization("Geneva")
            val diplo = cs.getDiplomacyManager(player)!!
            val influence = NativeDiplomacyBridge.rawInfluence(diplo)
            val status = diplo.diplomaticStatus
            val event = options(session)["pendingAlert"]!!.jsonObject
            assertEquals("Greece", event.text("civId"))
            assertEquals("Geneva", event["cityState"]!!.jsonObject.text("civId"))
            assertTrue(event.text("message").contains("Geneva"))
            val pending = run(session, "snapshot")["snapshot"]!!.jsonObject["pending"]!!.jsonArray
                .single { it.jsonObject.text("kind") == "diplomacyAlert" }.jsonObject
            assertTrue(pending.boolean("supported"))
            assertEquals("Greece", pending.text("target"))
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice) {
                DiplomacyFixtures.alertDecision(expected, choice)
            }
            // 原生宣战的共同敌人奖励 +10，再叠加 AlertPopup 的 +20。
            assertEquals(influence + when (choice) { "declareWar" -> 30f; "withdrawProtection" -> -20f; else -> 0f }, NativeDiplomacyBridge.rawInfluence(diplo), 0f)
            assertEquals(choice == "declareWar", player.isAtWarWith(other(session)))
            if (choice == "withdrawProtection") {
                assertEquals(DiplomaticStatus.Peace, diplo.diplomaticStatus)
                assertEquals(20, diplo.getFlag(DiplomacyFlags.RecentlyWithdrewProtection))
                assertTrue(player.notifications.any { it.text == "You have broken your Pledge to Protect [Geneva]!" })
            } else {
                assertEquals(status, diplo.diplomaticStatus)
                val theirs = other(session).getDiplomacyManager(player)!!
                assertEquals(25, theirs.getFlag(DiplomacyFlags.RememberSidedWithProtectedMinor))
                assertEquals(-5f, theirs.diplomaticModifiers[DiplomaticModifiers.SidedWithProtectedMinor.name]!!, 0f)
                if (choice == "declareWar") assertTrue(player.notifications.any { it.text == "We have joined [Geneva] in the war against [Greece]!" })
            }
        }
    }

    @Test fun cityStateWarChoicesRespectWarTreatiesAndFixedRules() {
        for (type in DiplomacyFixtures.cityStateAlerts) for (mode in listOf("war", "treaty", "fixed")) {
            val (session, expected) = scenario("minor-$mode-$type", mode == "war") { DiplomacyFixtures.alert(it, type) }
            for (g in listOf(session.game!!, expected)) {
                if (mode == "treaty") {
                    val p = g.currentPlayerCiv
                    val o = DiplomacyFixtures.enemy(g)
                    p.getDiplomacyManager(o)!!.trades.add(DiplomacyFixtures.peace(g))
                    o.getDiplomacyManager(p)!!.trades.add(DiplomacyFixtures.peace(g))
                } else if (mode == "fixed") {
                    g.ruleset = g.ruleset.clone()
                    g.ruleset.modOptions = com.unciv.models.ruleset.ModOptions().apply { uniques.add("Diplomatic relationships cannot change") }
                }
            }
            val choices = options(session)["pendingAlert"]!!.jsonObject["choices"]!!.jsonArray.map { it.jsonObject }
            if (mode == "war") assertFalse(choices.any { it.text("id") == "declareWar" })
            else assertFalse(choices.single { it.text("id") == "declareWar" }.boolean("enabled"))
            reject(session, if (mode == "war") "INVALID_ARGUMENT" else "CANNOT_DECLARE_WAR",
                request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "declareWar"))
            assertTrue(choices.filter { it.text("id") != "declareWar" }.all { it.boolean("enabled") })
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "support") {
                DiplomacyFixtures.alertDecision(expected, "support")
            }
        }
    }

    @Test fun malformedOrHiddenCompositeReferencesAreReadOnlyAndNeverLeak() {
        val (session, _) = scenario("minor-hidden") {
            DiplomacyFixtures.protectedCityState(it)
            DiplomacyFixtures.addCiv(it, "China", 6, 6, false)
            DiplomacyFixtures.addCiv(it, "Sidon", 2, -5, false)
        }
        for (value in listOf("Greece", "@Geneva", "Greece@", "Greece@Geneva@secret", "China@Geneva", "Greece@Sidon", "Greece@China", "Greece@Greece", "no-source@Geneva")) {
            session.game!!.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.AttackedProtectedMinor, value))
            val data = options(session)
            for (secret in listOf("China", "Sidon", "secret", "no-source")) assertFalse(data.toString(), data.toString().contains(secret))
            val event = data["pendingAlert"]!!.jsonObject
            assertTrue(event["choices"]!!.jsonArray.none { it.jsonObject.boolean("enabled") })
            for (choice in listOf("declareWar", "support", "withdrawProtection")) reject(session, "DIPLOMACY_TARGET",
                request(session, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to choice))
            val pending = run(session, "snapshot")["snapshot"]!!.jsonObject["pending"]!!.jsonArray
                .single { it.jsonObject.text("kind") == "diplomacyAlert" }.jsonObject
            assertFalse(pending.boolean("supported"))
            for (secret in listOf("China", "Sidon", "secret", "no-source")) assertFalse(pending.toString().contains(secret))
            session.game!!.currentPlayerCiv.popupAlerts.removeAt(0)
        }
    }

    @Test fun newPendingEventsSurviveReloadAndOnlyConsumeQueueHead() {
        for (type in listOf(AlertType.DemandToStopSpreadingReligion) + DiplomacyFixtures.cityStateAlerts + DiplomacyFixtures.spyAlerts) {
            val (session, initial) = scenario("pending-reload-$type") {
                DiplomacyFixtures.alert(it, type)
                DiplomacyFixtures.alert(it, AlertType.Denounced)
            }
            val old = token(session, true)
            reject(session, "PENDING_DECISION", request(session, "nextTurn"))
            reject(session, "UNSUPPORTED", request(session, "acknowledge"))
            val saved = run(session, "save", "name" to "dip-pending-$type")
            run(session, "load", "path" to saved.text("savedPath"))
            val expected = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(initial, true))
            assertGameplayEquals("未处理事件独立重载 $type", expected, session.game!!)
            val choice = if (type in DiplomacyFixtures.cityStateAlerts) "support" else "agree"
            assertNotEquals(old, token(session, true))
            reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyAlertDecision", "alertToken" to old, "choice" to choice))
            val fresh = token(session, true)
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to fresh, "choice" to choice) {
                DiplomacyFixtures.alertDecision(expected, choice)
            }
            assertEquals(AlertType.Denounced, session.game!!.currentPlayerCiv.popupAlerts.single().type)
            reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyAlertDecision", "alertToken" to fresh, "choice" to choice))
        }
    }

    @Test fun naturallyGeneratedCityStateAlertsUseTheSameChoices() {
        for (type in DiplomacyFixtures.cityStateAlerts) {
            val (session, expected) = scenario("native-event-$type") { g ->
                val cs = DiplomacyFixtures.protectedCityState(g, type == AlertType.AttackedAllyMinor)
                val aggressor = DiplomacyFixtures.enemy(g)
                if (type == AlertType.BulliedProtectedMinor) cs.cityStateFunctions.tributeGold(aggressor)
                else aggressor.getDiplomacyManager(cs)!!.declareWar()
                assertEquals(type, g.currentPlayerCiv.popupAlerts.first().type)
            }
            apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "support") {
                DiplomacyFixtures.alertDecision(expected, "support")
            }
        }
    }

    @Test fun rawWarRewardDoesNotPrematurelyRecalculateAlliance() {
        val (session, expected) = scenario("raw-reward") {
            DiplomacyFixtures.alert(it, AlertType.AttackedProtectedMinor)
            it.getCivilization("Geneva").getDiplomacyManager(it.currentPlayerCiv)!!.setInfluence(35f)
        }
        apply(session, expected, "diplomacyAlertDecision", "alertToken" to token(session, true), "choice" to "declareWar") {
            DiplomacyFixtures.alertDecision(expected, "declareWar")
        }
        val cs = session.game!!.getCivilization("Geneva")
        assertEquals(65f, NativeDiplomacyBridge.rawInfluence(cs.getDiplomacyManager(session.game!!.currentPlayerCiv)!!), 0f)
        assertNull("原 UI 直接写字段，额外奖励跨过 60 也不立即重算盟友", cs.allyCiv)
    }

    @Test fun compositeTokenBindsBothParties() {
        val (session, _) = scenario("composite-token") { DiplomacyFixtures.alert(it, AlertType.AttackedProtectedMinor) }
        val old = token(session, true)
        session.game!!.currentPlayerCiv.popupAlerts[0] = PopupAlert(AlertType.AttackedProtectedMinor, "Greece@unavailable-city-state")
        assertNotEquals(old, token(session, true))
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyAlertDecision", "alertToken" to old, "choice" to "support"))
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
        for (type in DiplomacyFixtures.alertChoices.map { it.first }) {
            val (session, expected) = scenario("defeated-$type") { DiplomacyFixtures.alert(it, type) }
            for (g in listOf(session.game!!, expected)) DiplomacyFixtures.enemy(g).cities = emptyList()
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
