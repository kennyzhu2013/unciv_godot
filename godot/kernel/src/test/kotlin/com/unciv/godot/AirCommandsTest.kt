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
import com.unciv.logic.battle.AirInterception
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
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

/**
 * 第 7 批 7A：空军驻扎、转场、空袭、空中扫荡的独立原生差分。
 * 期望端只调用 core 的 `UnitMovement.moveToTile`、`Battle.movePreparingAttack + attackOrNuke`、
 * `AirInterception.airSweep` 与 `UnitActions`(AirSweep)，不复用网关 DTO 自证。
 */
class AirCommandsTest {
    /** 空军只能停靠己方空位城市或运输单位，因此双方飞机都放在各自首都；夹具名按用例区分，供窗口 smoke 稳定加载。 */
    private fun airfield(fixture: String, unit: String = "Fighter", configure: (GameInfo) -> Unit = {}): File =
        file("air-$fixture") { game ->
            add(game, unit, -6, 0)
            configure(game)
        }

    @Test fun rebaseCandidatesAndMovementMatchNative() {
        val fixture = airfield("rebase") { game ->
            // 前线机场：航程内的己方空位城市，且不含任何运输单位。
            game.currentPlayerCiv.addCity(HexCoord(0, 4)).name = "前线机场"
        }
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val id = native(expected) { unit(expected, "Fighter").id }

        val options = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject
        val bases = options["airBases"]!!.jsonArray.map { it.jsonObject.integer("x") to it.jsonObject.integer("y") }
        assertTrue(bases.toString(), HexCoord(0, 4).let { it.x to it.y } in bases)
        assertFalse("转场候选不含当前地块", HexCoord(-6, 0).let { it.x to it.y } in bases)
        val air = options["air"]!!.jsonObject
        assertEquals(0, air.integer("attacksThisTurn"))
        assertEquals(1, air.integer("maxAttacks"))
        assertTrue(air["canRebase"]!!.jsonPrimitive.boolean)

        native(expected) { unit(expected, "Fighter").movement.moveToTile(expected.tileMap[0, 4]) }
        val result = run(session, "airRebase", "unitId" to id, "x" to 0, "y" to 4)
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        assertGameplayEquals("空军转场", expected, session.game!!)
        val moved = unit(session.game!!, "Fighter")
        assertEquals(HexCoord(0, 4), moved.currentTile.position)
        assertEquals(0f, moved.currentMovement)
    }

    @Test fun airStrikePreviewAndDamageMatchNative() {
        val fixture = airfield("strike", "Bomber")
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val id = native(expected) { unit(expected, "Bomber").id }

        // 目标为敌方陆军：空袭不占城、不移动，航程覆盖到 (0,2)。
        val preview = run(session, "combatPreview", "unitId" to id, "x" to 0, "y" to 2)["data"]!!.jsonObject
        assertEquals("combat", preview.text("kind"))
        assertEquals(HexCoord(-6, 0), HexCoord(preview["attackFrom"]!!.jsonObject.integer("x"),
            preview["attackFrom"]!!.jsonObject.integer("y")))
        assertFalse(preview["canCaptureCity"]!!.jsonPrimitive.boolean)

        val nativeDamage = native(expected) {
            val bomber = unit(expected, "Bomber")
            val target = TargetHelper.getAttackableEnemies(bomber, bomber.movement.getDistanceToTiles())
                .first { it.tileToAttack.position == HexCoord(0, 2) }
            val attacker = MapUnitCombatant(bomber)
            val defender: ICombatant = if (target.tileToAttack.isCityCenter())
                CityCombatant(target.tileToAttack.getCity()!!) else MapUnitCombatant(target.tileToAttack.militaryUnit!!)
            for ((key, factor) in listOf("min" to 0f, "max" to 1f)) {
                val expectedDefender = BattleDamage.calculateDamageToDefender(attacker, defender, target.tileToAttackFrom, factor)
                val expectedAttacker = BattleDamage.calculateDamageToAttacker(attacker, defender, target.tileToAttackFrom, factor)
                assertEquals(preview["damageToDefender"]!!.jsonObject.integer(key),
                    expectedDefender.coerceIn(0, defender.getHealth()))
                assertEquals(preview["damageToAttacker"]!!.jsonObject.integer(key),
                    expectedAttacker.coerceIn(0, attacker.getHealth()))
            }
            assertTrue(Battle.movePreparingAttack(attacker, target, false))
            Battle.attackOrNuke(attacker, target)
        }

        val response = session.handle(request(session, "attack", "unitId" to id, "x" to 0, "y" to 2))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("attacked", response["battleResult"]!!.jsonObject.text("outcome"))
        assertEquals(nativeDamage.attackerDealt, response["battleResult"]!!.jsonObject.integer("damageToDefender"))
        assertEquals(nativeDamage.defenderDealt, response["battleResult"]!!.jsonObject.integer("damageToAttacker"))
        assertGameplayEquals("空袭", expected, session.game!!)
        // 空袭后飞机仍在原基地，未移动，但行动力与攻击次数按原生结算。
        val bomber = unit(session.game!!, "Bomber")
        assertEquals(HexCoord(-6, 0), bomber.currentTile.position)
        assertEquals(1, bomber.attacksThisTurn)
        assertEquals(0f, bomber.currentMovement)
    }

