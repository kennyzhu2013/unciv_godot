package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CityStateCommandsTest {
    private fun scenario(name: String, configure: (GameInfo) -> Unit): Pair<GameSession, GameInfo> {
        val file = CityStateFixtures.file(name, configure)
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }

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
    private fun csRow(session: GameSession) = options(session)["civilizations"]!!.jsonArray
        .map { it.jsonObject }.single { it.text("type") == "cityState" }
    private fun csNode(session: GameSession) = csRow(session)["cityState"]!!.jsonObject
    private fun csId(session: GameSession) = csRow(session).text("civId")
    private fun giftToken(session: GameSession, amount: Int) = csNode(session)["gifts"]!!.jsonArray
        .map { it.jsonObject }.single { it["amount"]!!.jsonPrimitive.int == amount }.text("token")
    private fun tributeToken(session: GameSession, kind: String) =
        csNode(session)["tribute"]!!.jsonObject.text(if (kind == "worker") "workerToken" else "goldToken")
    private fun marriageToken(session: GameSession) = csNode(session)["marriage"]!!.jsonObject.text("token")

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

    @Test fun quotesMatchNativeReadonlyFunctions() {
        val (session, expected) = scenario("cs-quote") { CityStateFixtures.minor(it) }
        val node = csNode(session)
        val player = expected.currentPlayerCiv
        val other = CityStateFixtures.cityState(expected)
        for (gift in node["gifts"]!!.jsonArray) {
            val amount = gift.jsonObject["amount"]!!.jsonPrimitive.int
            assertEquals(other.cityStateFunctions.influenceGainedByGift(player, amount),
                gift.jsonObject["influence"]!!.jsonPrimitive.int)
        }
        val tribute = node["tribute"]!!.jsonObject
        assertEquals(other.cityStateFunctions.goldGainedByTribute(), tribute["goldAmount"]!!.jsonPrimitive.int)
        assertEquals(other.cityStateFunctions.getTributeWillingness(player, false), tribute["goldWillingness"]!!.jsonPrimitive.int)
        assertEquals(other.cityStateFunctions.getTributeWillingness(player, true), tribute["workerWillingness"]!!.jsonPrimitive.int)
        assertEquals(other.cityStateFunctions.otherCivCanPledgeProtection(player),
            node["actions"]!!.jsonObject["pledge"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        // 非奥地利玩家不输出联姻节点。
        assertTrue(node["marriage"] is JsonNull)
        assertEquals(options(session), options(session))
    }

    @Test fun marriageQuoteMatchesNativeCost() {
        val (session, expected) = scenario("cs-marriage-quote") { CityStateFixtures.austria(it) }
        val node = csNode(session)
        val other = CityStateFixtures.cityState(expected)
        val marriage = node["marriage"]!!.jsonObject
        assertEquals(other.cityStateFunctions.getDiplomaticMarriageCost(), marriage["cost"]!!.jsonPrimitive.int)
        assertTrue(marriage["choice"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun giftMatchesNativeAndReplaysOnce() {
        for (amount in listOf(250, 500, 1000)) {
            val (session, expected) = scenario("cs-gift$amount") { CityStateFixtures.minor(it) }
            val token = giftToken(session, amount)
            val goldBefore = session.game!!.currentPlayerCiv.gold
            apply(session, expected, "cityStateGiftGold",
                "civId" to csId(session), "amount" to amount, "cityStateToken" to token) {
                CityStateFixtures.gift(expected, amount)
            }
            assertEquals(goldBefore - amount, session.game!!.currentPlayerCiv.gold)
        }
    }

    @Test fun pledgeAndRevokeMatchNative() {
        val (pledgeSession, pledgeExpected) = scenario("cs-pledge") { CityStateFixtures.minor(it) }
        apply(pledgeSession, pledgeExpected, "cityStatePledgeProtection", "civId" to csId(pledgeSession)) {
            CityStateFixtures.pledge(pledgeExpected)
        }
        val (revokeSession, revokeExpected) = scenario("cs-revoke") { CityStateFixtures.protectedCs(it) }
        apply(revokeSession, revokeExpected, "cityStateRevokeProtection", "civId" to csId(revokeSession)) {
            CityStateFixtures.revoke(revokeExpected)
        }
    }

    @Test fun tributeGoldAndWorkerMatchNative() {
        val (goldSession, goldExpected) = scenario("cs-tribute-gold") { CityStateFixtures.bullyable(it, false) }
        val goldToken = tributeToken(goldSession, "gold")
        apply(goldSession, goldExpected, "cityStateDemandTribute",
            "civId" to csId(goldSession), "kind" to "gold", "cityStateToken" to goldToken) {
            CityStateFixtures.tribute(goldExpected, false)
        }
        val (workerSession, workerExpected) = scenario("cs-tribute-worker") { CityStateFixtures.bullyable(it, true) }
        val workerToken = tributeToken(workerSession, "worker")
        apply(workerSession, workerExpected, "cityStateDemandTribute",
            "civId" to csId(workerSession), "kind" to "worker", "cityStateToken" to workerToken) {
            CityStateFixtures.tribute(workerExpected, true)
        }
    }

    @Test fun declareWarAndNegotiatePeaceMatchNative() {
        val (warSession, warExpected) = scenario("cs-war") { CityStateFixtures.minor(it) }
        apply(warSession, warExpected, "cityStateDeclareWar", "civId" to csId(warSession)) {
            CityStateFixtures.declareWar(warExpected)
        }
        reject(warSession, "CANNOT_DECLARE_WAR", request(warSession, "cityStateDeclareWar", "civId" to csId(warSession)))
        val (peaceSession, peaceExpected) = scenario("cs-peace") { CityStateFixtures.atWar(it) }
        apply(peaceSession, peaceExpected, "cityStateNegotiatePeace", "civId" to csId(peaceSession)) {
            CityStateFixtures.negotiatePeace(peaceExpected)
        }
        assertFalse(peaceSession.game!!.currentPlayerCiv.isAtWarWith(CityStateFixtures.cityState(peaceSession.game!!)))
    }

    @Test fun marriageMatchesNativeAndQueuesAssetDecision() {
        val (session, expected) = scenario("cs-marriage") { CityStateFixtures.austria(it) }
        val token = marriageToken(session)
        apply(session, expected, "cityStateMarriage", "civId" to csId(session), "cityStateToken" to token) {
            CityStateFixtures.marry(expected)
        }
        // 联姻后逐城弹出资产处置，交由既有 assetDecision 命令接管（此处仅验证队首弹窗类型）。
        val game = session.game!!
        assertEquals(com.unciv.logic.civilization.AlertType.DiplomaticMarriage, game.currentPlayerCiv.popupAlerts.first().type)
        assertTrue(CityStateFixtures.cityState(game).isDefeated())
    }

    @Test fun giftRejectedDuringWarOrInsufficientGold() {
        val (warSession, _) = scenario("cs-gift-war") { CityStateFixtures.atWar(it) }
        reject(warSession, "CANNOT_GIFT", request(warSession, "cityStateGiftGold",
            "civId" to csId(warSession), "amount" to 250, "cityStateToken" to giftToken(warSession, 250)))
        val (poorSession, _) = scenario("cs-gift-poor") { CityStateFixtures.minor(it); it.currentPlayerCiv.addGold(-it.currentPlayerCiv.gold) }
        reject(poorSession, "CANNOT_GIFT", request(poorSession, "cityStateGiftGold",
            "civId" to csId(poorSession), "amount" to 250, "cityStateToken" to giftToken(poorSession, 250)))
    }

    @Test fun giftRejectsStaleToken() {
        val (session, _) = scenario("cs-gift-token") { CityStateFixtures.minor(it) }
        reject(session, "CITY_STATE_TOKEN", request(session, "cityStateGiftGold",
            "civId" to csId(session), "amount" to 250, "cityStateToken" to "stale"))
        reject(session, "INVALID_ARGUMENT", request(session, "cityStateGiftGold",
            "civId" to csId(session), "amount" to 300, "cityStateToken" to giftToken(session, 250)))
    }

    @Test fun tributeRejectedWhenWillingnessNegative() {
        val (session, _) = scenario("cs-tribute-cold") { CityStateFixtures.minor(it) }
        reject(session, "CANNOT_TRIBUTE", request(session, "cityStateDemandTribute",
            "civId" to csId(session), "kind" to "gold", "cityStateToken" to tributeToken(session, "gold")))
    }

    @Test fun pledgeRejectedAtNegativeInfluenceAndRevokeCooldown() {
        val (negative, _) = scenario("cs-pledge-negative") { CityStateFixtures.minor(it, -5f) }
        reject(negative, "CANNOT_PLEDGE", request(negative, "cityStatePledgeProtection", "civId" to csId(negative)))
        // 承诺后 10 回合冷却内不能撤销。
        val (justPledged, _) = scenario("cs-revoke-cooldown") { CityStateFixtures.minor(it).also { cs -> cs.cityStateFunctions.addProtectorCiv(it.currentPlayerCiv) } }
        reject(justPledged, "CANNOT_REVOKE", request(justPledged, "cityStateRevokeProtection", "civId" to csId(justPledged)))
        // 撤销后 20 回合冷却内不能重新承诺。
        val (withdrew, _) = scenario("cs-pledge-withdrew") {
            CityStateFixtures.protectedCs(it).also { cs -> cs.cityStateFunctions.removeProtectorCiv(it.currentPlayerCiv) }
        }
        reject(withdrew, "CANNOT_PLEDGE", request(withdrew, "cityStatePledgeProtection", "civId" to csId(withdrew)))
    }

    @Test fun peaceRejectedWhenNotAtWarOrDuringCooldown() {
        val (peaceful, _) = scenario("cs-peace-notwar") { CityStateFixtures.minor(it) }
        reject(peaceful, "CANNOT_NEGOTIATE_PEACE", request(peaceful, "cityStateNegotiatePeace", "civId" to csId(peaceful)))
        // 宣战后保留 DeclaredWar 冷却，议和应被拒绝。
        val (cooldown, _) = scenario("cs-peace-cooldown") {
            CityStateFixtures.minor(it).also { cs -> it.currentPlayerCiv.getDiplomacyManager(cs)!!.declareWar() }
        }
        reject(cooldown, "CANNOT_NEGOTIATE_PEACE", request(cooldown, "cityStateNegotiatePeace", "civId" to csId(cooldown)))
    }

    @Test fun marriageRejectedForNonAustriaAndGiftImprovementNotACommand() {
        val (session, _) = scenario("cs-marriage-rome") { CityStateFixtures.minor(it, 100f) }
        reject(session, "UNSUPPORTED", request(session, "cityStateMarriage", "civId" to csId(session), "cityStateToken" to "x"))
        // 改良馈赠仅作为 DTO 只读 UNSUPPORTED 选项，未注册为可执行命令。
        reject(session, "UNKNOWN_COMMAND", request(session, "cityStateGiftImprovement", "civId" to csId(session)))
    }

    @Test fun emitIndependentCityStateSmokeFixtures() = CityStateFixtures.emit()
}
