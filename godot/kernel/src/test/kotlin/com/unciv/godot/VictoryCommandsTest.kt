package com.unciv.godot

import com.badlogic.gdx.Gdx
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.godot.VictoryFixtures.apply
import com.unciv.godot.VictoryFixtures.conquest
import com.unciv.godot.VictoryFixtures.copy
import com.unciv.godot.VictoryFixtures.defeated
import com.unciv.godot.VictoryFixtures.emit
import com.unciv.godot.VictoryFixtures.export
import com.unciv.godot.VictoryFixtures.recorded
import com.unciv.godot.VictoryFixtures.screenUpdate
import com.unciv.logic.GameInfo
import com.unciv.logic.VictoryData
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.translations.tr
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 期望端只走原生规则、检查与继续标志；每一步比较完整持久化玩法状态。 */
class VictoryCommandsTest {
    private fun scenario(initial: GameInfo): Pair<GameSession, GameInfo> {
        val file = export(initial, "test")
        return load(file) to copy(initial).also { screenUpdate(it) }
    }
    private fun options(session: GameSession): JsonObject {
        val current = session.game!!
        val before = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val data = run(session, "victoryOptions")["data"]!!.jsonObject
        assertEquals(data, run(session, "victoryOptions")["data"])
        run(session, "snapshot")
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(current, false))
        return data
    }
    private fun body(session: GameSession) = request(session, "victoryContinue",
        "resultToken" to options(session)["continue"]!!.jsonObject.text("token"))
    private fun reject(session: GameSession, request: JsonObject, code: String) {
        val current = session.game!!
        val before = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val response = session.handle(request)
        assertEquals(response.toString(), code, response["error"]?.jsonObject?.text("code"))
        assertEquals(revision, session.revision)
        assertSame(current, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(current, false))
    }
    @Test fun progressMatchesNativeFilteringTextAndMilestonesInBothRulesets() {
        for (rules in BaseRuleset.entries) {
            val (session, expected) = scenario(VictoryFixtures.game(rules))
            val data = options(session)
            assertEquals("ongoing", data.text("outcome"))
            assertEquals(JsonNull, data["recorded"])
            assertEquals(500, data.integer("maxTurns"))
            assertEquals(expected.currentPlayerCiv.calculateTotalScore(), data["score"]!!.jsonPrimitive.double, 0.0)
            val shown = expected.currentPlayerCiv.victoryManager.getVictoriesShownInVictoryScreen().toList()
            val progress = data["progress"]!!.jsonArray.map { it.jsonObject }
            assertEquals(shown.map { it.name }, progress.map { it.text("name") })
            for ((index, victory) in shown.withIndex()) {
                val milestones = progress[index]["milestones"]!!.jsonArray.map { it.jsonObject }
                val firstIncomplete = victory.milestoneObjects.indexOfFirst { !it.hasBeenCompletedBy(expected.currentPlayerCiv) }
                for ((i, milestone) in victory.milestoneObjects.withIndex()) {
                    val completed = milestone.hasBeenCompletedBy(expected.currentPlayerCiv)
                    assertEquals(if (completed) "Completed" else if (i == firstIncomplete) "Partially" else "Incomplete", milestones[i].text("status"))
                    assertEquals(milestone.getVictoryScreenButtonHeaderText(completed, expected.currentPlayerCiv).tr(hideIcons = true), milestones[i].text("text"))
                }
            }
            session.game!!.gameParameters.victoryTypes.remove("Time")
            assertEquals(JsonNull, options(session)["maxTurns"])
            reject(session, request(session, "victoryOptions", "player" to "Greece"), "INVALID_ARGUMENT")
            reject(session, request(session, "victoryContinue", "resultToken" to "x"), "NO_VICTORY_DECISION")
        }
        assertNull(Gdx.app); assertNull(Gdx.gl); assertNull(Gdx.audio)
    }
    @Test fun scientificCulturalAndTimeThresholdsUseNativeVictoryChecks() {
        for (rules in BaseRuleset.entries) for (type in listOf("Scientific", "Cultural", "Time")) {
            val initial = VictoryFixtures.game(rules)
            initial.gameParameters.victoryTypes = arrayListOf(type)
            val player = initial.currentPlayerCiv
            fun verify(won: Boolean, statuses: List<String>) {
                val expected = copy(initial)
                BattleFixtures.native(expected) { assertEquals(won, expected.checkForVictory()) }
                val session = load(export(initial, "threshold"))
                assertGameplayEquals("原生达标 $rules $type $statuses", expected, session.game!!)
                val data = options(session)
                assertEquals(if (won) "won" else "ongoing", data.text("outcome"))
                val progress = data["progress"]!!.jsonArray
                if (type == "Time") assertTrue(progress.isEmpty())
                else assertEquals(statuses, progress.single().jsonObject["milestones"]!!.jsonArray.map { it.jsonObject.text("status") })
                if (won) assertEquals(initial.turns, data["recorded"]!!.jsonObject.integer("turn"))
                // 检查发生在独立副本和 load 事务，夹具本身始终没有手写或补写 victoryData。
                assertNull(initial.victoryData)
            }
            verify(false, listOf("Partially", "Incomplete"))
            BattleFixtures.native(initial) {
                when (type) {
                    "Scientific" -> player.cities.first().cityConstructions.addBuilding("Apollo Program")
                    "Cultural" -> {
                        player.policies.freePolicies = 100
                        for (name in listOf("Tradition", "Liberty", "Honor", "Piety", "Patronage")) {
                            val branch = initial.ruleset.policyBranches[name]!!
                            if (!player.policies.isAdopted(branch.name)) player.policies.adopt(branch)
                            for (policy in branch.policies.dropLast(1))
                                if (!player.policies.isAdopted(policy.name)) player.policies.adopt(policy)
                        }
                        assertEquals(5, player.policies.completedBranches.size)
                    }
                    "Time" -> {
                        player.tech.repeatingTechsResearched = 10000
                        initial.turns = initial.gameParameters.maxTurns - 1
                    }
                }
            }
            verify(false, listOf("Completed", "Partially"))
            BattleFixtures.native(initial) {
                when (type) {
                    "Scientific" -> {
                        // 尚未接入组装命令：构造原生组装计数边界，由真实里程碑和 checkForVictory 判定。
                        val parts = initial.ruleset.victories[type]!!.requiredSpaceshipParts
                        parts.dropLast(1).forEach { player.victoryManager.currentsSpaceshipParts.add(it, 1) }
                        verify(false, listOf("Completed", "Partially"))
                        player.victoryManager.currentsSpaceshipParts.add(parts.last(), 1)
                    }
                    "Cultural" -> player.cities.first().cityConstructions.addBuilding("Utopia Project")
                    "Time" -> initial.turns++
                }
            }
            verify(true, listOf("Completed", "Completed"))
        }
    }
    @Test fun allRecordedTypesAndOutcomesRetainOriginalWinnerAndTurn() {
        for (rules in BaseRuleset.entries) for (type in listOf("Scientific", "Cultural", "Domination", "Diplomatic", "Time", "missing-rule")) {
            for (wins in listOf(true, false)) {
                val (session, expected) = scenario(recorded(wins, type, rules))
                val data = options(session)
                assertEquals(if (wins) "won" else "lost", data.text("outcome"))
                assertEquals(type, data["recorded"]!!.jsonObject.text("type"))
                assertEquals(expected.turns, data["recorded"]!!.jsonObject.integer("turn"))
                assertTrue(data.text("description").isNotEmpty())
                session.game!!.turns += 30
                run(session, "load", "path" to export(session.game!!, "first-record").absolutePath)
                assertEquals(expected.turns, options(session)["recorded"]!!.jsonObject.integer("turn"))
                assertEquals(expected.victoryData!!.winningCiv, session.game!!.victoryData!!.winningCiv)
            }
        }
        val (session, _) = scenario(defeated())
        assertEquals("defeated", options(session).text("outcome"))
        assertEquals(JsonNull, options(session)["recorded"])
        session.game!!.victoryData = VictoryData(enemy(session.game!!), "Domination", 2)
        assertEquals("lost", options(session).text("outcome"))
    }
    @Test fun continuationIsIdempotentAndSurvivesIndependentReloadAndNativeTurn() {
        for (initial in listOf(recorded(true), recorded(false), defeated())) {
            val source = export(initial, "original")
            val original = source.readText()
            val session = load(source)
            var expected = copy(initial).also { screenUpdate(it) }
            val beforeTurn = expected.turns
            val command = body(session)
            val revision = session.revision
            val response = session.handle(command)
            assertTrue(response.toString(), response.boolean("ok"))
            expected.oneMoreTurnMode = true
            assertGameplayEquals("继续仅设置标志", expected, session.game!!)
            assertEquals(beforeTurn, session.game!!.turns)
            assertEquals(response, session.handle(command))
            assertEquals(revision + 1, session.revision)
            reject(session, JsonObject(command + ("extra" to JsonPrimitive(1))), "REQUEST_REUSED")
            reject(session, request(session, "victoryContinue", "resultToken" to "x"), "NO_VICTORY_DECISION")
            val saved = run(session, "save", "name" to "victory-roundtrip").text("savedPath")
            run(session, "load", "path" to saved)
            expected = copy(expected)
            assertGameplayEquals("继续后独立重载", expected, session.game!!)
            assertFalse(options(session).boolean("requiresAcknowledgement"))
            run(session, "nextTurn")
            expected = apply(expected, "nextTurn")
            assertGameplayEquals("继续后原生 AI 回合", expected, session.game!!)
            assertEquals(original, source.readText())
        }
    }
    @Test fun tokensBindIdentityRevisionAndResultAndRejectStrictArguments() {
        val foreign = body(scenario(recorded(true)).first).text("resultToken")
        for (change in listOf("forged", "foreign", "revision", "gameId", "winner", "turn", "defeated")) {
            val (session, _) = scenario(recorded(true))
            val command = body(session)
            when (change) {
                "revision" -> run(session, "save", "name" to "victory-token")
                "gameId" -> session.game!!.gameId = "other"
                "winner" -> session.game!!.victoryData = VictoryData(enemy(session.game!!), "Time", 1)
                "turn" -> session.game!!.victoryData = VictoryData(session.game!!.currentPlayerCiv, "Scientific", 20)
                "defeated" -> session.game!!.currentPlayerCiv.cities = emptyList()
            }
            if (change == "revision") reject(session, command, "STALE_STATE")
            reject(session, request(session, "victoryContinue", "resultToken" to when (change) {
                "forged" -> "forged"; "foreign" -> foreign; else -> command.text("resultToken")
            }), "VICTORY_RESULT")
        }
        val (session, _) = scenario(recorded(true))
        for (token in listOf(JsonNull, JsonPrimitive(false), JsonPrimitive(1), JsonPrimitive("")))
            reject(session, JsonObject(body(session) + ("resultToken" to token)), "INVALID_ARGUMENT")
        reject(session, JsonObject(body(session) - "resultToken"), "INVALID_ARGUMENT")
        reject(session, JsonObject(body(session) + ("player" to JsonPrimitive("Greece"))), "INVALID_ARGUMENT")
        reject(session, JsonObject(body(session) + ("session" to JsonPrimitive("other"))), "SESSION")
    }
    @Test fun everyNonExemptEffectIsGatedBeforePreparation() {
        for (initial in listOf(recorded(true), defeated())) {
            val (session, _) = scenario(initial)
            for ((name, command) in session.catalog()) if (command is EffectCommand && !command.allowedDuringVictory)
                reject(session, request(session, name), "PENDING_DECISION")
            assertTrue(options(session).boolean("requiresAcknowledgement"))
            run(session, "save", "name" to "victory-gated")
            run(session, "load", "path" to export(VictoryFixtures.game(), "ongoing").absolutePath)
            assertEquals("ongoing", options(session).text("outcome"))
        }
    }
    @Test fun defeatedPlayerCanAcknowledgePublicVoteBeforeContinuation() {
        val initial = defeated()
        initial.currentPlayerCiv.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 0)
        val (session, expected) = scenario(initial)
        assertEquals(listOf("voteResult", "victory"), PlayerSnapshot(session.game!!).pending().take(2).map { it.text("kind") })
        reject(session, request(session, "victoryContinue", "resultToken" to "x"), "PENDING_DECISION")
        assertFalse(run(session, "diplomaticVoteOptions")["data"]!!.jsonObject.boolean("canAbstain"))
        val vote = run(session, "diplomaticVoteOptions")["data"]!!.jsonObject["decision"]!!.jsonObject
        run(session, "diplomaticVoteAcknowledge", "resultToken" to vote.text("token"))
        DiplomaticVoteFixtures.acknowledge(expected)
        assertNull(session.game!!.victoryData)
        assertGameplayEquals("被消灭玩家确认公开结果", expected, session.game!!)
        run(session, "victoryContinue", "resultToken" to body(session).text("resultToken"))
        expected.oneMoreTurnMode = true
        assertGameplayEquals("被消灭玩家继续", expected, session.game!!)
    }
    @Test fun realCapitalCaptureTriggersVictoryOnlyAfterDisposition() {
        val (session, initial) = scenario(conquest())
        var expected = initial
        val cityId = enemy(expected).cities.first().id
        val commands = listOf("attack" to dto("unitId" to unit(expected, "Warrior").id, "x" to -6, "y" to 0),
            "cityDecision" to dto("cityId" to cityId, "choice" to "puppet"))
        for ((action, params) in commands) {
            expected = apply(expected, action, params)
            val response = session.handle(JsonObject(request(session, action) + params))
            assertTrue(response.toString(), response.boolean("ok"))
            assertGameplayEquals("原生攻城 $action", expected, session.game!!)
            if (action == "attack") assertNull(session.game!!.victoryData)
        }
        assertEquals("Domination", session.game!!.victoryData!!.victoryType)
        assertEquals("won", options(session).text("outcome"))
        run(session, "victoryContinue", "resultToken" to body(session).text("resultToken"))
        expected.oneMoreTurnMode = true
        assertGameplayEquals("征服后继续", expected, session.game!!)
    }
    @Test fun continuationPreservesOtherPendingDecisions() {
        val initial = recorded(true)
        initial.currentPlayerCiv.greatPeople.freeGreatPeople = 1
        initial.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.CityConquered, enemy(initial).cities.first().id))
        DiplomaticVoteFixtures.native(initial) { DiplomacyFixtures.incoming(initial) }
        val (session, expected) = scenario(initial)
        val before = PlayerSnapshot(session.game!!).pending().filter { it.text("kind") != "victory" }
        run(session, "victoryContinue", "resultToken" to body(session).text("resultToken"))
        expected.oneMoreTurnMode = true
        assertGameplayEquals("继续不消费其他事项", expected, session.game!!)
        assertEquals(before, PlayerSnapshot(session.game!!).pending())
        assertTrue(before.any { it.text("kind") == "cityCapture" })
        assertTrue(before.any { it.text("kind") == "greatPerson" })
    }
    @Test fun queriesSaveAndInvalidWritesNeverDiscoverUnrecordedVictory() {
        val (session, _) = scenario(VictoryFixtures.game())
        session.game!!.gameParameters.maxTurns = 0
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        assertEquals("ongoing", options(session).text("outcome"))
        reject(session, request(session, "victoryContinue", "resultToken" to "x"), "NO_VICTORY_DECISION")
        run(session, "save", "name" to "victory-readonly")
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        val expected = copy(session.game!!).also { screenUpdate(it) }
        assertNotNull(expected.victoryData)
        run(session, "load", "path" to export(session.game!!, "unrecorded").absolutePath)
        assertGameplayEquals("读档事务检查原生胜利", expected, session.game!!)
    }
    @Test fun hiddenForeignProgressDoesNotChangeOwnProjectionOrTicket() {
        val (session, _) = scenario(recorded(true))
        val before = options(session)
        val enemy = enemy(session.game!!)
        enemy.victoryManager.currentsSpaceshipParts.add("SS Booster", 3)
        enemy.tech.techsResearched.remove("Pottery")
        enemy.policies.storedCulture += 400
        assertEquals(before, options(session))
    }
    @Test fun unsupportedRecordKeepsLightweightSnapshotAvailable() {
        val (session, _) = scenario(recorded(true))
        session.game!!.victoryData = VictoryData("missing-civilization", "Time", 3)
        run(session, "snapshot")
        val pending = PlayerSnapshot(session.game!!).pending().first()
        assertFalse(pending.boolean("supported"))
        reject(session, request(session, "victoryOptions"), "UNSUPPORTED")
        reject(session, request(session, "victoryContinue", "resultToken" to "x"), "UNSUPPORTED")
    }
    @Test fun exportIndependentNativeSmokeScenarios() { emit() }
}