    @Test fun airSweepPreparationAndInterceptionMatchNative() {
        val fixture = airfield("sweep") { game -> add(game, "Fighter", 6, 0, enemy(game)) }
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val id = native(expected) { unit(expected, "Fighter").id }

        // 未准备扫荡：扫荡动作可用但候选为空。
        val idle = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject
        val action = idle["actions"]!!.jsonArray.single { it.jsonObject.text("id") == "airSweep" }.jsonObject
        assertTrue(action["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(idle["sweepTargets"]!!.jsonArray.isEmpty())
        assertFalse(idle["air"]!!.jsonObject["sweep"]!!.jsonObject["active"]!!.jsonPrimitive.boolean)

        native(expected) {
            UnitActions.getUnitActions(unit(expected, "Fighter"), UnitActionType.AirSweep)
                .first { it.action != null }.action!!.invoke()
        }
        run(session, "unitAction", "unitId" to id, "type" to "airSweep")
        assertGameplayEquals("准备空中扫荡", expected, session.game!!)

        val armed = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject
        assertTrue(armed["air"]!!.jsonObject["sweep"]!!.jsonObject["active"]!!.jsonPrimitive.boolean)
        val targets = armed["sweepTargets"]!!.jsonArray.map {
            it.jsonObject.integer("x") to it.jsonObject.integer("y")
        }
        assertTrue(targets.toString(), HexCoord(0, 2).let { it.x to it.y } in targets)

        // 扫荡目标不含敌机，拦截由射程内的敌方战斗机原生触发。
        val preview = run(session, "combatPreview", "unitId" to id, "x" to 0, "y" to 2)["data"]!!.jsonObject
        assertEquals("airSweep", preview.text("kind"))
        assertTrue(preview["unknownOutcome"]!!.jsonPrimitive.boolean)
        assertEquals(0, preview["damageToDefender"]!!.jsonObject.integer("max"))

        native(expected) {
            AirInterception.airSweep(MapUnitCombatant(unit(expected, "Fighter")), expected.tileMap[0, 2])
        }
        val response = session.handle(request(session, "attack", "unitId" to id, "x" to 0, "y" to 2))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("swept", response["battleResult"]!!.jsonObject.text("outcome"))
        assertGameplayEquals("空中扫荡", expected, session.game!!)
    }

    @Test fun bomberHasNoSweepAndIllegalRequestsLeaveStateUntouched() {
        val fixture = airfield("illegal", "Bomber")
        val session = load(fixture)
        val game = session.game!!
        val bomber = unit(game, "Bomber")
        val options = run(session, "unitOptions", "unitId" to bomber.id)["data"]!!.jsonObject
        assertTrue("轰炸机无空中扫荡能力", options["actions"]!!.jsonArray.isEmpty())
        assertTrue(options["sweepTargets"]!!.jsonArray.isEmpty())

        fun rejected(command: JsonObject, code: String) {
            val before = UncivFiles.gameInfoToString(game, false)
            val revision = session.revision
            val result = session.handle(command)
            assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
            assertEquals(revision, session.revision)
            assertSame("拒绝不应触发回滚重建", game, session.game)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
        }
        // 目标没有己方空位城市或运输单位。
        rejected(request(session, "airRebase", "unitId" to bomber.id, "x" to 0, "y" to 2), "CANNOT_MOVE")
        // 同一地块。
        rejected(request(session, "airRebase", "unitId" to bomber.id, "x" to -6, "y" to 0), "CANNOT_MOVE")
        // 只有空军可以转场。
        rejected(request(session, "airRebase", "unitId" to unit(game, "Warrior").id, "x" to 0, "y" to 0), "UNSUPPORTED")
        // 未准备扫荡时以扫荡形式提交会被拒绝：目标地块无防守方，不是可空袭目标。
        rejected(request(session, "combatPreview", "unitId" to bomber.id, "x" to -6, "y" to 1), "CANNOT_ATTACK")
        bomber.currentMovement = 0f
        rejected(request(session, "airRebase", "unitId" to bomber.id, "x" to 0, "y" to 0), "CANNOT_MOVE")
    }
}
