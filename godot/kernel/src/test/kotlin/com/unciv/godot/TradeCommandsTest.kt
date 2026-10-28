package com.unciv.godot

import com.unciv.Constants
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.DiplomacyFixtures.incomingTrade
import com.unciv.godot.DiplomacyFixtures.resource
import com.unciv.godot.DiplomacyFixtures.third
import com.unciv.godot.DiplomacyFixtures.trade
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.*
import com.unciv.view.GameView
import com.unciv.view.TradeView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 接收交易的逐类条款差分：每条命令都与原生 TradeLogic/TradeRequest 在完整存档上对比，
 * 只把网关票据、可见性约束和“整笔拒绝/拒绝冷却与清理之分”当作额外断言。
 */
class TradeCommandsTest {
    private fun scenario(name: String, war: Boolean = false, configure: (GameInfo) -> Unit = {}): Pair<GameSession, GameInfo> {
        val file = DiplomacyFixtures.file("trade-$name", war, configure)
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
    private fun pending(session: GameSession) = options(session)["incomingTrade"]!!.jsonObject
    private fun token(session: GameSession) = pending(session).text("tradeToken")
    private fun choice(trade: JsonObject, id: String) = trade["choices"]!!.jsonArray.map { it.jsonObject }.single { it.text("id") == id }
    private fun clauses(trade: JsonObject, side: String) = trade[side]!!.jsonArray.map { it.jsonObject }

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
    private fun execute(session: GameSession, expected: GameInfo, action: String,
                        args: List<Pair<String, Any?>>, operation: () -> Unit) {
        val body = request(session, action, *args.toTypedArray())
        native(expected, operation)
        val revision = session.revision
        val current = session.game
        val response = session.handle(body)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertSame(current, session.game)
        assertEquals(revision + 1, session.revision)
        assertGameplayEquals(action, expected, session.game!!)
        assertEquals(response, session.handle(body))
        reject(session, "REQUEST_REUSED", JsonObject(body + ("unexpected" to JsonPrimitive(true))))
    }
    private fun decide(session: GameSession, expected: GameInfo, option: String) =
        execute(session, expected, "diplomacyTradeDecision",
            listOf("tradeToken" to token(session), "choice" to option)) {
            DiplomacyFixtures.tradeDecision(expected, option)
        }

    private fun gold(amount: Int) = TradeOffer(Constants.flatGold, TradeOfferType.Gold, amount, duration = -1)
    private fun gpt(amount: Int, duration: Int) = TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, amount, duration)
    private fun embassy() = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, 1, duration = -1)
    private fun treaty(name: String, amount: Int = 1, duration: Int = 30) = TradeOffer(name, TradeOfferType.Treaty, amount, duration)
    private fun openBorders(duration: Int = 30) = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, duration)
    private fun luxury(name: String, amount: Int, duration: Int = 10) = TradeOffer(name, TradeOfferType.Luxury_Resource, amount, duration)
    private fun strategic(name: String, amount: Int, duration: Int = 10) = TradeOffer(name, TradeOfferType.Strategic_Resource, amount, duration)
    private fun city(id: String) = TradeOffer(id, TradeOfferType.City, 1, duration = -1)
    private fun warOn(third: Civilization) = TradeOffer(third.civID, TradeOfferType.WarDeclaration, 1, duration = -1)
    private fun peaceWith(third: Civilization) = TradeOffer(third.civID, TradeOfferType.PeaceProposal, 1, duration = -1)

    @Test fun goldGptAndEmbassyClausesMatchNative() {
        val cases = listOf<Pair<String, (GameInfo) -> Trade>>(
            "gold-their" to { trade(their = listOf(gold(120))) },
            "gold-our" to { trade(our = listOf(gold(60))) },
            "gpt-their" to { trade(their = listOf(gpt(1, 4))) },
            "gpt-our" to { trade(our = listOf(gpt(1, 5))) },
            "embassy-their" to { trade(their = listOf(embassy())) },
            "embassy-our" to { trade(our = listOf(embassy())) },
            "embassy-both" to { trade(our = listOf(embassy()), their = listOf(embassy())) })
        for ((name, build) in cases) {
            val (session, expected) = scenario(name) { incomingTrade(it, build(it)) }
            val side = if (name.endsWith("their")) "theirOffers" else "ourOffers"
            val clause = clauses(pending(session), side).single()
            assertTrue(name, clause.boolean("supported"))
            assertEquals(name, "", clause.text("problem"))
            assertFalse(name, clause["effects"]!!.jsonArray.isEmpty())
            decide(session, expected, "accept")
        }
        val own = session0("gold-effects") { incomingTrade(it, trade(their = listOf(gold(120)))) }
        val clause = clauses(pending(own), "theirOffers").single()
        assertTrue(clause.text("text").contains("一次性金币"))
        assertTrue(clause.text("text").contains("120"))
        assertTrue(clause["risks"]!!.jsonArray.isNotEmpty())
    }

    private fun session0(name: String, configure: (GameInfo) -> Unit) = scenario(name, configure = configure).first

    @Test fun treatyClausesSignOnceAndMatchNative() {
        val (peace, expectedPeace) = scenario("peace-treaty", war = true) {
            incomingTrade(it, trade(our = listOf(treaty(Constants.peaceTreaty)), their = listOf(treaty(Constants.peaceTreaty))))
        }
        decide(peace, expectedPeace, "accept")
        assertFalse(peace.game!!.currentPlayerCiv.isAtWarWith(other(peace)))
        assertEquals(30, peace.game!!.currentPlayerCiv.getDiplomacyManager(other(peace))!!.turnsToPeaceTreaty())

        val (research, expectedResearch) = scenario("research-agreement") {
            incomingTrade(it, trade(our = listOf(treaty(Constants.researchAgreement, 50)),
                their = listOf(treaty(Constants.researchAgreement, 50))))
        }
        decide(research, expectedResearch, "accept")
        assertEquals(950, research.game!!.currentPlayerCiv.gold)
        assertEquals(950, other(research).gold)
        val ours = research.game!!.currentPlayerCiv.getDiplomacyManager(other(research))!!
        assertEquals(30, ours.getFlag(DiplomacyFlags.ResearchAgreement))
        assertEquals(30, ours.otherCivDiplomacy().getFlag(DiplomacyFlags.ResearchAgreement))

        val (pact, expectedPact) = scenario("defensive-pact") {
            incomingTrade(it, trade(our = listOf(treaty(Constants.defensivePact, duration = 20)),
                their = listOf(treaty(Constants.defensivePact, duration = 20))))
        }
        decide(pact, expectedPact, "accept")
        assertEquals(DiplomaticStatus.DefensivePact,
            pact.game!!.currentPlayerCiv.getDiplomacyManager(other(pact))!!.diplomaticStatus)
        assertEquals(20, pact.game!!.currentPlayerCiv.getDiplomacyManager(other(pact))!!.getFlag(DiplomacyFlags.DefensivePact))
    }

    @Test fun unmatchedTreatyMirrorIsRejectedAsWholeTrade() {
        val (session, _) = scenario("treaty-half") {
            incomingTrade(it, trade(their = listOf(treaty(Constants.defensivePact, duration = 20))))
        }
        val dto = pending(session)
        assertFalse(dto.boolean("supported"))
        assertTrue(dto["problems"]!!.jsonArray.any { it.jsonPrimitive.content.contains("镜像") })
        reject(session, "UNSUPPORTED", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        assertFalse(choice(dto, "dismiss").boolean("enabled"))
        assertTrue(choice(dto, "decline").boolean("enabled"))
    }

    @Test fun resourcesAndOpenBordersMatchNative() {
        val (session, expected) = scenario("resources") { game ->
            resource(game, game.currentPlayerCiv, "Iron", 2)
            resource(game, DiplomacyFixtures.enemy(game), "Furs", 2)
            incomingTrade(game, trade(
                our = listOf(strategic("Iron", 1), openBorders(12)),
                their = listOf(luxury("Furs", 1))))
        }
        val their = clauses(pending(session), "theirOffers").single()
        assertEquals("Furs", their.text("name"))
        assertEquals(1, their["resource"]!!.jsonObject.integer("owned"))
        assertEquals("Luxury", their["resource"]!!.jsonObject.text("kind"))
        decide(session, expected, "accept")
        // 开放边界只在本方的 theirOffers 中被识别，所以“我们给出”意味着对方的 hasOpenBorders 为真。
        assertTrue(other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!.hasOpenBorders)
        assertFalse(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!.hasOpenBorders)
        assertTrue(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!
            .trades.any { trade -> trade.theirOffers.any { it.name == "Furs" } })
    }

    @Test fun mixedTradeAcrossClausesMatchesNative() {
        val (session, expected) = scenario("mixed") { game ->
            resource(game, game.currentPlayerCiv, "Iron", 2)
            resource(game, DiplomacyFixtures.enemy(game), "Furs", 2)
            val enemyCity = DiplomacyFixtures.enemy(game).cities.first { it.name == "陆战测试城" }
            incomingTrade(game, trade(
                our = listOf(gold(25), gpt(1, 5), strategic("Iron", 1, 10)),
                their = listOf(gold(75), luxury("Furs", 1, 10), embassy(), openBorders(12), city(enemyCity.id))))
        }
        val dto = pending(session)
        assertTrue(dto.toString(), dto.boolean("supported"))
        assertEquals(3, clauses(dto, "ourOffers").size)
        assertEquals(5, clauses(dto, "theirOffers").size)
        assertFalse(dto.toString().contains(session.game!!.currentPlayerCiv.cities.first().id))
        decide(session, expected, "accept")
        assertTrue(session.game!!.currentPlayerCiv.cities.any { it.name == "陆战测试城" })
        assertFalse(other(session).cities.any { it.name == "陆战测试城" })
        assertEquals(1000 + 75 - 25, session.game!!.currentPlayerCiv.gold)
    }

    @Test fun goldPerTurnPaysEachTurnThenExpires() {
        val (session, expected) = scenario("gpt-expiry") {
            incomingTrade(it, trade(their = listOf(gpt(2, 3)), our = listOf(gold(10))))
        }
        decide(session, expected, "accept")
        assertTrue(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!
            .trades.any { trade -> trade.theirOffers.any { it.type == TradeOfferType.Gold_Per_Turn } })
        var next = expected
        repeat(4) { turn ->
            next = DiplomacyFixtures.nextTurn(next)
            run(session, "nextTurn")
            assertGameplayEquals("GPT 第 ${turn + 1} 回合", next, session.game!!)
        }
        assertTrue(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!
            .trades.none { trade -> trade.theirOffers.any { it.type == TradeOfferType.Gold_Per_Turn } })
    }

    @Test fun warCancelsTimedAgreementsAndClosesBorders() {
        val (session, expected) = scenario("war-interrupt") {
            incomingTrade(it, trade(our = listOf(openBorders(20)), their = listOf(gpt(1, 20), embassy())))
        }
        decide(session, expected, "accept")
        execute(session, expected, "diplomacyDeclareWar", listOf("civId" to other(session).civID)) {
            expected.currentPlayerCiv.getDiplomacyManager(DiplomacyFixtures.enemy(expected))!!.declareWar()
        }
        assertFalse(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!.hasOpenBorders)
        assertTrue(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!.trades.isEmpty())
        assertTrue(other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!.trades.isEmpty())
    }

    @Test fun cityTradeRaisesLiberationDecisionAndBlocksQueue() {
        val (session, expected) = scenario("city-liberation") { game ->
            val founder = third(game)
            val enemyCity = DiplomacyFixtures.enemy(game).cities.first { it.name == "陆战测试城" }
            native(game) { enemyCity.foundingCivObject = founder }
            incomingTrade(game, trade(their = listOf(city(enemyCity.id))))
            incomingTrade(game, trade(their = listOf(gold(5))))
        }
        val cities = pending(session)
        val clause = clauses(cities, "theirOffers").single()
        assertEquals("陆战测试城", clause.text("name"))
        assertEquals(3, clause["city"]!!.jsonObject.integer("population"))
        val cityId = session.game!!.currentPlayerCiv.tradeRequests.first().trade.theirOffers.single().name
        decide(session, expected, "accept")
        assertTrue(session.game!!.currentPlayerCiv.cities.any { it.name == "陆战测试城" })
        assertTrue(session.game!!.currentPlayerCiv.popupAlerts.any { it.type == AlertType.CityTraded && it.value == cityId })

        val queued = pending(session)
        assertFalse(choice(queued, "accept").boolean("enabled"))
        assertTrue(choice(queued, "accept").text("reason").contains("弹窗"))
        reject(session, "PENDING_DECISION", request(session, "diplomacyTradeDecision", "tradeToken" to queued.text("tradeToken"), "choice" to "accept"))
    }

    @Test fun thirdPartyWarAndPeaceMatchNative() {
        val (warSession, warExpected) = scenario("third-war") { game ->
            val egypt = third(game)
            incomingTrade(game, trade(their = listOf(warOn(egypt))))
        }
        decide(warSession, warExpected, "accept")
        val thirdCiv = thirdSession(warSession)
        assertTrue(other(warSession).isAtWarWith(thirdCiv))
        assertFalse(warSession.game!!.currentPlayerCiv.isAtWarWith(thirdCiv))

        val (peaceSession, peaceExpected) = scenario("third-peace") { game ->
            val egypt = third(game)
            native(game) { DiplomacyFixtures.enemy(game).getDiplomacyManager(egypt)!!.declareWar() }
            incomingTrade(game, trade(their = listOf(peaceWith(egypt))))
        }
        val target = thirdSession(peaceSession)
        assertTrue(other(peaceSession).isAtWarWith(target))
        decide(peaceSession, peaceExpected, "accept")
        assertFalse(other(peaceSession).isAtWarWith(target))
        assertFalse(peaceSession.game!!.currentPlayerCiv.isAtWarWith(target))
        assertEquals(1, other(peaceSession).getDiplomacyManager(target)!!.trades.count { it.isPeaceTreaty() })
        assertEquals(1, target.getDiplomacyManager(other(peaceSession))!!.trades.count { it.isPeaceTreaty() })
    }

    private fun thirdSession(session: GameSession) = session.game!!.civilizations.single { it.civName == "Egypt" }

    @Test fun unsupportedClauseRejectsWholeTradeAndDeclineKeepsNativeCooldown() {
        val (session, expected) = scenario("unsupported") {
            incomingTrade(it, trade(our = listOf(gold(10), openBorders(10)), their = listOf(gold(100),
                TradeOffer("Pottery", TradeOfferType.Technology, 1, duration = -1))))
        }
        val dto = pending(session)
        assertFalse(dto.boolean("supported"))
        assertTrue(dto["problems"]!!.jsonArray.any { it.jsonPrimitive.content.contains("科技") })
        reject(session, "UNSUPPORTED", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        assertEquals(1000, session.game!!.currentPlayerCiv.gold)
        assertTrue(session.game!!.currentPlayerCiv.isAtWarWith(other(session)).not())
        assertTrue(choice(dto, "decline").boolean("enabled"))
        assertFalse(choice(dto, "dismiss").boolean("enabled"))
        decide(session, expected, "decline")
        // 拒绝保留原生冷却；清理不会写任何外交标记。AI 收件箱按原生规则不收通知。
        assertEquals(10, other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!
            .getFlag(DiplomacyFlags.DeclinedOpenBorders))
        assertEquals(0, other(session).notifications.size)
    }

    @Test fun expiredTradeOnlyAcceptsCleanupAndKeepsDeclineCooldown() {
        val (session, expected) = scenario("expired", war = true) {
            incomingTrade(it, trade(our = listOf(treaty(Constants.peaceTreaty)),
                their = listOf(treaty(Constants.peaceTreaty), gold(100))))
            native(it) { DiplomacyFixtures.enemy(it).addGold(-DiplomacyFixtures.enemy(it).gold) }
        }
        val dto = pending(session)
        assertTrue(dto.boolean("expired"))
        assertFalse(choice(dto, "accept").boolean("enabled"))
        assertTrue(choice(dto, "accept").text("reason").contains("失效"))
        assertTrue(choice(dto, "dismiss").boolean("enabled"))
        reject(session, "CANNOT_ACCEPT_TRADE", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        decide(session, expected, "decline")
        assertEquals(3, other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!
            .getFlag(DiplomacyFlags.DeclinedPeace))
        assertEquals(0, other(session).notifications.size)

        val (cleared, clearedExpected) = scenario("dismiss", war = true) {
            incomingTrade(it, trade(our = listOf(treaty(Constants.peaceTreaty)),
                their = listOf(treaty(Constants.peaceTreaty), gold(100))))
            native(it) { DiplomacyFixtures.enemy(it).addGold(-DiplomacyFixtures.enemy(it).gold) }
        }
        decide(cleared, clearedExpected, "dismiss")
        assertTrue(cleared.game!!.currentPlayerCiv.tradeRequests.isEmpty())
        assertFalse(other(cleared).getDiplomacyManager(cleared.game!!.currentPlayerCiv)!!
            .hasFlag(DiplomacyFlags.DeclinedPeace))
        assertEquals(0, other(cleared).notifications.size)
    }

    @Test fun staleTokenAndContentChangesAreRejected() {
        val (session, expected) = scenario("stale") { incomingTrade(it, trade(their = listOf(gold(100)))) }
        val old = token(session)
        session.game!!.currentPlayerCiv.tradeRequests.first().trade.theirOffers.single().amount = 50
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyTradeDecision", "tradeToken" to old, "choice" to "accept"))
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyTradeDecision", "tradeToken" to old, "choice" to "decline"))
        assertNotEquals(old, token(session))
        session.game!!.currentPlayerCiv.tradeRequests.first().trade.theirOffers.add(gold(1))
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "dismiss"))
        decide(session, expected, "decline")
    }

    @Test fun malformedClausesNeverAcceptPartialTrades() {
        fun base(game: GameInfo) = trade(our = listOf(gold(10)), their = listOf(gold(50)))
        val mutations: List<Pair<String, (GameInfo, Trade) -> Unit>> = listOf(
            "gold-zero" to { _, t -> t.theirOffers.first().amount = 0 },
            "gold-duration" to { _, t -> t.theirOffers.first().duration = 5 },
            "gold-wrong-name" to { _, t -> t.theirOffers[0] = TradeOffer("Weird", TradeOfferType.Gold, 5, -1) },
            "gold-per-turn-zero" to { _, t -> t.theirOffers.add(TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, 0, 5)) },
            "empty" to { _, t -> t.ourOffers.clear(); t.theirOffers.clear() },
            "gpt-duration" to { _, t -> t.theirOffers.add(TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, 1, -1)) },
            "embassy-amount" to { _, t -> t.theirOffers.add(TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, 2, -1)) },
            "treaty-duration" to { _, t -> t.theirOffers.add(TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, 1, 0)) },
            "open-borders-amount" to { _, t -> t.theirOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 2, 10)) },
            "luxury-as-strategic" to { g, t -> t.theirOffers.add(TradeOffer("Furs", TradeOfferType.Strategic_Resource, 1, 10)) },
            "city-unknown" to { _, t -> t.theirOffers.add(TradeOffer("hidden-city-id", TradeOfferType.City, 1, -1)) },
            "war-amount" to { g, t -> t.theirOffers.add(TradeOffer(DiplomacyFixtures.enemy(g).civID, TradeOfferType.WarDeclaration, 2, -1)) },
            "stockpiled" to { _, t -> t.theirOffers.add(TradeOffer("Iron", TradeOfferType.Stockpiled_Resource, 1, 10)) },
            "introduction" to { g, t -> t.theirOffers.add(TradeOffer(DiplomacyFixtures.third(g).civID, TradeOfferType.Introduction, 1, -1)) },
            "unknown-treaty" to { _, t -> t.theirOffers.add(TradeOffer("FutureTreaty", TradeOfferType.Treaty, 1, 10)) })
        for ((name, mutate) in mutations) {
            val (session, _) = scenario("shape-$name") { game ->
                val built = base(game)
                mutate(game, built)
                incomingTrade(game, built)
            }
            val dto = pending(session)
            assertFalse(name, dto.boolean("supported"))
            assertTrue(name, dto["problems"]!!.jsonArray.isNotEmpty())
            assertFalse(name, choice(dto, "accept").boolean("enabled"))
            reject(session, "UNSUPPORTED", request(session, "diplomacyTradeDecision", "tradeToken" to token(session), "choice" to "accept"))
        }
    }

    // --- 第 5 批：主动报价、还价与撤回 ---

    private fun tradeOptions(session: GameSession): JsonObject {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val data = run(session, "diplomacyTradeOptions", "civId" to other(session).civID)["data"]!!.jsonObject
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
        return data
    }

    private fun tradePreview(session: GameSession, draft: JsonObject): JsonObject {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val data = run(session, "diplomacyTradePreview", "civId" to other(session).civID, "draft" to draft)["data"]!!.jsonObject
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
        return data
    }

    private fun clause(type: String, name: String, amount: Int? = null): JsonObject {
        val fields = mutableListOf<Pair<String, Any?>>("type" to type, "name" to name)
        if (amount != null) fields.add("amount" to amount)
        return dto(*fields.toTypedArray())
    }

    private fun draft(our: List<JsonObject> = emptyList(), their: List<JsonObject> = emptyList()) =
        dto("ourOffers" to our, "theirOffers" to their)

    /** 把内核规范化后的草稿还原为原生 Trade，交给原生入口执行，避免测试自行解释金额或期限。 */
    private fun tradeFrom(draft: JsonObject): Trade = Trade().apply {
        fun offers(key: String) = draft[key]!!.jsonArray.map {
            val offer = it.jsonObject
            TradeOffer(offer.text("name"), TradeOfferType.valueOf(offer.text("type")),
                offer["amount"]!!.jsonPrimitive.int, offer["duration"]!!.jsonPrimitive.int)
        }
        ourOffers.addAll(offers("ourOffers"))
        theirOffers.addAll(offers("theirOffers"))
    }

    private fun sendProposal(session: GameSession, expected: GameInfo, preview: JsonObject) {
        val draft = preview["draft"]!!.jsonObject
        execute(session, expected, "diplomacyTradeSend", listOf(
            "civId" to other(session).civID, "draft" to draft, "tradeToken" to preview.text("tradeToken"))) {
            val view = TradeView(expected.currentPlayerCiv, DiplomacyFixtures.enemy(expected),
                GameView(expected, expected.currentPlayerCiv))
            view.setStagedTrade(tradeFrom(draft))
            check(view.tryProposeStagedTrade())
        }
    }

    @Test fun optionsExposeNativeCandidatesAndBounds() {
        val (session, _) = scenario("trade-options") { game ->
            resource(game, game.currentPlayerCiv, "Iron", 2)
            resource(game, DiplomacyFixtures.enemy(game), "Furs", 2)
        }
        val data = tradeOptions(session)
        assertEquals(other(session).civID, data.text("civId"))
        assertTrue(data.boolean("canSend"))
        assertTrue(data["pending"]!!.jsonPrimitive is JsonNull)
        val ours = clauses(data, "ourOffers")
        val theirs = clauses(data, "theirOffers")
        assertEquals(1000, ours.single { it.text("type") == "Gold" }["maxAmount"]!!.jsonPrimitive.int)
        assertEquals(2, ours.single { it.text("name") == "Iron" }["maxAmount"]!!.jsonPrimitive.int)
        assertTrue(theirs.any { it.text("name") == "Furs" && it.boolean("tradable") })
        // 候选集合与原生 TradeLogic 的可用条款一致（名称与金额均来自原生）。
        val native = TradeLogic(session.game!!.currentPlayerCiv, other(session))
        val nativeGold = native.ourAvailableOffers.single { it.type == TradeOfferType.Gold }
        assertEquals(nativeGold.amount, ours.single { it.text("type") == "Gold" }["maxAmount"]!!.jsonPrimitive.int)
        assertTrue(ours.none { it.text("type") == "Technology" || it.text("type") == "Introduction" || it.text("type") == "Stockpiled_Resource" })
        assertTrue(ours.all { it.text("rawName").isNotEmpty() })
        assertTrue(data["incoming"]!!.jsonPrimitive is JsonNull)
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyTradeOptions", "civId" to other(session).civID, "extra" to 1))
    }

    @Test fun previewNormalizesAmountsDurationsAndTreatyMirroring() {
        val (session, _) = scenario("trade-preview", war = true)
        val native = TradeView(session.game!!.currentPlayerCiv, other(session),
            GameView(session.game!!, session.game!!.currentPlayerCiv))
        val peaceSource = native.ourAvailableOffers().single { it.name == Constants.peaceTreaty }
        val preview = tradePreview(session, draft(
            our = listOf(clause("Gold", Constants.flatGold, 200), clause("Treaty", Constants.peaceTreaty))))
        assertTrue(preview.toString(), preview.boolean("supported"))
        assertTrue(preview["problems"]!!.jsonArray.isEmpty())
        val normalized = tradeFrom(preview["draft"]!!.jsonObject)
        assertEquals(200, normalized.ourOffers.single { it.type == TradeOfferType.Gold }.amount)
        // 只给一侧的条约由内核按原生期限双边镜像。
        assertEquals(1, normalized.ourOffers.count { it.name == Constants.peaceTreaty })
        assertEquals(1, normalized.theirOffers.count { it.name == Constants.peaceTreaty })
        assertEquals(peaceSource.duration, normalized.theirOffers.single { it.name == Constants.peaceTreaty }.duration)
        assertTrue(preview["effects"]!!.jsonArray.any { it.jsonPrimitive.content.contains("停战") })

        val overMax = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 9999))))
        assertFalse(overMax.boolean("supported"))
        assertTrue(overMax["problems"]!!.jsonArray.any { it.jsonPrimitive.content.contains("越界") })
        val unknown = tradePreview(session, draft(our = listOf(clause("Technology", "Pottery"))))
        assertFalse(unknown.boolean("supported"))
        assertTrue(unknown["problems"]!!.jsonArray.any { it.jsonPrimitive.content.contains("不可交易") })
    }

    @Test fun sentProposalIsDecidedByNativeAiOnItsTurn() {
        val (session, expected) = scenario("trade-send")
        val preview = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 500))))
        assertTrue(preview.toString(), preview.boolean("supported"))
        sendProposal(session, expected, preview)
        val player = session.game!!.currentPlayerCiv
        assertTrue(other(session).tradeRequests.any { it.requestingCiv == player.civID })
        assertEquals(player.civID, tradeOptions(session).text("pending").let { if (it.isEmpty()) "" else player.civID })
        assertFalse(tradeOptions(session).boolean("canSend"))
        // AI 在原生存档上做同样的决定：接受、还价或拒绝都必须逐字段一致。
        val next = DiplomacyFixtures.nextTurn(expected)
        run(session, "nextTurn")
        assertGameplayEquals("主动提案经 AI 回合", next, session.game!!)
        assertTrue(other(session).tradeRequests.none { it.requestingCiv == player.civID })
        // 提案必须被 AI 处置：或直接接受而转移金币，或转为还价，不能原地留存。
        val after = session.game!!.currentPlayerCiv
        assertTrue("AI 必须处置提案：接受或还价", after.gold < 1000 || after.tradeRequests.isNotEmpty())
    }

    @Test fun onlyOnePendingProposalAndStaleQuoteAreRejected() {
        val (session, expected) = scenario("trade-once")
        val first = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 100))))
        sendProposal(session, expected, first)
        val second = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 50))))
        reject(session, "CANNOT_PROPOSE_TRADE", request(session, "diplomacyTradeSend",
            "civId" to other(session).civID, "draft" to second["draft"]!!, "tradeToken" to second.text("tradeToken")))
        // 撤回后可以重新报价；旧票据在余额变化后失效。
        val pendingToken = tradeOptions(session).text("pending")
        assertTrue(pendingToken.isNotEmpty())
        execute(session, expected, "diplomacyTradeWithdraw", listOf(
            "civId" to other(session).civID, "tradeToken" to pendingToken)) {
            check(TradeView(expected.currentPlayerCiv, DiplomacyFixtures.enemy(expected),
                GameView(expected, expected.currentPlayerCiv)).tryRetractOffer())
        }
        assertTrue(other(session).tradeRequests.isEmpty())
        assertTrue(tradeOptions(session).boolean("canSend"))
        val stale = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 75))))
        // 客户端篡改已确认的金额后，票据不再对应规范化草稿。
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyTradeSend",
            "civId" to other(session).civID,
            "draft" to draft(our = listOf(clause("Gold", Constants.flatGold, 80))),
            "tradeToken" to stale.text("tradeToken")))
    }

    @Test fun withdrawRestoresNativeResourceAvailability() {
        val (session, expected) = scenario("trade-withdraw") { game ->
            resource(game, game.currentPlayerCiv, "Iron", 2)
        }
        fun available(game: GameInfo) = TradeLogic(game.currentPlayerCiv, DiplomacyFixtures.enemy(game))
            .ourAvailableOffers.single { it.name == "Iron" }.amount
        val before = available(session.game!!)
        val preview = tradePreview(session, draft(our = listOf(clause("Strategic_Resource", "Iron", 1))))
        assertTrue(preview.toString(), preview.boolean("supported"))
        sendProposal(session, expected, preview)
        val withdrawn = available(session.game!!)
        execute(session, expected, "diplomacyTradeWithdraw", listOf(
            "civId" to other(session).civID, "tradeToken" to tradeOptions(session).text("pending"))) {
            check(TradeView(expected.currentPlayerCiv, DiplomacyFixtures.enemy(expected),
                GameView(expected, expected.currentPlayerCiv)).tryRetractOffer())
        }
        assertEquals(before, available(session.game!!))
        assertTrue(withdrawn <= before)
        reject(session, "DIPLOMACY_REQUEST", request(session, "diplomacyTradeWithdraw",
            "civId" to other(session).civID, "tradeToken" to "stale"))
    }

    @Test fun renegotiateRemovesIncomingWithoutCooldownThenResends() {
        val (session, expected) = scenario("trade-renegotiate") {
            incomingTrade(it, trade(their = listOf(gold(40))))
        }
        val incoming = pending(session)
        assertEquals(40, clauses(incoming, "theirOffers").single()["amount"]!!.jsonPrimitive.int)
        // 还价预填：可见的来件票据与原始草稿（我方视角）由同一查询给出。
        val exposed = tradeOptions(session)["incoming"]!!.jsonObject
        assertEquals(incoming.text("tradeToken"), exposed.text("tradeToken"))
        val incomingDraft = exposed["draft"]!!.jsonObject
        assertEquals("Gold", incomingDraft["theirOffers"]!!.jsonArray.single().jsonObject.text("type"))
        assertEquals(40, incomingDraft["theirOffers"]!!.jsonArray.single().jsonObject["amount"]!!.jsonPrimitive.int)
        execute(session, expected, "diplomacyTradeRenegotiate", listOf("tradeToken" to incoming.text("tradeToken"))) {
            expected.currentPlayerCiv.tradeRequests.clear()
            expected.currentPlayerCiv.cache.updateCivResources()
        }
        assertTrue(session.game!!.currentPlayerCiv.tradeRequests.isEmpty())
        assertTrue(tradeOptions(session)["incoming"]!!.jsonPrimitive is JsonNull)
        val enemyDiplo = other(session).getDiplomacyManager(session.game!!.currentPlayerCiv)!!
        assertFalse(enemyDiplo.hasFlag(DiplomacyFlags.DeclinedPeace))
        assertFalse(session.game!!.currentPlayerCiv.getDiplomacyManager(other(session))!!.hasFlag(DiplomacyFlags.DeclinedPeace))
        assertEquals(0, other(session).notifications.size)
        // 以原提案条款为基础加价重发，仍是真实提案。
        val counter = tradePreview(session, draft(their = listOf(clause("Gold", Constants.flatGold, 80))))
        assertTrue(counter.toString(), counter.boolean("supported"))
        sendProposal(session, expected, counter)
        assertTrue(other(session).tradeRequests.any { it.requestingCiv == session.game!!.currentPlayerCiv.civID })
    }

    /** 模拟 Godot 的 JSON 往返：数字全部变成双精度浮点，整数语义不变。 */
    private fun floats(draft: JsonObject) = JsonObject(draft.mapValues { (_, side) ->
        JsonArray((side as JsonArray).map { element ->
            JsonObject(element.jsonObject.mapValues { (key, field) ->
                if (key == "amount" || key == "duration") JsonPrimitive(field.jsonPrimitive.int.toDouble()) else field
            })
        })
    })

    @Test fun previewRoundTripWithFloatNumbersKeepsTicketsAndAmounts() {
        val (session, expected) = scenario("trade-float")
        val preview = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 500))))
        assertTrue(preview.toString(), preview.boolean("supported"))
        // 前端把预览返回的 draft 原样回传时整数已变成浮点：必须仍规范化为同一草稿与票据，
        // 否则发送会以“报价已变化”被拒绝，或静默把金额换成原生上限。
        val replay = tradePreview(session, floats(preview["draft"]!!.jsonObject))
        assertEquals(preview.text("tradeToken"), replay.text("tradeToken"))
        assertEquals(500, clauses(replay, "ourOffers").single()["amount"]!!.jsonPrimitive.int)
        sendProposal(session, expected, replay)
        assertTrue(other(session).tradeRequests.any { it.requestingCiv == session.game!!.currentPlayerCiv.civID })
        // 真正的小数直接拒绝，不得回退到原生上限。
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyTradePreview",
            "civId" to other(session).civID,
            "draft" to dto("ourOffers" to listOf(dto("type" to "Gold", "name" to Constants.flatGold, "amount" to 1.5)))))
    }

    @Test fun malformedDraftsAreRejectedWholesale() {
        val (session, _) = scenario("trade-draft-shape")
        val empty = tradePreview(session, dto())
        assertFalse(empty.boolean("supported"))
        assertTrue(empty["problems"]!!.jsonArray.any { it.jsonPrimitive.content.contains("没有任何条款") })
        val duplicate = tradePreview(session, draft(our = listOf(
            clause("Gold", Constants.flatGold, 10), clause("Gold", Constants.flatGold, 20))))
        assertFalse(duplicate.boolean("supported"))
        assertTrue(duplicate["problems"]!!.jsonArray.any { it.jsonPrimitive.content.contains("重复") })
        val zero = tradePreview(session, draft(our = listOf(clause("Gold", Constants.flatGold, 0))))
        assertFalse(zero.boolean("supported"))
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyTradePreview",
            "civId" to other(session).civID,
            "draft" to dto("ourOffers" to listOf(dto("type" to "Gold", "name" to Constants.flatGold, "amount" to 10, "extra" to 1)))))
        reject(session, "INVALID_ARGUMENT", request(session, "diplomacyTradeSend",
            "civId" to other(session).civID,
            "draft" to dto("ourOffers" to "not-a-list"), "tradeToken" to "token"))
        reject(session, "UNSUPPORTED", request(session, "diplomacyTradeSend",
            "civId" to other(session).civID, "draft" to dto("ourOffers" to emptyList<Any>()), "tradeToken" to "token"))
        assertTrue(other(session).tradeRequests.isEmpty())
    }
}
