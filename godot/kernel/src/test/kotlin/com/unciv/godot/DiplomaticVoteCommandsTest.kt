package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.DiplomaticVoteFixtures.acknowledge
import com.unciv.godot.DiplomaticVoteFixtures.cast
import com.unciv.godot.DiplomaticVoteFixtures.copy
import com.unciv.godot.DiplomaticVoteFixtures.export
import com.unciv.godot.DiplomaticVoteFixtures.native
import com.unciv.godot.DiplomaticVoteFixtures.other
import com.unciv.godot.DiplomaticVoteFixtures.results
import com.unciv.godot.DiplomaticVoteFixtures.voting
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.translations.tr
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

internal class DiplomaticVoteCommandsTest {
    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) = report(description, e)
        override fun succeeded(description: Description) = report(description, null)
        private fun report(description: Description, error: Throwable?) {
            val file = File(DiplomaticVoteFixtures.runDir, "${description.methodName}.json")
            file.writeText(dto("runId" to DiplomaticVoteFixtures.runId, "test" to description.methodName,
                "ok" to (error == null), "error" to error?.stackTraceToString()).toString())
            println("外交投票测试归档：${file.absolutePath}")
        }
    }
    private fun firstDifference(a: JsonElement?, b: JsonElement?, path: String = "state"): String? {
        if (a == b) return null
        if (a is JsonObject && b is JsonObject) {
            for (key in a.keys + b.keys) firstDifference(a[key], b[key], "$path.$key")?.let { return it }
        } else if (a is JsonArray && b is JsonArray && a.size == b.size) {
            for (i in a.indices) firstDifference(a[i], b[i], "$path[$i]")?.let { return it }
        }
        return "$path: $a != $b"
    }
    private fun scenario(game: GameInfo): Pair<GameSession, GameInfo> {
        val file = export(game, "diplomatic-vote-test")
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }
    private fun options(session: GameSession): JsonObject {
        val current = session.game!!
        val text = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val data = run(session, "diplomaticVoteOptions")["data"]!!.jsonObject
        assertEquals(data, run(session, "diplomaticVoteOptions")["data"])
        run(session, "snapshot")
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(text, UncivFiles.gameInfoToString(current, false))
        return data
    }
    private fun castRequest(session: GameSession, target: String?): JsonObject {
        val params = mutableListOf<Pair<String, Any?>>("choice" to if (target == null) "abstain" else "civilization",
            "decisionToken" to options(session)["decision"]!!.jsonObject.text("token"))
        if (target != null) params.add("civId" to target)
        return request(session, "diplomaticVoteCast", *params.toTypedArray())
    }
    private fun ackRequest(session: GameSession) = request(session, "diplomaticVoteAcknowledge",
        "resultToken" to options(session)["decision"]!!.jsonObject.text("token"))
    private fun reject(session: GameSession, body: JsonObject, code: String) {
        val current = session.game!!
        val text = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val response = session.handle(body)
        assertEquals(response.toString(), code, response["error"]?.jsonObject?.text("code"))
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(text, UncivFiles.gameInfoToString(current, false))
    }
    private fun commit(session: GameSession, expected: GameInfo, body: JsonObject) {
        val current = session.game!!
        val revision = session.revision
        val response = session.handle(body)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertSame(current, session.game)
        assertEquals(revision + 1, session.revision)
        assertGameplayEquals(body.text("action"), expected, current)
        val after = UncivFiles.gameInfoToString(current, false)
        assertEquals(response, session.handle(body))
        assertEquals(after, UncivFiles.gameInfoToString(current, false))
        reject(session, JsonObject(body + ("extra" to JsonPrimitive(1))), "REQUEST_REUSED")
        reject(session, JsonObject(body + ("requestId" to JsonPrimitive("old-$revision"))), "STALE_STATE")
    }

    @Test fun exportNativeSmokeScenarios() { DiplomaticVoteFixtures.emit() }

    private fun replay(initial: GameInfo, sequence: DiplomaticVoteFixtures.Sequence) {
        val (session, _) = scenario(initial)
        val evidence = File(DiplomaticVoteFixtures.runDir, "gateway-${sequence.evidenceDir.name}").apply { mkdirs() }
        fun compare(step: String) {
            val current = session.game!!
            val raw = UncivFiles.gameInfoToString(current, false)
            val normalized = GameplayAssertions.gameplay(current)
            File(evidence, "$step-raw.json").writeText(raw)
            File(evidence, "$step-normalized.json").writeText(normalized.toString())
            val difference = firstDifference(sequence.states[step], normalized)
            File(evidence, "$step-diff.json").writeText(dto("step" to step, "difference" to difference,
                "native" to sequence.evidenceDir.absolutePath).toString())
            assertNull("网关长序列 $step，证据 $evidence", difference)
        }
        compare(sequence.states.keys.first())
        for (step in sequence.steps) {
            val action = step.text("action")
            var params = step["params"]!!.jsonObject
            if (action == "reload") {
                val path = run(session, "save", "name" to "${DiplomaticVoteFixtures.runId}-${sequence.name}").text("savedPath")
                run(session, "load", "path" to path)
            } else {
                when (action) {
                    "diplomaticVoteCast" -> params = JsonObject(params + ("decisionToken" to options(session)["decision"]!!.jsonObject["token"]!!))
                    "diplomaticVoteAcknowledge" -> params = dto("resultToken" to options(session)["decision"]!!.jsonObject["token"])
                    "diplomacyTradeDecision" -> params = JsonObject(params + ("tradeToken" to run(session, "diplomacyOptions")["data"]!!.jsonObject["incomingTrade"]!!.jsonObject["tradeToken"]!!))
                }
                val result = session.handle(JsonObject(request(session, action) + params))
                File(evidence, "${step.text("step")}-response.json").writeText(result.toString())
                assertTrue(result.toString(), result.boolean("ok"))
            }
            compare(step.text("step"))
        }
    }
    @Test fun gatewayNaturalCyclesAndWinsMatchEveryNativePersistentField() {
        for (rules in BaseRuleset.entries) {
            val initial = DiplomaticVoteFixtures.natural(rules)
            replay(initial, DiplomaticVoteFixtures.naturalSequence(initial, "natural"))
            val abstain = voting(rules, meet = false)
            replay(abstain, DiplomaticVoteFixtures.abstainSequence(abstain))
            for (playerWins in listOf(true, false)) {
                val winning = DiplomaticVoteFixtures.winning(playerWins, rules)
                replay(winning, DiplomaticVoteFixtures.winningSequence(winning, playerWins))
            }
        }
    }

    @Test fun nativeQueriesAndFlagsWorkWithoutGraphics() {
        for (rules in BaseRuleset.entries) {
            val game = copy(voting(rules))
            native(game) {
                val player = game.currentPlayerCiv
                assertTrue(player.mayVoteForDiplomaticVictory())
                assertEquals(listOf(other(game).civID), player.diplomacyFunctions.getKnownCivsSorted(false).map { it.civID }.toList())
                cast(game, null)
                assertTrue(game.diplomaticVictoryVotesCast.containsKey(player.civID))
                assertFalse(player.mayVoteForDiplomaticVictory())
            }
            val tied = copy(results(rules = rules))
            native(tied) {
                val breakdown = tied.currentPlayerCiv.victoryManager.getDiplomaticVictoryVoteBreakdown()
                assertEquals(1, breakdown.results[other(tied).civID])
                assertTrue(breakdown.winnerText.tr(hideIcons = true).contains("No world leader"))
                assertTrue(tied.currentPlayerCiv.shouldShowDiplomaticVotingResults())
                acknowledge(tied)
                assertEquals(-1, tied.currentPlayerCiv.flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name])
                assertFalse(tied.currentPlayerCiv.shouldShowDiplomaticVotingResults())
                tied.diplomaticVictoryVotesProcessed = true
                assertTrue(copy(tied).diplomaticVictoryVotesProcessed)
                assertFalse(tied.clone().diplomaticVictoryVotesProcessed)
            }
        }
    }

    @Test fun nativeNaturalCyclesAreRepeatable() {
        for (rules in BaseRuleset.entries) {
            val initial = copy(DiplomaticVoteFixtures.natural(rules))
            val first = DiplomaticVoteFixtures.naturalSequence(initial, "natural")
            val second = DiplomaticVoteFixtures.naturalSequence(initial, "natural")
            assertEquals(first.steps, second.steps)
            for ((step, expected) in first.states)
                assertNull("两份原生序列 $rules $step", firstDifference(expected, second.states[step]))
            assertGameplayEquals("原生完整双周期 $rules", first.game, second.game)
        }
    }

    @Test fun nativeEmptyCandidatesCanAbstain() {
        for (rules in BaseRuleset.entries) {
            val seq = DiplomaticVoteFixtures.Sequence(voting(rules, meet = false), "abstain")
            val player = seq.game.currentPlayerCiv
            assertTrue(player.mayVoteForDiplomaticVictory())
            assertTrue(player.diplomacyFunctions.getKnownCivsSorted(false).none())
            seq.step("diplomaticVoteCast", dto("choice" to "abstain"))
            seq.step("reload"); seq.untilResults(); seq.step("reload")
            assertTrue(seq.game.currentPlayerCiv.shouldShowDiplomaticVotingResults())
            seq.step("diplomaticVoteAcknowledge"); seq.step("reload")
        }
    }

    @Test fun nativeAiVotingRecordsBothWinnersRepeatably() {
        for (rules in BaseRuleset.entries) for (playerWins in listOf(true, false)) {
            val initial = copy(DiplomaticVoteFixtures.winning(playerWins, rules))
            val first = DiplomaticVoteFixtures.winningSequence(initial, playerWins)
            val second = DiplomaticVoteFixtures.winningSequence(initial, playerWins)
            assertEquals(first.steps, second.steps)
            for ((step, expected) in first.states)
                assertNull("原生获胜序列 $rules $playerWins $step", firstDifference(expected, second.states[step]))
            assertGameplayEquals("原生获胜后重载", first.game, second.game)
        }
    }

    @Test fun candidatesAndQueryAreNativeAndReadOnly() {
        for (rules in BaseRuleset.entries) {
            val (session, expected) = scenario(voting(rules))
            val data = options(session)
            assertEquals("vote", data.text("phase"))
            assertEquals(listOf(other(expected).civID), data["candidates"]!!.jsonArray.map { it.jsonObject.text("civId") })
            assertTrue(data.boolean("canAbstain"))
            assertEquals(JsonNull, data["results"])
            assertFalse(data["myVote"]!!.jsonObject.boolean("hasVoted"))
        }
    }
    @Test fun castUsesNativeEntryAndCommitsOnce() {
        val (session, expected) = scenario(voting())
        val body = castRequest(session, other(expected).civID)
        cast(expected, other(expected).civID)
        commit(session, expected, body)
        assertEquals("waitingResults", options(session).text("phase"))
        reject(session, request(session, "diplomaticVoteCast", "choice" to "abstain", "decisionToken" to "old"), "DIPLOMATIC_VOTE_ALREADY_CAST")
    }
    @Test fun abstainIsAnExplicitPersistentVote() {
        val (session, expected) = scenario(voting(meet = false))
        assertTrue(options(session)["candidates"]!!.jsonArray.isEmpty())
        val body = castRequest(session, null)
        cast(expected, null)
        commit(session, expected, body)
        assertEquals("abstain", options(session)["myVote"]!!.jsonObject.text("choice"))
        assertTrue(session.game!!.diplomaticVictoryVotesCast.containsKey(expected.currentPlayerCiv.civID))
    }
    @Test fun resultsAndAcknowledgeKeepAllOtherState() {
        val (session, expected) = scenario(results())
        assertEquals("results", options(session).text("phase"))
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        val body = ackRequest(session)
        acknowledge(expected)
        commit(session, expected, body)
        assertTrue(options(session)["results"]!!.jsonObject.boolean("acknowledged"))
        reject(session, request(session, "diplomaticVoteAcknowledge", "resultToken" to "old"), "NO_DIPLOMATIC_VOTE_RESULT")
    }
    @Test fun candidateFilteringAndWarUseNativeEligibility() {
        for (rules in BaseRuleset.entries) {
            val fixture = voting(rules)
            native(fixture) {
                val game = fixture
                DiplomacyFixtures.addCiv(game, "Geneva", 6, 0)
                DiplomacyFixtures.addCiv(game, "Egypt", 6, 6, false)
                val dead = DiplomacyFixtures.addCiv(game, "China", -6, -6)
                dead.cities = emptyList()
                for (name in listOf("Barbarians", "Spectator")) {
                    val civ = Civilization(game.ruleset.nations[name]!!)
                    civ.gameInfo = game; game.civilizations.add(civ); civ.setTransients()
                    game.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(civ)
                }
                game.currentPlayerCiv.diplomacy.remove("Egypt")
                game.getCivilization("Egypt").diplomacy.remove(game.currentPlayerCiv.civID)
                game.currentPlayerCiv.popupAlerts.clear()
                game.currentPlayerCiv.getDiplomacyManager(other(game))!!.diplomaticStatus = DiplomaticStatus.War
                other(game).getDiplomacyManager(game.currentPlayerCiv)!!.diplomaticStatus = DiplomaticStatus.War
            }
            val (session, expected) = scenario(fixture)
            for (game in listOf(session.game!!, expected)) {
                game.currentPlayerCiv.diplomacy.remove("Egypt")
                game.getCivilization("Egypt").diplomacy.remove(game.currentPlayerCiv.civID)
                game.currentPlayerCiv.popupAlerts.clear()
            }
            val game = session.game!!
            val data = options(session)
            assertEquals(listOf(other(game).civID), data["candidates"]!!.jsonArray.map { it.jsonObject.text("civId") })
            for (target in listOf(game.currentPlayerCiv.civID, "Geneva", "Egypt", "China", "Barbarians", "Spectator", "unknown-id"))
                reject(session, castRequest(session, target), "DIPLOMATIC_VOTE_CHOICE")
            val body = castRequest(session, other(game).civID)
            cast(expected, other(expected).civID)
            commit(session, expected, body)
        }
    }
    @Test fun noOtherMajorAndCitylessPlayerFollowNativeBoundaries() {
        val (only, _) = scenario(voting())
        other(only.game!!).cities = emptyList()
        assertFalse(only.game!!.currentPlayerCiv.mayVoteForDiplomaticVictory())
        assertEquals(JsonNull, options(only)["decision"])
        assertFalse(PlayerSnapshot(only.game!!).pending().any { it.text("kind") == "vote" })
        reject(only, request(only, "diplomaticVoteCast", "choice" to "abstain", "decisionToken" to "x"), "NO_DIPLOMATIC_VOTE")
        val (session, _) = scenario(voting())
        val game = session.game!!
        native(game) {
            game.currentPlayerCiv.cities = emptyList()
            game.currentPlayerCiv.hasEverOwnedOriginalCapital = false
            BattleFixtures.add(game, "Settler", 0, 0)
            game.gameParameters.victoryTypes.clear()
        }
        assertFalse(game.currentPlayerCiv.isDefeated())
        assertTrue(options(session).boolean("canAbstain"))
        val body = castRequest(session, null)
        val expected = copy(game)
        cast(expected, null)
        commit(session, expected, body)
    }
    @Test fun unpublishedVotesDoNotAffectPublicQueriesOrCastTokens() {
        val (session, _) = scenario(voting())
        val game = session.game!!
        val before = options(session)
        val snap = PlayerSnapshot(game).build()
        for (target in listOf(null, game.currentPlayerCiv.civID, other(game).civID)) {
            game.diplomaticVictoryVotesCast[other(game).civID] = target
            assertEquals(before, options(session))
            assertEquals(snap, PlayerSnapshot(game).build())
        }
        assertEquals(JsonNull, before["results"])
        assertEquals(JsonNull, before["victory"])
        assertEquals(JsonNull, before["myVote"]!!.jsonObject["choice"])
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        reject(session, request(session, "acknowledge"), "NO_ALERT")
    }
    @Test fun resultProjectionMatchesNativeIncludingHiddenAndDeadTargets() {
        for (rules in BaseRuleset.entries) for (kind in listOf("tie", "abstain", "empty", "win", "hidden", "dead")) {
            val source = results(if (kind == "dead") "tie" else kind, rules)
            if (kind == "dead") native(source) {
                val dead = DiplomacyFixtures.addCiv(source, "China", -6, -6)
                dead.cities = emptyList()
                source.diplomaticVictoryVotesCast[source.currentPlayerCiv.civID] = dead.civID
                source.currentPlayerCiv.popupAlerts.clear()
            }
            val (session, expected) = scenario(source)
            if (kind == "hidden") assertFalse(session.game!!.currentPlayerCiv.knows("Egypt"))
            val actual = options(session)["results"]!!.jsonObject
            val breakdown = native(expected) { expected.currentPlayerCiv.victoryManager.getDiplomaticVictoryVoteBreakdown() }
            assertEquals(dto(*breakdown.results.toSortedMap().map { it.key to it.value }.toTypedArray()), actual["totals"])
            assertEquals(breakdown.winnerText.tr(hideIcons = true), actual.text("winnerText"))
            assertEquals(expected.getCivsSorted().map { it.civID }.toSet(), actual["rows"]!!.jsonArray.map { it.jsonObject.text("civId") }.toSet())
            assertEquals(expected.currentPlayerCiv.civID, actual["rows"]!!.jsonArray.first().jsonObject.text("civId"))
            if (kind == "hidden") assertFalse(session.game!!.currentPlayerCiv.knows("Egypt"))
            if (kind == "dead") {
                assertEquals(1, actual["totals"]!!.jsonObject.integer("China"))
                assertEquals("abstain", actual["rows"]!!.jsonArray.first().jsonObject.text("choice"))
            }
            assertEquals(JsonNull, options(session)["victory"])
            val body = ackRequest(session)
            acknowledge(expected); commit(session, expected, body)
        }
    }
    @Test fun unitedNationsWeightUsesCurrentOwnerInBothRulesets() {
        for (rules in BaseRuleset.entries) for (hasUn in listOf(false, true)) {
            val fixture = results("tie", rules)
            if (hasUn) native(fixture) { fixture.currentPlayerCiv.cities.first().cityConstructions.addBuilding("United Nations") }
            val (session, _) = scenario(fixture)
            val result = options(session)["results"]!!.jsonObject
            assertEquals(if (hasUn) 2 else 1, result["totals"]!!.jsonObject.integer(other(session.game!!).civID))
            assertEquals(if (hasUn) 2 else 1, result["rows"]!!.jsonArray.first().jsonObject.integer("voteWeight"))
        }
    }
    @Test fun badResultReferencesStayUnsupportedAndSnapshotRemainsUsable() {
        for (badVoter in listOf(false, true)) {
            val (session, _) = scenario(results())
            session.game!!.diplomaticVictoryVotesCast[if (badVoter) "missing-voter" else session.game!!.currentPlayerCiv.civID] = "missing-target"
            val data = options(session)
            assertEquals("results", data.text("phase"))
            assertEquals(JsonNull, data["results"])
            assertEquals("UNSUPPORTED", data["decision"]!!.jsonObject.text("code"))
            val pending = PlayerSnapshot(session.game!!).pending().first()
            assertEquals("voteResult", pending.text("kind")); assertFalse(pending.boolean("supported"))
            reject(session, request(session, "diplomaticVoteAcknowledge", "resultToken" to "x"), "UNSUPPORTED")
            reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        }
    }
    @Test fun resultConfirmationPrecedesOtherDecisionsWithoutConsumingThem() {
        for (type in listOf(AlertType.CityConquered, AlertType.DeclarationOfFriendship, AlertType.GameHasBeenWon)) {
            val fixture = results()
            native(fixture) {
                DiplomacyFixtures.incoming(fixture)
                fixture.currentPlayerCiv.popupAlerts.add(PopupAlert(type,
                    if (type == AlertType.CityConquered) other(fixture).cities.first().id else other(fixture).civID))
                fixture.currentPlayerCiv.greatPeople.freeGreatPeople = 1
            }
            val (session, expected) = scenario(fixture)
            assertEquals("voteResult", PlayerSnapshot(session.game!!).pending().first().text("kind"))
            reject(session, request(session, "diplomaticVoteCast", "choice" to "abstain", "decisionToken" to "x"), "DIPLOMATIC_VOTE_RESULT_PENDING")
            val body = ackRequest(session)
            acknowledge(expected); commit(session, expected, body)
            assertTrue(PlayerSnapshot(session.game!!).pending().any { it.text("kind") == "greatPerson" })
            reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        }
        val (session, _) = scenario(voting())
        session.game!!.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, other(session.game!!).civID))
        val decision = options(session)["decision"]!!.jsonObject
        assertTrue(decision.boolean("supported")); assertFalse(decision.boolean("enabled"))
        reject(session, castRequest(session, null), "PENDING_DECISION")
        reject(session, request(session, "acknowledge"), "UNSUPPORTED")
    }
    @Test fun castTicketsBindFlagsCandidatesIdentityAndReload() {
        for (change in listOf("flag", "candidate", "turn", "gameId", "reload")) {
            val (session, _) = scenario(voting())
            val old = castRequest(session, null)
            val game = session.game!!
            when (change) {
                "flag" -> game.currentPlayerCiv.addFlag(CivFlags.ShouldResetDiplomaticVotes.name, 7)
                "candidate" -> native(game) {
                    DiplomacyFixtures.addCiv(game, "Egypt", 6, 6)
                    game.currentPlayerCiv.popupAlerts.clear()
                }
                "turn" -> game.turns++
                "gameId" -> game.gameId = "different-game"
                "reload" -> {
                    val path = run(session, "save", "name" to "dv-token-reload").text("savedPath")
                    run(session, "load", "path" to path)
                    reject(session, old, "STALE_STATE")
                }
            }
            reject(session, request(session, "diplomaticVoteCast", "choice" to "abstain", "decisionToken" to old.text("decisionToken")), "DIPLOMATIC_VOTE_DECISION")
        }
    }
    @Test fun resultTicketsBindVotesWeightsLivingCivsAndMode() {
        val castToken = castRequest(scenario(voting()).first, null).text("decisionToken")
        for (change in listOf("votes", "missing-null", "un", "alive", "mode")) {
            val (session, _) = scenario(results())
            val game = session.game!!
            val cityState = native(game) { DiplomacyFixtures.addCiv(game, "Geneva", 6, 0, false) }
            val old = ackRequest(session)
            when (change) {
                "votes" -> game.diplomaticVictoryVotesCast[other(game).civID] = null
                "missing-null" -> { game.diplomaticVictoryVotesCast.clear(); game.diplomaticVictoryVotesCast[other(game).civID] = null }
                "un" -> native(game) { game.currentPlayerCiv.cities.first().cityConstructions.addBuilding("United Nations") }
                "alive" -> cityState.cities = emptyList()
            }
            reject(session, request(session, "diplomaticVoteAcknowledge", "resultToken" to if (change == "mode") castToken else old.text("resultToken")), "DIPLOMATIC_VOTE_RESULT")
        }
        val resultToken = ackRequest(scenario(results()).first).text("resultToken")
        val (session, _) = scenario(voting())
        reject(session, request(session, "diplomaticVoteCast", "choice" to "abstain", "decisionToken" to resultToken), "DIPLOMATIC_VOTE_DECISION")
    }
    @Test fun acknowledgedResultsExpireOnlyWhenNativeFlagIsRemoved() {
        val (session, expected) = scenario(results())
        val body = ackRequest(session)
        acknowledge(expected); commit(session, expected, body)
        assertTrue(options(session)["results"]!!.jsonObject.boolean("acknowledged"))
        val path = run(session, "save", "name" to "dv-result-readable").text("savedPath")
        run(session, "load", "path" to path)
        assertTrue(options(session)["results"]!!.jsonObject.boolean("acknowledged"))
        val game = session.game!!
        game.currentPlayerCiv.removeFlag(CivFlags.ShowDiplomaticVotingResults.name)
        assertEquals(JsonNull, options(session)["results"])
        assertFalse(PlayerSnapshot(game).build()["diplomaticVote"]!!.jsonObject.boolean("resultsReadable"))
    }
    @Test fun unsupportedScopeRejectsQueriesAndBothWritesBeforeBackup() {
        for (result in listOf(false, true)) for (mode in listOf("online", "mods", "player", "multiple", "dead")) {
            val (session, _) = scenario(if (result) results() else voting())
            val body = if (result) ackRequest(session) else castRequest(session, null)
            val game = session.game!!
            when (mode) {
                "online" -> game.gameParameters.isOnlineMultiplayer = true
                "mods" -> game.gameParameters.mods.add("unsupported-test-mod")
                "player" -> game.currentPlayerCiv.playerType = PlayerType.AI
                "multiple" -> other(game).playerType = PlayerType.Human
                "dead" -> game.currentPlayerCiv.cities = emptyList()
            }
            val code = when (mode) { "mods" -> "UNSUPPORTED_MOD"; "dead" -> "UNSUPPORTED"; else -> "UNSUPPORTED_GAME" }
            reject(session, body, code)
            reject(session, request(session, "diplomaticVoteOptions"), code)
        }
    }
    @Test fun strictArgumentsRejectBeforeBackup() {
        val (session, _) = scenario(voting())
        val body = castRequest(session, null)
        reject(session, JsonObject(body + ("civId" to JsonNull)), "INVALID_ARGUMENT")
        reject(session, JsonObject(body + ("choice" to JsonPrimitive(true))), "INVALID_ARGUMENT")
        reject(session, JsonObject(body + ("playerId" to JsonPrimitive("Greece"))), "INVALID_ARGUMENT")
        reject(session, JsonObject(body - "decisionToken"), "INVALID_ARGUMENT")
        reject(session, JsonObject(body + ("decisionToken" to JsonPrimitive("old"))), "DIPLOMATIC_VOTE_DECISION")
    }
}
