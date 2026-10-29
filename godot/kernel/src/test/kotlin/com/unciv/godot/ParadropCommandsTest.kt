package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.file
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 第 7 批 7B：空降准备、合法落点、执行、取消与存读档的独立原生差分。
 * 期望端只调用 core 的 `UnitActions`(Paradrop) 与
 * `UnitMovement.getReachableTilesInCurrentTurn`／`moveToTile`，不复用网关 DTO 自证。
 */
class ParadropCommandsTest {
    /** 伞兵必须站在己方陆地上才命中 `MayParadrop` 的 `<in [{Friendly} {Land}] tiles>` 条件。 */
    private fun paratrooper(fixture: String, configure: (GameInfo) -> Unit = {}): File =
        file("paradrop-$fixture") { game ->
            add(game, "Paratrooper", -6, 0)
            configure(game)
        }

    private fun coordinates(array: JsonArray) =
        array.map { it.jsonObject.integer("x") to it.jsonObject.integer("y") }.toSet()

    private fun soldier(game: GameInfo, name: String = "Paratrooper"): MapUnit = unit(game, name)

    /** 准备与取消都是同一个原生闭包：准备态下调用即取消。 */
    private fun toggleNative(game: GameInfo) {
        val raw = soldier(game)
        UnitActions.getUnitActions(raw, UnitActionType.Paradrop)
            .first { it.action != null }.action!!.invoke()
    }

    private fun nativeTargets(game: GameInfo): Set<Pair<Int, Int>> {
        val raw = soldier(game)
        // 落点过滤器不随存档持久化；原生端也先跑一次动作工厂，与游戏内刷新顺序一致。
        UnitActions.getUnitActions(raw, UnitActionType.Paradrop).toList()
        val origin = raw.currentTile.position
        return raw.movement.getReachableTilesInCurrentTurn()
            .filter { it.position != origin }
            .map { it.position.x to it.position.y }.toSet()
    }

    private fun options(session: GameSession, id: Int) =
        run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject

