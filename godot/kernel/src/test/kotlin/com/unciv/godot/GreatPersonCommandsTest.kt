package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GreatPersonFixtures.choose
import com.unciv.godot.GreatPersonFixtures.game
import com.unciv.godot.GreatPersonFixtures.manager
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class GreatPersonCommandsTest {
    private fun scenario(game: GameInfo): Pair<GameSession, GameInfo> {
        val file = GreatPersonFixtures.export(game, "great-person-test")
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }
    private fun options(session: GameSession): JsonObject {
        val current = session.game!!
        val state = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val data = run(session, "greatPersonOptions")["data"]!!.jsonObject
        assertEquals(data, run(session, "greatPersonOptions")["data"])
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(state, UncivFiles.gameInfoToString(current, false))
        return data
    }
    private fun body(session: GameSession, name: String) = request(session, "greatPersonChoose",
        "unitName" to name, "decisionToken" to options(session)["decision"]!!.jsonObject.text("token"))
    private fun reject(session: GameSession, body: JsonObject, code: String) {
        val current = session.game!!
        val state = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val result = session.handle(body)
        assertEquals(result.toString(), code, result["error"]?.jsonObject?.text("code"))
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(state, UncivFiles.gameInfoToString(current, false))
    }
    private fun grant(session: GameSession, expected: GameInfo, name: String, outcome: String = "granted") {
        val body = body(session, name)
        val unit = choose(expected, name)
        assertEquals(outcome == "granted", unit != null)
        val revision = session.revision
        val current = session.game!!
        val response = session.handle(body)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        val result = response["greatPersonResult"]!!.jsonObject
        assertEquals(outcome, result.text("outcome"))
        assertSame(current, session.game)
        assertEquals(revision + 1, session.revision)
        assertGameplayEquals("领取 $name", expected, current)
        if (unit != null) {
            assertEquals(unit.id, result.integer("unitId"))
            val actual = current.currentPlayerCiv.units.getCivUnits().single { it.id == unit.id }
            assertSame(actual, actual.currentTile.civilianUnit)
            assertSame(current.tileMap[actual.currentTile.position], actual.currentTile)
            assertEquals(current.currentPlayerCiv.civID, actual.owner)
        } else assertEquals(JsonNull, result["unitId"])
        val after = UncivFiles.gameInfoToString(current, false)
        assertEquals(response, session.handle(body))
        assertEquals(after, UncivFiles.gameInfoToString(current, false))
        assertEquals(revision + 1, session.revision)
        reject(session, JsonObject(body + ("extra" to JsonPrimitive(1))), "REQUEST_REUSED")
        reject(session, JsonObject(body + ("requestId" to JsonPrimitive("stale-$revision"))), "STALE_STATE")
    }

    @Test fun exportNativeSmokeScenarios() { GreatPersonFixtures.emit() }

    @Test fun nativeFixturesReachRealRewardsAndPlacementOutcomes() {
        val liberty = GreatPersonFixtures.copy(GreatPersonFixtures.liberty())
        assertEquals(0, manager(liberty).freeGreatPeople)
        GreatPersonFixtures.adoptMeritocracy(liberty)
        assertEquals(1, manager(liberty).freeGreatPeople)
        assertNotNull(choose(liberty, "Great Scientist"))
        val maya = GreatPersonFixtures.nextTurn(GreatPersonFixtures.copy(GreatPersonFixtures.maya()))
        assertEquals(1, manager(maya).freeGreatPeople)
        assertEquals(1, manager(maya).mayaLimitedFreeGP)
        val blocked = GreatPersonFixtures.copy(game(blocked = true))
        val lastId = GreatPersonFixtures.lastId(blocked)
        assertNull(choose(blocked, "Great Scientist"))
        assertEquals(lastId + 1, GreatPersonFixtures.lastId(blocked))
        GreatPersonFixtures.moveBlocker(blocked)
        assertNotNull(choose(blocked, "Great Scientist"))
        assertNull(choose(game(), "Great Admiral"))
        assertNotNull(choose(game(coastal = true), "Great Admiral"))
    }
    @Test fun bothBaseRulesAndMongolianReplacement() {
        for (rules in BaseRuleset.entries) {
            val (session, expected) = scenario(game("Mongolia", rules))
            val names = options(session)["candidates"]!!.jsonArray.map { it.jsonObject.text("unitName") }
            assertEquals(manager(expected).getGreatPeople().map { it.name }.sorted(), names)
            assertTrue("Khan" in names)
            assertFalse("Great General" in names)
            reject(session, body(session, "Great General"), "GREAT_PERSON_CHOICE")
            grant(session, expected, "Khan")
        }
    }
    @Test fun mixedQuotasUseMayaFirstThenOrdinary() {
        val (session, expected) = scenario(GreatPersonFixtures.mixed())
        reject(session, body(session, "Great Engineer"), "GREAT_PERSON_CHOICE")
        grant(session, expected, "Great Scientist")
        assertEquals("ordinary", options(session)["decision"]!!.jsonObject.text("mode"))
        grant(session, expected, "Great Engineer")
        assertEquals(JsonNull, options(session)["decision"])
        assertFalse(PlayerSnapshot(session.game!!).pending().any { it.text("kind") == "greatPerson" })
    }
    @Test fun normalGrantPreservesHistoricPoolAndPointCounters() {
        val fixture = game(free = 2)
        manager(fixture).longCountGPPool = hashSetOf("stored value", "Great Scientist")
        val (session, expected) = scenario(fixture)
        val counters = manager(session.game!!).pointsForNextGreatPersonCounter.clone()
        grant(session, expected, "Great Scientist")
        grant(session, expected, "Great Engineer")
        assertEquals(counters, manager(session.game!!).pointsForNextGreatPersonCounter)
        assertEquals(setOf("stored value", "Great Scientist"), manager(session.game!!).longCountGPPool)
    }
    @Test fun blockedAttemptCommitsIdThenMovementAllowsNewAttempt() {
        val (session, expected) = scenario(game(blocked = true))
        grant(session, expected, "Great Scientist", "notPlaced")
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        val worker = session.game!!.tileMap[HexCoord(10, 0)].civilianUnit!!
        GreatPersonFixtures.moveBlocker(expected)
        run(session, "move", "unitId" to worker.id, "x" to 11, "y" to 0)
        assertGameplayEquals("移开阻挡单位", expected, session.game!!)
        grant(session, expected, "Great Scientist")
    }
    @Test fun admiralUsesNativeCoastalRules() {
        for (coastal in listOf(false, true)) {
            val (session, expected) = scenario(game(coastal = coastal))
            grant(session, expected, "Great Admiral", if (coastal) "granted" else "notPlaced")
        }
    }
    @Test fun noQuotaAndEmptyMayaPoolStayReadOnly() {
        val (session, _) = scenario(game(free = 0))
        assertEquals(JsonNull, options(session)["decision"])
        reject(session, request(session, "greatPersonChoose", "unitName" to "Great Scientist", "decisionToken" to "x"), "NO_FREE_GREAT_PERSON")
        manager(session.game!!).freeGreatPeople = 1
        manager(session.game!!).mayaLimitedFreeGP = 1
        val data = options(session)
        assertFalse(data["decision"]!!.jsonObject.boolean("enabled"))
        reject(session, body(session, "Great Scientist"), "NO_GREAT_PERSON_OPTIONS")
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        assertTrue(manager(session.game!!).longCountGPPool.isEmpty())
    }
    @Test fun strictArgumentsAndStaleTicketsAreRejectedBeforeBackup() {
        val (session, _) = scenario(game())
        val original = body(session, "Great Scientist")
        for (key in listOf("unitName", "decisionToken")) {
            reject(session, JsonObject(original - key), "INVALID_ARGUMENT")
            for (bad in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive(""), JsonArray(emptyList())))
                reject(session, JsonObject(original + (key to bad)), "INVALID_ARGUMENT")
        }
        reject(session, JsonObject(original + ("cityId" to JsonPrimitive("x"))), "INVALID_ARGUMENT")
        reject(session, JsonObject(original + ("decisionToken" to JsonPrimitive("wrong"))), "GREAT_PERSON_DECISION")
        reject(session, request(session, "greatPersonOptions", "x" to 0), "INVALID_ARGUMENT")
        manager(session.game!!).longCountGPPool.add("stored value")
        reject(session, original, "GREAT_PERSON_DECISION")
    }
    @Test fun citylessDecisionIsSupportedButCannotExecute() {
        val (session, _) = scenario(game())
        session.game!!.currentPlayerCiv.cities = emptyList()
        val decision = options(session)["decision"]!!.jsonObject
        assertTrue(decision.boolean("supported"))
        assertFalse(decision.boolean("enabled"))
        reject(session, body(session, "Great Scientist"), "NO_CITY")
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
    }
    @Test fun capitalFallbackAndOccupiedCenterFollowNativePlacement() {
        for (occupied in listOf(false, true)) {
            val fixture = game()
            GreatPersonFixtures.native(fixture) {
                val city = fixture.currentPlayerCiv.cities.first()
                city.cityConstructions.removeBuilding(fixture.ruleset.buildings["Palace"]!!)
                if (occupied) BattleFixtures.add(fixture, "Worker", 0, 0)
            }
            val (session, expected) = scenario(fixture)
            assertNull(session.game!!.currentPlayerCiv.getCapital(false))
            grant(session, expected, "Great Scientist")
            val unit = session.game!!.currentPlayerCiv.units.getCivUnits().single { it.name == "Great Scientist" }
            assertEquals(if (occupied) 1 else 0, unit.currentTile.aerialDistanceTo(session.game!!.tileMap[HexCoord(0, 0)]))
        }
    }
    @Test fun prophetAvailabilityFollowsReligionSetting() {
        for (enabled in listOf(true, false)) {
            val fixture = game()
            if (!enabled) fixture.gameParameters.startingEra = "Industrial era"
            val (session, expected) = scenario(fixture)
            assertEquals(enabled, session.game!!.isReligionEnabled())
            assertEquals(enabled, options(session)["candidates"]!!.jsonArray.any { it.jsonObject.text("unitName") == "Great Prophet" })
            if (enabled) grant(session, expected, "Great Prophet")
            else reject(session, body(session, "Great Prophet"), "GREAT_PERSON_CHOICE")
        }
    }
    @Test fun multipleMayaQuotasPreserveStalePoolEntries() {
        val fixture = game("The Maya", free = 2)
        manager(fixture).mayaLimitedFreeGP = 2
        manager(fixture).longCountGPPool = hashSetOf("Great Scientist", "Great Engineer", "obsolete")
        val (session, expected) = scenario(fixture)
        grant(session, expected, "Great Scientist")
        reject(session, body(session, "Great Scientist"), "GREAT_PERSON_CHOICE")
        grant(session, expected, "Great Engineer")
        assertEquals(setOf("obsolete"), manager(session.game!!).longCountGPPool)
        assertEquals(JsonNull, options(session)["decision"])
    }
    @Test fun unrelatedAlertsAndTradesAreNotConsumed() {
        val fixture = game()
        GreatPersonFixtures.native(fixture) {
            fixture.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(BattleFixtures.enemy(fixture))
            fixture.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, BattleFixtures.enemy(fixture).civID))
            DiplomacyFixtures.incoming(fixture)
        }
        val (session, expected) = scenario(fixture)
        val alerts = session.game!!.currentPlayerCiv.popupAlerts.toList()
        val trades = session.game!!.currentPlayerCiv.tradeRequests.toList()
        grant(session, expected, "Great Scientist")
        assertEquals(alerts, session.game!!.currentPlayerCiv.popupAlerts)
        assertEquals(trades, session.game!!.currentPlayerCiv.tradeRequests)
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
    }
    @Test fun unsupportedSessionStatesRejectBeforeBackup() {
        for (mode in listOf("online", "mods", "player", "multiple")) {
            val (session, _) = scenario(game())
            val body = body(session, "Great Scientist")
            val current = session.game!!
            when (mode) {
                "online" -> current.gameParameters.isOnlineMultiplayer = true
                "mods" -> current.gameParameters.mods.add("unsupported-test-mod")
                "player" -> current.currentPlayerCiv.playerType = PlayerType.AI
                "multiple" -> BattleFixtures.enemy(current).playerType = PlayerType.Human
            }
            val code = if (mode == "mods") "UNSUPPORTED_MOD" else "UNSUPPORTED_GAME"
            reject(session, body, code)
            reject(session, request(session, "greatPersonOptions"), code)
        }
    }
    private fun reload(session: GameSession, expected: GameInfo): GameInfo {
        val old = body(session, "Great Scientist")
        val revision = session.revision
        val saved = run(session, "save", "name" to "great-person-reload").text("savedPath")
        assertEquals(revision + 1, session.revision)
        assertGameplayEquals("保存", expected, UncivFiles.gameInfoFromString(java.io.File(saved).readText()))
        run(session, "load", "path" to saved)
        val native = GreatPersonFixtures.copy(expected)
        assertGameplayEquals("重载", native, session.game!!)
        reject(session, JsonObject(old + ("requestId" to JsonPrimitive("reload-${session.revision}"))), "STALE_STATE")
        reject(session, request(session, "greatPersonChoose", "unitName" to "Great Scientist", "decisionToken" to old.text("decisionToken")), "GREAT_PERSON_DECISION")
        return native
    }
    @Test fun intermediateSavesKeepQuotasPoolsAndAttemptIds() {
        val (session, initial) = scenario(GreatPersonFixtures.mixed())
        var expected = reload(session, initial)
        grant(session, expected, "Great Scientist")
        expected = reload(session, expected)
        grant(session, expected, "Great Engineer")
        val saved = run(session, "save", "name" to "great-person-final").text("savedPath")
        run(session, "load", "path" to saved)
        assertGameplayEquals("最终重载", GreatPersonFixtures.copy(expected), session.game!!)
        val (blocked, original) = scenario(game(blocked = true))
        grant(blocked, original, "Great Scientist", "notPlaced")
        val restored = reload(blocked, original)
        GreatPersonFixtures.moveBlocker(restored)
        run(blocked, "move", "unitId" to blocked.game!!.tileMap[HexCoord(10, 0)].civilianUnit!!.id, "x" to 11, "y" to 0)
        grant(blocked, restored, "Great Scientist")
    }
    @Test fun naturalLibertyAndMayaRewardsUseRealCommands() {
        for (kind in listOf("liberty", "maya")) {
            val (session, initial) = scenario(if (kind == "liberty") GreatPersonFixtures.liberty() else GreatPersonFixtures.maya())
            var expected = initial
            if (kind == "liberty") {
                GreatPersonFixtures.adoptMeritocracy(expected)
                run(session, "policy", "name" to "Meritocracy")
            } else {
                expected = GreatPersonFixtures.nextTurn(expected)
                run(session, "nextTurn")
            }
            assertGameplayEquals("自然奖励 $kind", expected, session.game!!)
            expected = reload(session, expected)
            grant(session, expected, "Great Scientist")
            val saved = run(session, "save", "name" to "great-person-natural-$kind").text("savedPath")
            run(session, "load", "path" to saved)
            expected = GreatPersonFixtures.copy(expected)
            assertGameplayEquals("领取后重载 $kind", expected, session.game!!)
            // 无关提示也通过与网关相同的原生动作逐个处理，不能中途清空待决。
            while (expected.currentPlayerCiv.popupAlerts.isNotEmpty()) {
                assertTrue(expected.currentPlayerCiv.popupAlerts.first().type in GameSession.informationalAlerts)
                GreatPersonFixtures.native(expected) { expected.currentPlayerCiv.popupAlerts.removeAt(0) }
                run(session, "acknowledge")
            }
            expected = GreatPersonFixtures.nextTurn(expected)
            run(session, "nextTurn")
            assertGameplayEquals("继续回合 $kind", expected, session.game!!)
        }
    }
    @Test fun abnormalQuotasAreUnsupportedPending() {
        for ((free, limited) in listOf(-1 to 0, 1 to -1, 1 to 2, 0 to 1)) {
            val fixture = game(free = free)
            manager(fixture).mayaLimitedFreeGP = limited
            val (session, _) = scenario(fixture)
            val data = options(session)
            assertEquals(JsonNull, data["ordinaryFreeGreatPeople"])
            assertFalse(data["decision"]!!.jsonObject.boolean("supported"))
            reject(session, body(session, "Great Scientist"), "UNSUPPORTED")
            reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        }
    }
}
