package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.file
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CityCaptureCommandsTest {
    private fun target(game: GameInfo) = game.getCities().first { it.name == "陆战测试城" }
    private fun capture(expected: GameInfo, session: GameSession) {
        native(expected) {
            val unit = unit(expected, "Warrior")
            val attack = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()).first { it.tileToAttack == target(expected).getCenterTile() }
            assertTrue(Battle.movePreparingAttack(MapUnitCombatant(unit), attack, false))
            Battle.attackOrNuke(MapUnitCombatant(unit), attack)
        }
        run(session, "attack", "unitId" to unit(session.game!!, "Warrior").id, "x" to 1, "y" to 0)
        assertGameplayEquals("攻陷城市", expected, session.game!!)
    }

    @Test fun annexPuppetRazeAndLiberateMatchNativeAndAreIdempotent() {
        for (choice in listOf("annex", "puppet", "raze", "liberate")) {
            val source = file("city-$choice") { game ->
                if (choice == "liberate") {
                    val founder = Civilization(game.ruleset.nations["Egypt"]!!)
                    game.civilizations.add(founder)
                    game.setTransients()
                    game.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(founder)
                    enemy(game).diplomacyFunctions.makeCivilizationsMeet(founder)
                    target(game).foundingCivObject = founder
                    game.civilizations.forEach { it.popupAlerts.clear() }
                }
            }
            val session = load(source)
            val expected = UncivFiles.gameInfoFromString(source.readText())
            capture(expected, session)
            val city = target(session.game!!)
            assertEquals(enemy(session.game!!), city.civ)
            assertTrue(city.hasJustBeenConquered)
            assertFalse(city.isBeingRazed)
            val pending = PlayerSnapshot(session.game!!).pending().first { it.text("kind") == "cityCapture" }
            assertEquals(city.id, pending.text("target"))
            assertTrue(pending["choices"]!!.jsonArray.any { it.jsonObject.text("id") == choice && it.jsonObject["enabled"]!!.jsonPrimitive.boolean })
            for (game in listOf(expected, session.game!!)) {
                game.currentPlayerCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(AlertType.TechResearched, "unrelated-test-alert"))
            }
            val decision = request(session, "cityDecision", "cityId" to city.id, "choice" to choice)
            native(expected) {
                val c = target(expected)
                val player = expected.currentPlayerCiv
                when (choice) {
                    "annex" -> { c.puppetCity(player); c.annexCity() }
                    "puppet" -> c.puppetCity(player)
                    "raze" -> { c.puppetCity(player); c.annexCity(); c.isBeingRazed = true }
                    "liberate" -> c.liberateCity(player)
                }
                player.popupAlerts.removeAt(0)
            }
            val result = session.handle(decision)
            assertTrue(result.toString(), result["ok"]!!.jsonPrimitive.boolean)
            assertGameplayEquals(choice, expected, session.game!!)
            assertEquals(result, session.handle(decision))
            assertGameplayEquals("重复 $choice", expected, session.game!!)
            assertFalse(city.hasJustBeenConquered)
            assertFalse(session.game!!.currentPlayerCiv.popupAlerts.any { it.type == AlertType.CityConquered })
        }
    }

    @Test fun pendingCaptureSurvivesSaveReloadAndCannotBeSkipped() {
        val source = file()
        val session = load(source)
        val expected = UncivFiles.gameInfoFromString(source.readText())
        capture(expected, session)
        val id = target(session.game!!).id
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        run(session, "combatPreview", "unitId" to unit(session.game!!, "Archer").id, "x" to 0, "y" to 2)
        run(session, "unitOptions", "unitId" to unit(session.game!!, "Archer").id)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        for ((command, params) in listOf(
            "acknowledge" to emptyArray(), "nextTurn" to emptyArray(),
            "attack" to arrayOf("unitId" to unit(session.game!!, "Archer").id, "x" to 0, "y" to 2),
            "cityDecision" to arrayOf("cityId" to "forged", "choice" to "annex"),
            "cityDecision" to arrayOf("cityId" to id, "choice" to "liberate")
        )) {
            val response = session.handle(request(session, command, *params))
            assertFalse(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
            assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        }
        val saved = run(session, "save", "name" to "pending-capture").text("savedPath")
        val expectedReload = UncivFiles.gameInfoFromString(java.io.File(saved).readText())
        run(session, "load", "path" to saved)
        assertTrue(target(session.game!!).hasJustBeenConquered)
        assertTrue(PlayerSnapshot(session.game!!).pending().any { it.text("kind") == "cityCapture" })
        assertGameplayEquals("未决城市重载", expectedReload, session.game!!)
        native(expectedReload) { target(expectedReload).puppetCity(expectedReload.currentPlayerCiv); expectedReload.currentPlayerCiv.popupAlerts.removeAt(0) }
        run(session, "cityDecision", "cityId" to id, "choice" to "puppet")
        assertGameplayEquals("重载后处置", expectedReload, session.game!!)
    }

    @Test fun prohibitedRazingAndAnnexingAreRejectedBeforeMutation() {
        for (restriction in listOf("capital", "holy", "noRazing", "noAnnex")) {
            val session = load(file())
            val game = session.game!!
            run(session, "attack", "unitId" to unit(game, "Warrior").id, "x" to 1, "y" to 0)
            val city = target(game)
            when (restriction) {
                "capital" -> city.isOriginalCapital = true
                "holy" -> city.religion.religionThisIsTheHolyCityOf = "Test religion"
                "noRazing" -> game.gameParameters.noCityRazing = true
                "noAnnex" -> game.currentPlayerCiv.nation = Nation().apply { name = "Rome"; uniques.add(UniqueType.MayNotAnnexCities.text) }
            }
            val choice = if (restriction == "noAnnex") "annex" else "raze"
            val before = UncivFiles.gameInfoToString(game, false)
            val response = session.handle(request(session, "cityDecision", "cityId" to city.id, "choice" to choice))
            assertEquals(response.toString(), "CITY_DECISION", response["error"]!!.jsonObject.text("code"))
            assertSame(game, session.game)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
            if (restriction == "noAnnex") {
                run(session, "cityDecision", "cityId" to city.id, "choice" to "raze")
                assertTrue(city.isPuppet && city.isBeingRazed)
            }
        }
    }

    @Test fun recoveredCapitalAndOneCityChallengeKeepAutomaticResults() {
        for (occ in listOf(false, true)) {
            val source = file("city-auto") { game ->
                if (occ) game.gameParameters.oneCityChallenge = true
                else target(game).apply { isOriginalCapital = true; foundingCivObject = game.currentPlayerCiv }
            }
            val session = load(source)
            val expected = UncivFiles.gameInfoFromString(source.readText())
            capture(expected, session)
            assertFalse(session.game!!.currentPlayerCiv.popupAlerts.any { it.type == AlertType.CityConquered })
            if (occ) assertFalse(session.game!!.getCities().any { it.name == "陆战测试城" })
            else { assertEquals(session.game!!.currentPlayerCiv, target(session.game!!).civ); assertFalse(target(session.game!!).isPuppet) }
        }
    }

    @Test fun aiConquestStillAutomaticallyDisposesCity() {
        val game = BattleFixtures.game()
        native(game) {
            val city = game.currentPlayerCiv.cities.first()
            city.health = 1
            val ai = enemy(game)
            val attacker = add(game, "Warrior", -5, 0, ai)
            Battle.attack(MapUnitCombatant(attacker), com.unciv.logic.battle.CityCombatant(city))
            assertEquals(ai, city.civ)
            assertFalse(ai.popupAlerts.any { it.type == AlertType.CityConquered })
        }
    }

    /** 占城处置为傀儡后，后续吞并与原生 City.annexCity 同构：包括 puppet 取消、人口重分配、统计刷新。 */
    @Test fun annexPuppetAfterPuppetMatchesNativeAnnexCity() {
        val source = file("city-annex-puppet")
        val session = load(source)
        val expected = UncivFiles.gameInfoFromString(source.readText())
        capture(expected, session)
        val sessionId = target(session.game!!).id
        val expectedId = target(expected).id
        run(session, "cityDecision", "cityId" to sessionId, "choice" to "puppet")
        native(expected) { target(expected).puppetCity(expected.currentPlayerCiv); expected.currentPlayerCiv.popupAlerts.removeAt(0) }
        assertGameplayEquals("先占为傀儡", expected, session.game!!)
        assertTrue(target(session.game!!).isPuppet)
        run(session, "annexPuppet", "cityId" to sessionId)
        native(expected) { target(expected).annexCity() }
        assertGameplayEquals("傀儡后续吞并应与原生 annexCity 一致", expected, session.game!!)
        assertFalse(target(session.game!!).isPuppet)
        val rejected = session.handle(request(session, "annexPuppet", "cityId" to sessionId))
        assertEquals("CITY_STATUS", rejected["error"]!!.jsonObject.text("code"))
        assertGameplayEquals("重复吞并无副作用", expected, session.game!!)
        assertEquals(expectedId, sessionId)
    }

    /** 占城处置为焚城后，停止焚城与原生 trySetRazing(false) 同构：仅翻转标志，不重新处理人口／建筑。 */
    @Test fun stopRazingAfterRazeMatchesNativeFlagFlip() {
        val source = file("city-stop-raze")
        val session = load(source)
        val expected = UncivFiles.gameInfoFromString(source.readText())
        capture(expected, session)
        val sessionId = target(session.game!!).id
        run(session, "cityDecision", "cityId" to sessionId, "choice" to "raze")
        native(expected) {
            val c = target(expected); val p = expected.currentPlayerCiv
            c.puppetCity(p); c.annexCity(); c.isBeingRazed = true
            p.popupAlerts.removeAt(0)
        }
        assertGameplayEquals("先焚城", expected, session.game!!)
        assertTrue(target(session.game!!).isBeingRazed)
        run(session, "stopRazing", "cityId" to sessionId)
        native(expected) { target(expected).isBeingRazed = false }
        assertGameplayEquals("停止焚城与原生标志翻转一致", expected, session.game!!)
        assertFalse(target(session.game!!).isBeingRazed)
        val rejected = session.handle(request(session, "stopRazing", "cityId" to sessionId))
        assertEquals("CITY_STATUS", rejected["error"]!!.jsonObject.text("code"))
    }

    /** 非傀儡不能吞并，非焚毁中不能停止；拒绝不得产生写入与回滚重建。 */
    @Test fun annexPuppetAndStopRazingRejectOutsideApplicableState() {
        val session = load(file())
        val game = session.game!!
        run(session, "attack", "unitId" to unit(game, "Warrior").id, "x" to 1, "y" to 0)
        run(session, "cityDecision", "cityId" to target(game).id, "choice" to "puppet")
        val own = game.currentPlayerCiv.cities.first { it.location.x == -6 && it.location.y == 0 } // 本方首都，非傀儡
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val r1 = session.handle(request(session, "annexPuppet", "cityId" to own.id))
        assertEquals("CITY_STATUS", r1["error"]!!.jsonObject.text("code"))
        val r2 = session.handle(request(session, "stopRazing", "cityId" to own.id))
        assertEquals("CITY_STATUS", r2["error"]!!.jsonObject.text("code"))
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(game, false))
        assertEquals(revision, session.revision)
        val r3 = session.handle(request(session, "annexPuppet", "cityId" to "forged"))
        assertEquals("NOT_OWNED", r3["error"]!!.jsonObject.text("code"))
    }
}
