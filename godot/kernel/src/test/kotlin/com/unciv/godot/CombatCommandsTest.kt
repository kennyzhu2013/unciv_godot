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
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CombatCommandsTest {
    private fun duel(name: String = "Warrior", distance: Int = 1, health: Int = 100): File = file("duel") { game ->
        game.civilizations.forEach { civ -> civ.units.getCivUnits().toList().forEach { it.destroy() } }
        add(game, name, -3, 0)
        if (name == "Catapult") add(game, "Scout", -4, 2)
        add(game, "Warrior", -3, distance, enemy(game)).health = health
    }

    /** 期望端直接调用 core，不经过战斗适配器或 DTO。 */
    private fun attackAndCompare(file: File, name: String, x: Int, y: Int, outcome: String = "attacked",
                                 configure: (GameInfo) -> Unit = {}): Pair<GameSession, JsonObject> {
        val session = load(file)
        val expected = UncivFiles.gameInfoFromString(file.readText())
        for (game in listOf(expected, session.game!!)) native(game) { configure(game) }
        val unit = unit(session.game!!, name)
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        val preview = run(session, "combatPreview", "unitId" to unit.id, "x" to x, "y" to y)
        run(session, "unitOptions", "unitId" to unit.id)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
        val originalUnit = unit(expected, name)
        val nativeDamage = native(expected) {
            val target = TargetHelper.getAttackableEnemies(originalUnit, originalUnit.movement.getDistanceToTiles())
                .first { it.tileToAttack.position == HexCoord(x, y) }
            val attacker = MapUnitCombatant(originalUnit)
            val defender = if (target.tileToAttack.isCityCenter())
                com.unciv.logic.battle.CityCombatant(target.tileToAttack.getCity()!!)
            else MapUnitCombatant(target.tileToAttack.militaryUnit!!)
            val extra = if (!defender.isCity() && originalUnit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.ExtraRangedAttack))
                com.unciv.logic.battle.BattleDamage.getExtraRangedAttackBonusDamage(attacker, defender, target.tileToAttackFrom)
            else 0 to 0
            for ((key, factor) in listOf("min" to 0f, "max" to 1f)) {
                val expectedDefender = com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(attacker, defender, target.tileToAttackFrom, factor) +
                    if (key == "min") extra.second else extra.first
                val expectedAttacker = com.unciv.logic.battle.BattleDamage.calculateDamageToAttacker(attacker, defender, target.tileToAttackFrom, factor)
                val data = preview["data"]!!.jsonObject
                assertEquals(expectedDefender.coerceIn(0, defender.getHealth()), data["damageToDefender"]!!.jsonObject.integer(key))
                assertEquals(expectedAttacker.coerceIn(0, attacker.getHealth()), data["damageToAttacker"]!!.jsonObject.integer(key))
            }
            if (Battle.movePreparingAttack(attacker, target, false))
                Battle.attackOrNuke(attacker, target)
            else Battle.DamageDealt.None
        }
        val request = request(session, "attack", "unitId" to unit.id, "x" to x, "y" to y)
        val response = session.handle(request)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertEquals(outcome, response["battleResult"]!!.jsonObject.text("outcome"))
        assertEquals(nativeDamage.attackerDealt, response["battleResult"]!!.jsonObject.integer("damageToDefender"))
        assertEquals(nativeDamage.defenderDealt, response["battleResult"]!!.jsonObject.integer("damageToAttacker"))
        assertGameplayEquals("攻击 $name", expected, session.game!!)
        val after = UncivFiles.gameInfoToString(session.game!!, false)
        assertEquals(response, session.handle(request))
        val reused = session.handle(JsonObject(request + ("x" to JsonPrimitive(x + 1))))
        assertEquals("REQUEST_REUSED", reused["error"]!!.jsonObject.text("code"))
        assertEquals(after, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision + 1, session.revision)
        return session to preview["data"]!!.jsonObject
    }

    @Test fun meleeDamageAndKillMatchNative() {
        for (health in listOf(100, 1)) {
            val (session, _) = attackAndCompare(duel(health = health), "Warrior", -3, 1)
            if (health == 100) assertTrue(unit(session.game!!, "Warrior").health < 100)
            assertEquals(1, unit(session.game!!, "Warrior").attacksThisTurn)
            if (health == 1) assertFalse(enemy(session.game!!).units.getCivUnits().any())
        }
    }

    @Test fun rangedHasNoRetaliationAndCannotCapture() {
        val (session, preview) = attackAndCompare(duel("Archer", 2), "Archer", -3, 2)
        assertEquals(100, unit(session.game!!, "Archer").health)
        assertEquals(0, preview["damageToAttacker"]!!.jsonObject.integer("max"))
        val (citySession, cityPreview) = attackAndCompare(file(), "Archer", 1, 0)
        assertFalse(cityPreview["canCaptureCity"]!!.jsonPrimitive.boolean)
        assertFalse(citySession.game!!.getCities().first { it.name == "陆战测试城" }.hasJustBeenConquered)
    }

    @Test fun movingMeleeAndSiegeSetupMatchNative() {
        attackAndCompare(duel(distance = 2), "Warrior", -3, 2)
        val (session, _) = attackAndCompare(duel("Catapult", 2), "Catapult", -3, 2)
        assertTrue(unit(session.game!!, "Catapult").isSetUpForSiege())
    }

    @Test fun previewAndIllegalRequestsLeaveExactStateUntouched() {
        val session = load(file())
        val game = session.game!!
        val warrior = unit(game, "Warrior")
        val enemies = PlayerSnapshot(game).build()["units"]!!.jsonArray.filter { !it.jsonObject["own"]!!.jsonPrimitive.boolean }
        assertTrue(enemies.isNotEmpty())
        for (enemy in enemies) for (field in listOf("movement", "action", "due", "attacksThisTurn"))
            assertEquals("敌方字段不能泄漏：$field", JsonNull, enemy.jsonObject[field])
        fun rejected(command: JsonObject, code: String) {
            val before = UncivFiles.gameInfoToString(game, false)
            val revision = session.revision
            val result = session.handle(command)
            assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
            assertEquals(revision, session.revision)
            assertSame("拒绝不应触发回滚重建", game, session.game)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
        }
        rejected(request(session, "attack", "unitId" to enemy(game).units.getCivUnits().first().id, "x" to 0, "y" to 0), "NOT_OWNED")
        rejected(request(session, "attack", "unitId" to warrior.id, "x" to 99999, "y" to 99999), "CANNOT_ATTACK")
        rejected(request(session, "attack", "unitId" to warrior.id, "x" to -6, "y" to 0), "CANNOT_ATTACK")
        rejected(request(session, "unitAction", "unitId" to warrior.id, "type" to "Disband"), "UNIT_ACTION")
        rejected(request(session, "cityDecision", "cityId" to game.currentPlayerCiv.cities.first().id, "choice" to "annex"), "CITY_DECISION")
        rejected(JsonObject(request(session, "attack", "unitId" to warrior.id, "x" to 1, "y" to 0) + ("revision" to JsonPrimitive(0))), "STALE_STATE")
        warrior.currentMovement = 0f
        rejected(request(session, "attack", "unitId" to warrior.id, "x" to 1, "y" to 0), "CANNOT_ATTACK")
        warrior.currentMovement = 2f
        warrior.attacksThisTurn = 1
        rejected(request(session, "attack", "unitId" to warrior.id, "x" to 1, "y" to 0), "CANNOT_ATTACK")
        warrior.attacksThisTurn = 0
        val tile = game.tileMap[1, 0]
        game.currentPlayerCiv.viewableTiles = game.currentPlayerCiv.viewableTiles - tile
        rejected(request(session, "combatPreview", "unitId" to warrior.id, "x" to 1, "y" to 0), "CANNOT_ATTACK")
        game.currentPlayerCiv.viewableTiles = game.currentPlayerCiv.viewableTiles + tile
        game.currentPlayerCiv.getDiplomacyManager(enemy(game))!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
        enemy(game).getDiplomacyManager(game.currentPlayerCiv)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
        rejected(request(session, "attack", "unitId" to warrior.id, "x" to 1, "y" to 0), "CANNOT_ATTACK")
    }

    @Test fun basicActionsAndFollowingTurnMatchNative() {
        val cases = listOf("skip" to UnitActionType.Skip, "fortify" to UnitActionType.Fortify,
            "fortifyUntilHealed" to UnitActionType.FortifyUntilHealed, "sleep" to UnitActionType.Sleep,
            "sleepUntilHealed" to UnitActionType.SleepUntilHealed, "setUp" to UnitActionType.SetUp)
        for ((id, type) in cases) {
            val name = when (id) { "sleep", "sleepUntilHealed" -> "Worker"; "setUp" -> "Catapult"; else -> "Warrior" }
            val source = duel(name).also { }
            val session = load(source)
            var expected = UncivFiles.gameInfoFromString(source.readText())
            for (game in listOf(expected, session.game!!)) unit(game, name).health = 70
            val own = unit(session.game!!, name)
            val options = run(session, "unitOptions", "unitId" to own.id)["data"]!!.jsonObject["actions"]!!.jsonArray
            assertTrue(options.toString(), options.any { it.jsonObject.text("id") == id && it.jsonObject["enabled"]!!.jsonPrimitive.boolean })
            native(expected) { UnitActions.getBasicActions(unit(expected, name)).first { it.type == type }.action!!.invoke() }
            run(session, "unitAction", "unitId" to own.id, "type" to id)
            assertGameplayEquals(id, expected, session.game!!)
            if (id == "skip") {
                native(expected) { UnitActions.getBasicActions(unit(expected, name)).first { it.type == type }.action!!.invoke() }
                run(session, "unitAction", "unitId" to own.id, "type" to id)
                assertTrue(own.due)
                assertGameplayEquals("取消跳过", expected, session.game!!)
            }
            // 场景已有生产和科研，只有开局消息可能需要确认。
            while (session.game!!.currentPlayerCiv.popupAlerts.isNotEmpty()) {
                native(expected) { expected.currentPlayerCiv.popupAlerts.removeAt(0) }
                run(session, "acknowledge")
            }
            expected = native(expected) { expected.clone().also { it.setTransients() } }
            native(expected) { expected.nextTurn() }
            run(session, "nextTurn")
            assertGameplayEquals("$id 跨回合", expected, session.game!!)
        }
    }

    @Test fun hiddenHillConsumesMovementWithoutAttackAndIsCommitted() {
        val (session, _) = attackAndCompare(duel(distance = 2), "Warrior", -3, 2, "movedOnly") { game ->
            val hill = game.tileMap[-3, 1]
            hill.baseTerrain = "Hill"
            hill.setTerrainTransients()
            hill.setExplored(game.currentPlayerCiv, false)
            game.currentPlayerCiv.viewableTiles = game.currentPlayerCiv.viewableTiles - hill
        }
        val warrior = unit(session.game!!, "Warrior")
        assertEquals(HexCoord(-3, 1), warrior.currentTile.position)
        assertEquals(0f, warrior.currentMovement)
        assertEquals(0, warrior.attacksThisTurn)
        assertEquals(100, enemy(session.game!!).units.getCivUnits().first().health)
    }

    @Test fun lineOfSightAndInvisibleDefendersDoNotLeakTargets() {
        for (invisible in listOf(false, true)) {
            val session = load(duel("Archer", 2))
            val game = session.game!!
            val attacker = unit(game, "Archer")
            val defender = enemy(game).units.getCivUnits().first()
            attacker.currentMovement = 0.5f
            if (invisible) {
                defender.statusMap["invisible-test"] = com.unciv.logic.map.mapunit.MapUnit.UnitStatus("invisible-test", 2).apply {
                    uniques = listOf(com.unciv.models.ruleset.unique.Unique("Invisible to others"))
                }
                defender.updateUniques()
            } else {
                game.tileMap[-3, 1].apply { baseTerrain = "Mountain"; setTerrainTransients() }
            }
            game.currentPlayerCiv.viewableTiles = game.currentPlayerCiv.viewableTiles + defender.currentTile
            val before = UncivFiles.gameInfoToString(game, false)
            val response = session.handle(request(session, "combatPreview", "unitId" to attacker.id, "x" to -3, "y" to 2))
            assertEquals(response.toString(), "CANNOT_ATTACK", response["error"]!!.jsonObject.text("code"))
            val options = run(session, "unitOptions", "unitId" to attacker.id)["data"]!!.jsonObject
            assertTrue(options["attackTargets"]!!.jsonArray.isEmpty())
            if (invisible) assertFalse(PlayerSnapshot(game).build()["units"]!!.jsonArray.any { it.jsonObject.integer("id") == defender.id })
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
        }
    }

    @Test fun cannotCaptureAndExtraRangedAttackUseOriginalBattleMath() {
        val noCapture = file("no-capture") { game ->
            unit(game, "Warrior").destroy()
            add(game, "Helicopter Gunship", 0, 0)
        }
        val (session, preview) = attackAndCompare(noCapture, "Helicopter Gunship", 1, 0)
        assertFalse(preview["canCaptureCity"]!!.jsonPrimitive.boolean)
        assertFalse(session.game!!.getCities().first { it.name == "陆战测试城" }.hasJustBeenConquered)
        attackAndCompare(duel(), "Warrior", -3, 1) { game ->
            val attacker = unit(game, "Warrior")
            attacker.statusMap["extra-ranged-test"] = com.unciv.logic.map.mapunit.MapUnit.UnitStatus("extra-ranged-test", 2).apply {
                uniques = listOf(com.unciv.models.ruleset.unique.Unique("Before engaging in combat performs an extra ranged attack with [50]% of melee combat strength"))
            }
            attacker.updateUniques()
            assertTrue(attacker.hasUnique(com.unciv.models.ruleset.unique.UniqueType.ExtraRangedAttack))
        }
    }

    @Test fun malformedRecapturedCivilianRemainsExplicitlyUnsupported() {
        val session = load(file())
        val game = session.game!!
        game.currentPlayerCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(com.unciv.logic.civilization.AlertType.RecapturedCivilian, "worker"))
        val before = UncivFiles.gameInfoToString(game, false)
        assertTrue(PlayerSnapshot(game).pending().any { it.text("kind") == "assetDecision" && !it["supported"]!!.jsonPrimitive.boolean })
        for (action in listOf("acknowledge", "nextTurn", "attack")) {
            val result = session.handle(request(session, action, "unitId" to unit(game, "Warrior").id, "x" to 1, "y" to 0))
            assertFalse(result["ok"]!!.jsonPrimitive.boolean)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
        }
        run(session, "save", "name" to "unsupported-civilian")
    }

    @Test fun generateGraphicalCombatFixture() {
        val fixture = file()
        val session = load(fixture)
        val preview = run(session, "combatPreview", "unitId" to unit(session.game!!, "Warrior").id, "x" to 1, "y" to 0)
        assertTrue(preview["data"]!!.jsonObject["canCaptureCity"]!!.jsonPrimitive.boolean)
    }
}
