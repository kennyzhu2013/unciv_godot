package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AssetDecisionCommandsTest {
    private fun decision(session: GameSession) = run(session, "assetDecisionOptions")["data"]!!.jsonObject["decision"]!!.jsonObject
    private fun choose(session: GameSession, choice: String) = request(session, "assetDecision",
        "decisionToken" to decision(session).text("token"), "choice" to choice)
    private fun civilian(session: GameSession) = session.game!!.tileMap[HexCoord.fromString(session.game!!.currentPlayerCiv.popupAlerts.first().value)].civilianUnit!!
    private fun city(session: GameSession) = session.game!!.getCities().first { it.id == session.game!!.currentPlayerCiv.popupAlerts.first().value }
    private fun rejected(session: GameSession, command: JsonObject, code: String) {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val result = session.handle(command)
        assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
        assertSame(game, session.game)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(game, false))
    }

    @Test fun allChoicesMatchIndependentNativeAndSurviveReload() {
        for ((name, choices) in AssetDecisionFixtures.cases) for (choice in choices) {
            val source = AssetDecisionFixtures.file(name)
            val session = load(source)
            val expected = UncivFiles.gameInfoFromString(source.readText())
            val game = session.game!!
            val before = UncivFiles.gameInfoToString(game, false)
            val revision = session.revision
            val options = decision(session)
            assertEquals(options, decision(session))
            assertSame(game, session.game)
            assertEquals(revision, session.revision)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
            assertTrue("$name $options", options.boolean("supported"))
            assertEquals(choices, options["choices"]!!.jsonArray.map { it.jsonObject.text("id") })
            val request = choose(session, choice)
            AssetDecisionFixtures.decide(expected, choice)
            val response = session.handle(request)
            assertTrue("$name/$choice $response", response.boolean("ok"))
            assertGameplayEquals("$name/$choice", expected, session.game!!)
            assertEquals(response, session.handle(request))
            assertGameplayEquals("重复 $name/$choice", expected, session.game!!)
            rejected(session, JsonObject(request + ("choice" to JsonPrimitive("other"))), "REQUEST_REUSED")
            rejected(session, JsonObject(request + ("requestId" to JsonPrimitive(UUID.randomUUID().toString()))), "STALE_STATE")
            val path = run(session, "save", "name" to "asset-result-$name-$choice").text("savedPath")
            val reload = UncivFiles.gameInfoFromString(File(path).readText())
            run(session, "load", "path" to path)
            assertGameplayEquals("结果重载 $name/$choice", reload, session.game!!)
        }
    }

    @Test fun pendingSaveReloadInvalidatesTicketsWithoutSkippingDecisions() {
        for (name in listOf("civilian-worker", "traded", "marriage")) {
            val session = load(AssetDecisionFixtures.file(name))
            val old = decision(session).text("token")
            rejected(session, request(session, "acknowledge"), "UNSUPPORTED")
            rejected(session, request(session, "nextTurn"), "PENDING_DECISION")
            rejected(session, request(session, "move", "unitId" to unit(session.game!!, "Warrior").id, "x" to 0, "y" to 1), "PENDING_DECISION")
            val path = run(session, "save", "name" to "asset-pending-$name").text("savedPath")
            rejected(session, request(session, "assetDecision", "decisionToken" to old, "choice" to "keep"), "ASSET_DECISION")
            val expected = UncivFiles.gameInfoFromString(File(path).readText())
            run(session, "load", "path" to path)
            assertGameplayEquals("待决重载 $name", expected, session.game!!)
            rejected(session, request(session, "assetDecision", "decisionToken" to old, "choice" to "keep"), "ASSET_DECISION")
            val choice = if (name == "marriage") "puppet" else "keep"
            AssetDecisionFixtures.decide(expected, choice)
            assertTrue(session.handle(choose(session, choice)).boolean("ok"))
            assertGameplayEquals("重载处置 $name", expected, session.game!!)
        }
    }

    @Test fun malformedArgumentsAndCrossSessionTicketsAreReadOnlyFailures() {
        val source = AssetDecisionFixtures.file("civilian-worker")
        val session = load(source)
        val good = choose(session, "keep")
        for (key in listOf("choice", "decisionToken")) {
            rejected(session, JsonObject(good - key), "INVALID_ARGUMENT")
            for (bad in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive(""), JsonPrimitive(" "), dto(), JsonArray(emptyList())))
                rejected(session, JsonObject(good + (key to bad)), "INVALID_ARGUMENT")
        }
        rejected(session, JsonObject(good + ("unitId" to JsonPrimitive(1))), "INVALID_ARGUMENT")
        rejected(session, request(session, "assetDecisionOptions", "choice" to "keep"), "INVALID_ARGUMENT")
        rejected(session, JsonObject(good + ("choice" to JsonPrimitive("annex"))), "INVALID_ARGUMENT")
        val other = load(source)
        rejected(session, JsonObject(good + ("decisionToken" to JsonPrimitive(decision(other).text("token")))), "ASSET_DECISION")
        rejected(session, JsonObject(good + ("session" to JsonPrimitive(other.sessionId))), "SESSION")
    }

    @Test fun headOnlyAndActualObjectIdentityAreBoundToTicket() {
        val session = load(AssetDecisionFixtures.file("civilian-worker"))
        val good = choose(session, "keep")
        val alerts = session.game!!.currentPlayerCiv.popupAlerts
        alerts.add(0, PopupAlert(AlertType.TechResearched, "Pottery"))
        assertEquals(JsonNull, run(session, "assetDecisionOptions")["data"]!!.jsonObject["decision"])
        rejected(session, good, "ASSET_DECISION")
        run(session, "acknowledge")
        val old = choose(session, "keep")
        val unit = civilian(session)
        val position = unit.currentTile.position
        val original = unit.originalOwner
        native(session.game!!) {
            unit.destroy()
            add(session.game!!, "Worker", position.x, position.y).originalOwner = original
        }
        rejected(session, old, "ASSET_DECISION")
        alerts.add(PopupAlert(AlertType.TechResearched, "Pottery"))
        assertTrue(session.handle(choose(session, "keep")).boolean("ok"))
        assertEquals(AlertType.TechResearched, alerts.single().type)
    }

    @Test fun malformedHiddenAndForeignSubjectsNeverLeakOrMutate() {
        for (kind in listOf("syntax", "outside", "hidden", "foreign", "original")) {
            val session = load(AssetDecisionFixtures.file("civilian-worker"))
            val game = session.game!!
            val player = game.currentPlayerCiv
            val unit = civilian(session)
            when (kind) {
                "syntax" -> { player.popupAlerts[0] = PopupAlert(AlertType.RecapturedCivilian, "SECRET") }
                "outside" -> { player.popupAlerts[0] = PopupAlert(AlertType.RecapturedCivilian, "(9999,9999)") }
                "hidden" -> player.viewableTiles = player.viewableTiles.filter { it != unit.currentTile }.toHashSet()
                "foreign" -> native(game) { unit.capturedBy(enemy(game)) }
                "original" -> unit.originalOwner = "SECRET"
            }
            val options = decision(session)
            assertFalse(options.boolean("supported"))
            assertEquals("", options.text("target"))
            assertEquals(JsonNull, options["originalOwner"])
            assertFalse(options.toString().contains("SECRET"))
            rejected(session, choose(session, "keep"), "ASSET_TARGET")
        }
        for (kind in listOf("missing", "foreign", "conquered", "founder")) {
            val session = load(AssetDecisionFixtures.file("traded"))
            val game = session.game!!
            when (kind) {
                "missing" -> game.currentPlayerCiv.popupAlerts[0] = PopupAlert(AlertType.CityTraded, "SECRET")
                "foreign" -> native(game) { city(session).moveToCiv(enemy(game)) }
                "conquered" -> city(session).hasJustBeenConquered = true
                "founder" -> city(session).foundingCivObject = null
            }
            assertFalse(decision(session).boolean("supported"))
            rejected(session, choose(session, "keep"), "ASSET_TARGET")
        }
    }

    @Test fun expiredOwnerIsOnlyDismissedAndReturnBonusIsSetNotAdded() {
        val session = load(AssetDecisionFixtures.file("civilian-worker"))
        val original = civilian(session).originalOwningCiv!!
        native(session.game!!) {
            original.cities.toList().forEach { it.destroyCity(overrideSafeties = true) }
            original.units.getCivUnits().toList().forEach { it.destroy() }
        }
        assertTrue(original.isDefeated())
        assertEquals(listOf("dismiss"), decision(session)["choices"]!!.jsonArray.map { it.jsonObject.text("id") })
        val unit = civilian(session)
        assertTrue(session.handle(choose(session, "dismiss")).boolean("ok"))
        assertTrue(session.game!!.currentPlayerCiv.units.getCivUnits().contains(unit))

        val bonus = load(AssetDecisionFixtures.file("civilian-worker"))
        val diplo = civilian(bonus).originalOwningCiv!!.getDiplomacyManager(bonus.game!!.currentPlayerCiv)!!
        diplo.setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 7f)
        assertTrue(bonus.handle(choose(bonus, "return")).boolean("ok"))
        assertEquals(20f, diplo.diplomaticModifiers[DiplomaticModifiers.ReturnedCapturedUnits.name]!!, 0f)
    }

    @Test fun warAndNoAnnexRestrictionsAndForeignFoundedMarriage() {
        val trade = load(AssetDecisionFixtures.file("traded"))
        native(trade.game!!) { trade.game!!.currentPlayerCiv.getDiplomacyManager(city(trade).foundingCivObject!!)!!.declareWar() }
        assertEquals(listOf("keep"), decision(trade)["choices"]!!.jsonArray.map { it.jsonObject.text("id") })
        rejected(trade, choose(trade, "liberate"), "INVALID_ARGUMENT")
        val marriage = load(AssetDecisionFixtures.file("marriage"))
        marriage.game!!.currentPlayerCiv.nation = Nation().apply { name = "Rome"; uniques.add(UniqueType.MayNotAnnexCities.text) }
        assertFalse(decision(marriage)["choices"]!!.jsonArray.first().jsonObject.boolean("enabled"))
        rejected(marriage, choose(marriage, "annex"), "ASSET_CHOICE")
        val gold = marriage.game!!.currentPlayerCiv.gold
        val units = marriage.game!!.currentPlayerCiv.units.getCivUnits().map { it.id }.toList()
        // 联姻城邦之前取得的他国城市保留建立者，不能把 founder=null 当成联姻凭证。
        city(marriage).foundingCivObject = enemy(marriage.game!!)
        assertTrue(marriage.handle(choose(marriage, "puppet")).boolean("ok"))
        assertEquals(gold, marriage.game!!.currentPlayerCiv.gold)
        assertEquals(units, marriage.game!!.currentPlayerCiv.units.getCivUnits().map { it.id }.toList())
    }

    @Test fun emitIndependentSmokeFixtures() = AssetDecisionFixtures.emit()
}
