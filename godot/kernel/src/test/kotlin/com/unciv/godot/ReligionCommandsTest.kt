package com.unciv.godot

import com.unciv.Constants
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class ReligionCommandsTest {

    // ---- 辅助 ----
    private fun scenario(name: String, build: () -> GameInfo): Pair<GameSession, GameInfo> {
        val file = ReligionFixtures.export(build(), name)
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }

    /** 只读查询：断言同一 GameInfo 引用、完整持久文本与 revision 均不变。 */
    private fun options(session: GameSession): JsonObject {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val data = run(session, "religionOptions")["data"]!!.jsonObject
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
        return data
    }

    private fun decision(session: GameSession) = options(session)["decision"]!!.jsonObject
    private fun symbols(session: GameSession) = options(session)["symbols"]!!.jsonArray.map { it.jsonObject }
    private fun firstSymbol(session: GameSession) = symbols(session).first { it.boolean("available") }.text("id")

    /** 从 decision 槽位的 candidateIds 逐项挑一个互不重复的可用信条（模拟前端选择，不使用网关资格函数）。 */
    private fun pick(d: JsonObject): List<String> {
        val used = HashSet<String>()
        return d["slots"]!!.jsonArray.map { slot ->
            slot.jsonObject["candidateIds"]!!.jsonArray.map { it.jsonPrimitive.content }.first { used.add(it) }
        }
    }

    /** 返回一个可执行预言家动作的 (unitId, actionToken)。 */
    private fun prophetAction(session: GameSession, mode: String): Pair<Int, String> {
        for (p in options(session)["prophets"]!!.jsonArray.map { it.jsonObject })
            for (a in p["actions"]!!.jsonArray.map { it.jsonObject })
                if (a.text("mode") == mode && a.boolean("enabled"))
                    return p["unitId"]!!.jsonPrimitive.int to a.text("actionToken")
        throw AssertionError("没有可用的 $mode 预言家动作")
    }

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

    /** 先在独立原生副本执行期望操作，再跑网关命令，全存档差分并验证幂等／重放／陈旧拒绝。 */
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

    // 期望端强化／免费信条完成选择（用原生 usingFreeBeliefs）
    private fun nativeChoose(expected: GameInfo, beliefs: List<String>) =
        ReligionFixtures.chooseBeliefs(expected, beliefs, ReligionFixtures.religion(expected).usingFreeBeliefs())

    // ---- fixture 合理性（原生独立期望）----
    @Test fun fixtureGeneratesProphetAfterPantheonNatively() {
        val file = ReligionFixtures.file("religion-sanity")
        var game = UncivFiles.gameInfoFromString(file.readText())
        native(game) {
            val manager = ReligionFixtures.religion(game)
            assertEquals(ReligionState.None, manager.religionState)
            assertTrue("预置信仰应足以创立万神殿", manager.canFoundOrExpandPantheon())
            val pantheon = ReligionFixtures.availableBeliefs(game, BeliefType.Pantheon).first()
            ReligionFixtures.chooseBeliefs(game, listOf(pantheon.name), manager.usingFreeBeliefs())
            assertEquals(ReligionState.Pantheon, manager.religionState)
            assertTrue("选万神殿后应可生成预言家", manager.canGenerateProphet())
        }
        game = ReligionFixtures.nextTurn(game)
        native(game) {
            assertNotNull("选万神殿后正常回合应生成预言家",
                ReligionFixtures.player(game).units.getCivUnits().firstOrNull { it.hasUnique(UniqueType.MayFoundReligion) })
        }
    }

    @Test fun fixtureBuildersReachExpectedStates() {
        native(ReligionFixtures.pantheonProphetGame()) {
            val g = com.unciv.UncivGame.Current.gameInfo!!
            assertEquals(ReligionState.Pantheon, ReligionFixtures.religion(g).religionState)
            assertNotNull(ReligionFixtures.player(g).units.getCivUnits().firstOrNull { it.hasUnique(UniqueType.MayFoundReligion) })
        }
        for (withPantheon in listOf(true, false)) native(ReligionFixtures.foundingGame(withPantheon)) {
            val g = com.unciv.UncivGame.Current.gameInfo!!
            assertEquals("withPantheon=$withPantheon", ReligionState.FoundingReligion, ReligionFixtures.religion(g).religionState)
        }
        native(ReligionFixtures.foundedGame()) {
            val g = com.unciv.UncivGame.Current.gameInfo!!
            assertEquals(ReligionState.Religion, ReligionFixtures.religion(g).religionState)
            assertNotNull(ReligionFixtures.religion(g).getHolyCity())
        }
        native(ReligionFixtures.enhancingGame()) {
            assertEquals(ReligionState.EnhancingReligion, ReligionFixtures.religion(com.unciv.UncivGame.Current.gameInfo!!).religionState)
        }
        native(ReligionFixtures.enhancingGameAtNonHolyCity()) {
            assertEquals(ReligionState.EnhancingReligion, ReligionFixtures.religion(com.unciv.UncivGame.Current.gameInfo!!).religionState)
        }
    }

    // ---- 万神殿 ----
    @Test fun pantheonPaidMatchesNativeAndReplaysOnce() {
        val (session, expected) = scenario("rel-pantheon") { ReligionFixtures.game() }
        val d = decision(session)
        assertEquals("pantheon", d.text("mode"))
        assertTrue(d.boolean("supported"))
        assertTrue(d.boolean("enabled"))
        assertFalse(d.boolean("useFreeBeliefs"))
        assertTrue("首次万神殿应扣信仰", d["faithCost"]!!.jsonPrimitive.int > 0)
        assertEquals(1, d["slots"]!!.jsonArray.size)
        val beliefs = pick(d)
        apply(session, expected, "religionChooseBeliefs", "decisionToken" to d.text("token"), "beliefs" to beliefs) {
            ReligionFixtures.chooseBeliefs(expected, beliefs, ReligionFixtures.religion(expected).usingFreeBeliefs())
        }
        assertEquals(ReligionState.Pantheon, ReligionFixtures.religion(session.game!!).religionState)
    }

    @Test fun expandPantheonIsFreeAndMatchesNative() {
        val (session, expected) = scenario("rel-expand") { ReligionFixtures.pantheonGame() }
        val d = decision(session)
        assertEquals("expandPantheon", d.text("mode"))
        assertTrue("扩展万神殿应免费", d.boolean("useFreeBeliefs"))
        assertEquals(0, d["faithCost"]!!.jsonPrimitive.int)
        val beliefs = pick(d)
        apply(session, expected, "religionChooseBeliefs", "decisionToken" to d.text("token"), "beliefs" to beliefs) {
            ReligionFixtures.chooseBeliefs(expected, beliefs, ReligionFixtures.religion(expected).usingFreeBeliefs())
        }
        assertEquals(ReligionState.Pantheon, ReligionFixtures.religion(session.game!!).religionState)
    }

    @Test fun insufficientFaithHasNoPendingDecision() {
        val (session, _) = scenario("rel-lowfaith") {
            ReligionFixtures.game().also { g -> native(g) { ReligionFixtures.religion(g).storedFaith = 0 } }
        }
        assertEquals(JsonNull, options(session)["decision"])
        assertFalse(ReligionFixtures.religion(session.game!!).canFoundOrExpandPantheon())
        reject(session, "RELIGION_DECISION", request(session, "religionChooseBeliefs", "decisionToken" to "x", "beliefs" to listOf("y")))
    }

    // ---- 创立（两步流程）----
    @Test fun foundingCycleWithPantheonMatchesNative() {
        val (session, expected) = scenario("rel-cycle") { ReligionFixtures.pantheonProphetGame() }
        val (unitId, actionToken) = prophetAction(session, "found")
        apply(session, expected, "religionUseProphet", "unitId" to unitId, "mode" to "found", "actionToken" to actionToken) {
            ReligionFixtures.useProphet(expected, ReligionFixtures.prophet(expected), true)
        }
        assertEquals(ReligionState.FoundingReligion, ReligionFixtures.religion(session.game!!).religionState)
        assertEquals("预言家应恰好消耗一次", 0,
            ReligionFixtures.player(session.game!!).units.getCivUnits().count { it.hasUnique(UniqueType.MayFoundReligion) })

        val d = decision(session)
        assertEquals("foundReligion", d.text("mode"))
        val beliefs = pick(d)
        val symbol = firstSymbol(session)
        apply(session, expected, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to symbol, "displayName" to "测试信仰", "beliefs" to beliefs) {
            ReligionFixtures.foundReligion(expected, "测试信仰", symbol, beliefs)
        }
        val manager = ReligionFixtures.religion(session.game!!)
        assertEquals(ReligionState.Religion, manager.religionState)
        assertNotNull("首都应成为圣城", manager.getHolyCity())
        assertEquals("测试信仰", manager.religion!!.getReligionDisplayName())
    }

    @Test fun foundingWithoutPantheonAddsPantheonSlotAndAcceptsDefaultName() {
        val (session, expected) = scenario("rel-nopanth") { ReligionFixtures.foundingGame(false) }
        val d = decision(session)
        assertEquals("foundReligion", d.text("mode"))
        val types = d["slots"]!!.jsonArray.map { it.jsonObject.text("type") }
        assertTrue("无万神殿创立应含 Pantheon 槽：$types", types.contains(BeliefType.Pantheon.name))
        val beliefs = pick(d)
        val symbol = firstSymbol(session)
        apply(session, expected, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to symbol, "displayName" to symbol, "beliefs" to beliefs) {
            ReligionFixtures.foundReligion(expected, symbol, symbol, beliefs)
        }
        assertEquals(ReligionState.Religion, ReligionFixtures.religion(session.game!!).religionState)
    }

    @Test fun useProphetRejectedWhileFoundingPending() {
        val (session, _) = scenario("rel-pending") {
            ReligionFixtures.foundingGame(true).also { g -> native(g) { ReligionFixtures.placeProphet(g) } }
        }
        val p = options(session)["prophets"]!!.jsonArray.map { it.jsonObject }.first()
        val a = p["actions"]!!.jsonArray.map { it.jsonObject }.first { it.text("mode") == "found" }
        assertFalse("已有待决时 found 应禁用", a.boolean("enabled"))
        reject(session, "RELIGION_DECISION", request(session, "religionUseProphet",
            "unitId" to p["unitId"]!!.jsonPrimitive.int, "mode" to "found", "actionToken" to a.text("actionToken")))
    }

    @Test fun foundAtNonOwnedCityRejectedBeforeConsume() {
        val (session, _) = scenario("rel-nonowned") {
            ReligionFixtures.game().also { g -> native(g) { ReligionFixtures.placeProphet(g, ReligionFixtures.aiCity) } }
        }
        val p = options(session)["prophets"]!!.jsonArray.map { it.jsonObject }.first()
        val found = p["actions"]!!.jsonArray.map { it.jsonObject }.first { it.text("mode") == "found" }
        assertFalse(found.boolean("enabled"))
        assertEquals("只能在本方城市中心创立宗教", found.text("reason"))
        reject(session, "UNSUPPORTED", request(session, "religionUseProphet",
            "unitId" to p["unitId"]!!.jsonPrimitive.int, "mode" to "found", "actionToken" to found.text("actionToken")))
        assertEquals("预言家不得被消耗", 1,
            ReligionFixtures.player(session.game!!).units.getCivUnits().count { it.hasUnique(UniqueType.MayFoundReligion) })
    }

    // ---- 强化 ----
    @Test fun enhanceAtHolyCityMatchesNative() {
        val (session, expected) = scenario("rel-enhance") { ReligionFixtures.enhancingGame() }
        val d = decision(session)
        assertEquals("enhanceReligion", d.text("mode"))
        val beliefs = pick(d)
        apply(session, expected, "religionChooseBeliefs", "decisionToken" to d.text("token"), "beliefs" to beliefs) {
            nativeChoose(expected, beliefs)
        }
        assertEquals(ReligionState.EnhancedReligion, ReligionFixtures.religion(session.game!!).religionState)
    }

    @Test fun useProphetEnhanceAllowedAtNonHolyNonOwnedCity() {
        val (session, expected) = scenario("rel-enhance-use") {
            ReligionFixtures.foundedGame().also { g -> native(g) { ReligionFixtures.placeProphet(g, ReligionFixtures.aiCity) } }
        }
        val (unitId, actionToken) = prophetAction(session, "enhance")
        apply(session, expected, "religionUseProphet", "unitId" to unitId, "mode" to "enhance", "actionToken" to actionToken) {
            ReligionFixtures.useProphet(expected, ReligionFixtures.prophet(expected), false)
        }
        assertEquals(ReligionState.EnhancingReligion, ReligionFixtures.religion(session.game!!).religionState)
    }

    // ---- 免费信条 ----
    @Test fun freeBeliefsQuotaClearedOnAdopt() {
        val (session, expected) = scenario("rel-free") {
            ReligionFixtures.foundedGame().also { g -> native(g) { ReligionFixtures.religion(g).freeBeliefs.add(BeliefType.Follower.name, 1) } }
        }
        val d = decision(session)
        assertEquals("freeBeliefs", d.text("mode"))
        val beliefs = pick(d)
        apply(session, expected, "religionChooseBeliefs", "decisionToken" to d.text("token"), "beliefs" to beliefs) {
            nativeChoose(expected, beliefs)
        }
        assertEquals("采用后免费额度应清空", 0, ReligionFixtures.religion(session.game!!).freeBeliefs.sumValues())
    }

    @Test fun duplicateBeliefRejected() {
        val (session, _) = scenario("rel-dup") {
            ReligionFixtures.foundedGame().also { g -> native(g) { ReligionFixtures.religion(g).freeBeliefs.add(BeliefType.Follower.name, 2) } }
        }
        val d = decision(session)
        assertEquals("freeBeliefs", d.text("mode"))
        assertEquals(2, d["slots"]!!.jsonArray.size)
        val x = d["slots"]!!.jsonArray[0].jsonObject["candidateIds"]!!.jsonArray.first().jsonPrimitive.content
        reject(session, "BELIEF_CHOICE", request(session, "religionChooseBeliefs", "decisionToken" to d.text("token"), "beliefs" to listOf(x, x)))
    }

    // ---- 名称 ----
    @Test fun customNamesAccepted() {
        val names = listOf("测试信仰", "Foi Chrétienne", "Åsatro", "a".repeat(32), " ", " 前后空格 ")
        for ((index, name) in names.withIndex()) {
            val (session, expected) = scenario("rel-name-ok-$index") { ReligionFixtures.foundingGame(true) }
            val d = decision(session)
            val beliefs = pick(d)
            val symbol = firstSymbol(session)
            apply(session, expected, "religionFound", "decisionToken" to d.text("token"),
                "religionId" to symbol, "displayName" to name, "beliefs" to beliefs) {
                ReligionFixtures.foundReligion(expected, name, symbol, beliefs)
            }
            assertEquals("名称[$name]", name, ReligionFixtures.religion(session.game!!).religion!!.getReligionDisplayName())
        }
    }

    @Test fun invalidNamesRejected() {
        val (session, _) = scenario("rel-name-bad") { ReligionFixtures.foundingGame(true) }
        val d = decision(session)
        val beliefs = pick(d)
        val allSymbols = symbols(session).filter { it.boolean("available") }.map { it.text("id") }
        val religionId = allSymbols.first()
        val otherSymbol = allSymbols[1]
        val existing = ReligionFixtures.religion(session.game!!).religion!!.name
        val bad = listOf("", Constants.noReligionName, otherSymbol, existing, "x".repeat(33),
            "bad[name]", "bad]name", "bad{name}", "bad\"name", "bad\\name", "bad<name>", "bad>name", "line\nbreak", "line\rbreak")
        for (name in bad)
            reject(session, "RELIGION_NAME", request(session, "religionFound", "decisionToken" to d.text("token"),
                "religionId" to religionId, "displayName" to name, "beliefs" to beliefs))
    }

    @Test fun usedSymbolAndUnknownSymbolRejected() {
        val (game, taken) = ReligionFixtures.foundingGameWithTakenSymbol()
        val session = load(ReligionFixtures.export(game, "rel-taken"))
        val d = decision(session)
        val beliefs = pick(d)
        val occupied = session.game!!.religions[taken]!!.getAllBeliefsOrdered().first().name
        reject(session, "RELIGION_NAME", request(session, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to taken, "displayName" to taken, "beliefs" to beliefs))
        reject(session, "RELIGION_NAME", request(session, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to "NoSuchSymbol", "displayName" to "NoSuchSymbol", "beliefs" to beliefs))
        val follower = d["slots"]!!.jsonArray.map { it.jsonObject }.first { it.text("type") == BeliefType.Follower.name }["candidateIds"]!!.jsonArray.first().jsonPrimitive.content
        reject(session, "BELIEF_CHOICE", request(session, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to firstSymbol(session), "displayName" to firstSymbol(session), "beliefs" to listOf(occupied, follower)))
    }

    // ---- 信条校验 ----
    @Test fun beliefChoiceValidationRejects() {
        val (session, _) = scenario("rel-beliefs") { ReligionFixtures.foundingGame(true) }
        val d = decision(session)
        val token = d.text("token")
        val symbol = firstSymbol(session)
        val slots = d["slots"]!!.jsonArray.map { it.jsonObject }
        val founder = slots.first { it.text("type") == BeliefType.Founder.name }["candidateIds"]!!.jsonArray.first().jsonPrimitive.content
        val follower = slots.first { it.text("type") == BeliefType.Follower.name }["candidateIds"]!!.jsonArray.first().jsonPrimitive.content
        fun found(vararg beliefs: String) = request(session, "religionFound", "decisionToken" to token,
            "religionId" to symbol, "displayName" to symbol, "beliefs" to beliefs.toList())
        reject(session, "BELIEF_CHOICE", found(follower, founder))          // 类型乱序
        reject(session, "BELIEF_CHOICE", found(founder))                     // 数量不足
        reject(session, "BELIEF_CHOICE", found(founder, follower, founder))  // 数量超出
        reject(session, "BELIEF_CHOICE", found("NoSuchBelief", follower))    // 未知信条
    }

    // ---- 票据 ----
    @Test fun tokenMismatchAndStaleRejected() {
        val (session, _) = scenario("rel-token") { ReligionFixtures.foundingGame(true) }
        val d = decision(session)
        val beliefs = pick(d)
        val symbol = firstSymbol(session)
        reject(session, "RELIGION_DECISION", request(session, "religionFound", "decisionToken" to "wrong",
            "religionId" to symbol, "displayName" to symbol, "beliefs" to beliefs))
        native(session.game!!) { ReligionFixtures.religion(session.game!!).storedFaith += 7 }
        reject(session, "RELIGION_DECISION", request(session, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to symbol, "displayName" to symbol, "beliefs" to beliefs))
    }

    @Test fun wrongCommandForModeRejected() {
        val (session, _) = scenario("rel-wrongcmd") { ReligionFixtures.foundingGame(true) }
        val d = decision(session)
        reject(session, "RELIGION_DECISION", request(session, "religionChooseBeliefs", "decisionToken" to d.text("token"), "beliefs" to pick(d)))
        val (s2, _) = scenario("rel-wrongcmd2") { ReligionFixtures.game() }
        val d2 = decision(s2)
        reject(s2, "RELIGION_DECISION", request(s2, "religionFound", "decisionToken" to d2.text("token"),
            "religionId" to firstSymbol(s2), "displayName" to "x", "beliefs" to pick(d2)))
    }

    // ---- 参数与权限 ----
    @Test fun strictArgumentValidation() {
        val (session, _) = scenario("rel-args") { ReligionFixtures.foundingGame(true) }
        val d = decision(session)
        val symbol = firstSymbol(session)
        val beliefs = pick(d)
        val valid = request(session, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to symbol, "displayName" to symbol, "beliefs" to beliefs)
        reject(session, "INVALID_ARGUMENT", JsonObject(valid + ("extra" to JsonPrimitive(1))))
        reject(session, "INVALID_ARGUMENT", JsonObject(valid - "religionId"))
        reject(session, "INVALID_ARGUMENT", JsonObject(valid + ("beliefs" to JsonPrimitive("x"))))
        reject(session, "INVALID_ARGUMENT", JsonObject(valid + ("beliefs" to JsonArray(listOf(JsonPrimitive(1))))))
        reject(session, "INVALID_ARGUMENT", JsonObject(valid + ("decisionToken" to JsonPrimitive(5))))
        reject(session, "INVALID_ARGUMENT", request(session, "religionUseProphet", "unitId" to "1", "mode" to "found", "actionToken" to "x"))
        reject(session, "INVALID_ARGUMENT", request(session, "religionUseProphet", "unitId" to 1, "mode" to "spread", "actionToken" to "x"))
    }

    @Test fun permissionsRejectReligionWritesBeforeBackup() {
        for (mode in listOf("online", "mods")) {
            val (session, _) = scenario("rel-perm-$mode") { ReligionFixtures.foundingGame(true) }
            val g = session.game!!
            if (mode == "online") g.gameParameters.isOnlineMultiplayer = true else g.gameParameters.mods.add("unsupported-test-mod")
            for (action in listOf("religionUseProphet", "religionChooseBeliefs", "religionFound"))
                reject(session, if (mode == "mods") "UNSUPPORTED_MOD" else "UNSUPPORTED_GAME", request(session, action))
        }
    }

    @Test fun unknownReligionActionRejected() {
        val (session, _) = scenario("rel-unknown") { ReligionFixtures.game() }
        reject(session, "UNKNOWN_COMMAND", request(session, "religionSpread"))
    }

    // ---- 快照／待决／单位能力 ----
    @Test fun snapshotExposesReligionSummaryAndBlocksNextTurn() {
        val (session, _) = scenario("rel-snapshot") { ReligionFixtures.foundingGame(true) }
        val snap = run(session, "snapshot")["snapshot"]!!.jsonObject
        val rel = snap["religion"]!!.jsonObject
        assertTrue(rel.boolean("enabled"))
        assertEquals("FoundingReligion", rel.text("state"))
        assertEquals("foundReligion", rel.text("pendingMode"))
        val pending = snap["pending"]!!.jsonArray.map { it.jsonObject }.first { it.text("kind") == "religion" }
        assertEquals("foundReligion", pending.text("target"))
        assertTrue(pending.boolean("supported"))
        reject(session, "PENDING_DECISION", request(session, "nextTurn"))
    }

    @Test fun unitOptionsExposeReligionCapability() {
        val (session, _) = scenario("rel-unit") { ReligionFixtures.pantheonProphetGame() }
        val prophet = ReligionFixtures.prophet(session.game!!)
        val rel = run(session, "unitOptions", "unitId" to prophet.id)["data"]!!.jsonObject["religion"]!!.jsonObject
        assertTrue("万神殿后应可创立", rel["found"]!!.jsonObject.boolean("enabled"))
        assertFalse("非主宗教状态不可强化", rel["enhance"]!!.jsonObject.boolean("enabled"))
    }

    @Test fun emitIndependentReligionSmokeFixtures() { ReligionFixtures.emit() }

    @Test fun twoStepFoundingSurvivesSaveReload() {
        val (session, expected) = scenario("rel-reload") { ReligionFixtures.pantheonProphetGame() }
        val (unitId, actionToken) = prophetAction(session, "found")
        apply(session, expected, "religionUseProphet", "unitId" to unitId, "mode" to "found", "actionToken" to actionToken) {
            ReligionFixtures.useProphet(expected, ReligionFixtures.prophet(expected), true)
        }
        val saved = run(session, "save", "name" to "rel-reload-step1")
        run(session, "load", "path" to saved.text("savedPath"))
        assertGameplayEquals("第一步重载", UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(expected, true)), session.game!!)
        val d = decision(session)
        assertEquals("foundReligion", d.text("mode"))
        val beliefs = pick(d)
        val symbol = firstSymbol(session)
        apply(session, expected, "religionFound", "decisionToken" to d.text("token"),
            "religionId" to symbol, "displayName" to "重载信仰", "beliefs" to beliefs) {
            ReligionFixtures.foundReligion(expected, "重载信仰", symbol, beliefs)
        }
        val saved2 = run(session, "save", "name" to "rel-reload-step2")
        run(session, "load", "path" to saved2.text("savedPath"))
        assertGameplayEquals("第二步重载", UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(expected, true)), session.game!!)
        val nextExpected = ReligionFixtures.nextTurn(expected)
        run(session, "nextTurn")
        assertGameplayEquals("创立后正常回合", nextExpected, session.game!!)
    }
}