    @Test fun preparationTargetsAndExecutionMatchNative() {
        val fixture = paratrooper("move")
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val id = native(expected) { soldier(expected).id }

        // 未准备：空降动作可点，但没有落点候选，准备态专属字段为空。
        val idle = options(session, id)
        val info = idle["paradrop"]!!.jsonObject
        assertTrue(info["available"]!!.jsonPrimitive.boolean)
        assertFalse(info["active"]!!.jsonPrimitive.boolean)
        assertTrue(info["enabled"]!!.jsonPrimitive.boolean)
        assertTrue("投送距离取自原生 unique", info.integer("range") >= 1)
        assertTrue("未准备时不投影落点", idle["paradropTargets"]!!.jsonArray.isEmpty())
        val action = idle["actions"]!!.jsonArray.single { it.jsonObject.text("id") == "paradrop" }.jsonObject
        assertTrue(action["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(action["current"]!!.jsonPrimitive.boolean)

        native(expected) { toggleNative(expected) }
        run(session, "unitAction", "unitId" to id, "type" to "paradrop")
        assertGameplayEquals("准备空降", expected, session.game!!)

        // 准备态：只保留空降开关（当前动作），落点集合与原生可达计算逐格一致。
        val armed = options(session, id)
        assertTrue(armed["paradrop"]!!.jsonObject["active"]!!.jsonPrimitive.boolean)
        assertEquals("准备态只保留空降开关", 1, armed["actions"]!!.jsonArray.size)
        assertTrue(armed["actions"]!!.jsonArray.single().jsonObject["current"]!!.jsonPrimitive.boolean)
        assertTrue("准备态不投影普通可达", armed["reachable"]!!.jsonArray.isEmpty())
        val targets = coordinates(armed["paradropTargets"]!!.jsonArray)
        assertEquals("空降落点", native(expected) { nativeTargets(expected) }, targets)
        assertTrue("空降落点不应为空", targets.isNotEmpty())

        val origin = soldier(session.game!!).currentTile
        val target = targets.map { HexCoord(it.first, it.second) }
            .maxByOrNull { origin.aerialDistanceTo(session.game!!.tileMap[it]) }!!
        native(expected) { soldier(expected).movement.moveToTile(expected.tileMap[target.x, target.y]) }

        val result = run(session, "paradrop", "unitId" to id, "x" to target.x, "y" to target.y)
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        assertGameplayEquals("执行空降", expected, session.game!!)
        val moved = soldier(session.game!!)
        assertEquals(target, moved.currentTile.position)
        assertNull("落地后清除准备动作", moved.action)
        assertEquals("原生空降额外消耗一次攻击次数", 1, moved.attacksThisTurn)
        assertEquals(1f, moved.currentMovement)
    }

    @Test fun cancelRestoresIdleStateWithoutSideEffects() {
        val fixture = paratrooper("cancel")
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val id = native(expected) { soldier(expected).id }

        native(expected) { toggleNative(expected) }
        run(session, "unitAction", "unitId" to id, "type" to "paradrop")
        assertGameplayEquals("准备空降", expected, session.game!!)

        native(expected) { toggleNative(expected) }
        run(session, "unitAction", "unitId" to id, "type" to "paradrop")
        assertGameplayEquals("取消空降", expected, session.game!!)

        val idle = options(session, id)
        assertFalse(idle["paradrop"]!!.jsonObject["active"]!!.jsonPrimitive.boolean)
        assertTrue(idle["paradropTargets"]!!.jsonArray.isEmpty())
        val soldier = soldier(session.game!!)
        assertEquals("取消不消耗行动力", soldier.getMaxMovement().toFloat(), soldier.currentMovement)
        assertEquals(0, soldier.attacksThisTurn)
        // 取消后普通动作恢复，可继续按原生规则移动。
        assertTrue(idle["actions"]!!.jsonArray.any { it.jsonObject.text("id") == "skip" })
    }

    @Test fun illegalRequestsLeaveStateUntouched() {
        val session = load(paratrooper("illegal") { game -> add(game, "Fighter", -6, 0) })
        val game = session.game!!
        val id = soldier(game).id
        fun rejected(command: JsonObject, code: String) {
            val before = UncivFiles.gameInfoToString(game, false)
            val revision = session.revision
            val result = session.handle(command)
            assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
            assertEquals(revision, session.revision)
            assertSame("拒绝不应触发回滚重建", game, session.game)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
        }

        // 未准备就提交落点；无空降能力的单位请求开关；空军走转场而不是空降。
        rejected(request(session, "paradrop", "unitId" to id, "x" to -5, "y" to 0), "UNIT_ACTION")
        rejected(request(session, "unitAction", "unitId" to unit(game, "Warrior").id, "type" to "paradrop"), "UNIT_ACTION")
        rejected(request(session, "paradrop", "unitId" to unit(game, "Fighter").id, "x" to -5, "y" to 0), "UNSUPPORTED")

        val expected = UncivFiles.gameInfoFromString(fixtureText(session))
        native(expected) { toggleNative(expected) }
        run(session, "unitAction", "unitId" to id, "type" to "paradrop")
        assertGameplayEquals("准备空降", expected, session.game!!)

        // 同地块、地图外与超出投送距离的陆地都必须在准备阶段被拒，且不改状态。
        val origin = soldier(game).currentTile.position
        rejected(request(session, "paradrop", "unitId" to id, "x" to origin.x, "y" to origin.y), "CANNOT_MOVE")
        rejected(request(session, "paradrop", "unitId" to id, "x" to 9999, "y" to 9999), "TARGET")
        val raw = soldier(game)
        val illegalTile = game.tileMap.values.firstOrNull {
            it.position != raw.currentTile.position && it.isLand && !raw.movement.canReachInCurrentTurn(it)
        }
        assertNotNull("夹具应存在超出投送距离或不可降落的陆地", illegalTile)
        rejected(request(session, "paradrop", "unitId" to id,
            "x" to illegalTile!!.position.x, "y" to illegalTile.position.y), "CANNOT_MOVE")

        val targets = coordinates(options(session, id)["paradropTargets"]!!.jsonArray)
        val target = targets.map { HexCoord(it.first, it.second) }
            .maxByOrNull { soldier(session.game!!).currentTile.aerialDistanceTo(game.tileMap[it]) }!!
        native(expected) { soldier(expected).movement.moveToTile(expected.tileMap[target.x, target.y]) }
        run(session, "paradrop", "unitId" to id, "x" to target.x, "y" to target.y)
        assertGameplayEquals("执行空降", expected, session.game!!)
        // 落地后准备态已清除，同一命令再次提交被拒且不产生第二次移动。
        rejected(request(session, "paradrop", "unitId" to id, "x" to target.x, "y" to target.y), "UNIT_ACTION")
    }

    @Test fun preparedParadropSurvivesSaveAndReload() {
        val fixture = paratrooper("reload")
        val session = load(fixture)
        val id = soldier(session.game!!).id
        run(session, "unitAction", "unitId" to id, "type" to "paradrop")

        // 落点过滤器不持久化：读档后必须由原生工厂重建，才能得到同一候选集合并继续执行。
        val saved = run(session, "save", "name" to "paradrop-preparation").text("savedPath")
        val expected = UncivFiles.gameInfoFromString(File(saved).readText())
        run(session, "load", "path" to saved)
        assertGameplayEquals("准备态重载", expected, session.game!!)
        assertTrue(soldier(session.game!!).isPreparingParadrop())
        val reloaded = options(session, id)
        assertTrue(reloaded["paradrop"]!!.jsonObject["active"]!!.jsonPrimitive.boolean)
        val targets = coordinates(reloaded["paradropTargets"]!!.jsonArray)
        assertEquals("重载后空降落点", native(expected) { nativeTargets(expected) }, targets)
        assertTrue(targets.isNotEmpty())

        val origin = soldier(session.game!!).currentTile
        val target = targets.map { HexCoord(it.first, it.second) }
            .maxByOrNull { origin.aerialDistanceTo(session.game!!.tileMap[it]) }!!
        native(expected) { soldier(expected).movement.moveToTile(expected.tileMap[target.x, target.y]) }
        run(session, "paradrop", "unitId" to id, "x" to target.x, "y" to target.y)
        assertGameplayEquals("重载后执行空降", expected, session.game!!)
    }

    /** 重新序列化当前对局，作为原生对照端的独立起点。 */
    private fun fixtureText(session: GameSession): String =
        UncivFiles.gameInfoToString(session.game!!, true)
}
