package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/**
 * 城市主动轰击差分：期望端只调用 [UnitCityActionsFixtures.apply]（原生 ForeignCityView.tryBombard），
 * 网关端走 cityBombardPreview／cityBombard；预览零副作用，执行后逐步比较完整持久化状态。
 * 目标集复刻 BattleTable 对敌方城市的排除；拒绝用例校验错误码与状态不变。
 */
class CityCombatCommandsTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() = KernelRuntime.initialize(UnitCityActionsFixtures.root)
    }

    private class Diff(val session: GameSession, var expected: GameInfo)

    private fun scenario(game: GameInfo, name: String): Diff {
        val file = UnitCityActionsFixtures.export(game, name)
        return Diff(BattleFixtures.load(file), UnitCityActionsFixtures.copy(game))
    }
    private fun state(session: GameSession) = UncivFiles.gameInfoToString(session.game!!, false)
    private fun playerId(session: GameSession) = session.game!!.currentPlayerCiv.cities.first().id
    private fun bombardDto(session: GameSession, cityId: String) =
        run(session, "cityOptions", "cityId" to cityId)["data"]!!.jsonObject["bombard"]!!.jsonObject
    private fun targets(dto: JsonObject) = dto["attackTargets"]!!.jsonArray.map { it.jsonObject }
        .map { it.integer("x") to it.integer("y") }

    private fun reject(session: GameSession, command: JsonObject, code: String) {
        val game = session.game!!
        val before = state(session)
        val revision = session.revision
        val result = session.handle(command)
        assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
        assertSame("拒绝不得触发回滚重建", game, session.game)
        assertEquals(before, state(session))
        assertEquals(revision, session.revision)
    }

    @Test fun bombardLandTargetMatchesNative() {
        val game = NavalFixtures.game().also { g -> native(g) {
            add(g, "Warrior", 1, 0, enemy(g)).health = 100
            NavalFixtures.ready(g)
        } }
        val d = scenario(game, "diff-bombard")
        val session = d.session
        val cityId = playerId(session)
        // cityOptions 只读，不得改动状态或 revision
        val before = state(session)
        val revision = session.revision
        val bombard = bombardDto(session, cityId)
        assertEquals("只读查询不得改动存档", before, state(session))
        assertEquals("只读查询不得推进 revision", revision, session.revision)
        assertTrue(bombard["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(bombard["supported"]!!.jsonPrimitive.boolean)
        assertTrue(targets(bombard).contains(1 to 0))
        // 预览零副作用，且城市轰击不受反击、不能占城、无路径
        val preview = run(session, "cityBombardPreview", "cityId" to cityId, "x" to 1, "y" to 0)["data"]!!.jsonObject
        assertEquals(before, state(session))
        assertEquals(revision, session.revision)
        assertEquals("bombard", preview.text("kind"))
        assertFalse(preview["canCaptureCity"]!!.jsonPrimitive.boolean)
        assertTrue(preview["path"]!!.jsonArray.isEmpty())
        assertEquals(0, preview["damageToAttacker"]!!.jsonObject.integer("max"))
        val minDamage = preview["damageToDefender"]!!.jsonObject.integer("min")
        val maxDamage = preview["damageToDefender"]!!.jsonObject.integer("max")
        assertTrue(maxDamage >= minDamage)
        // 执行：期望端原生重放，网关端 cityBombard，比较完整状态
        d.expected = UnitCityActionsFixtures.apply(d.expected, "bombard", dto("cityId" to cityId, "x" to 1, "y" to 0))
        val response = run(session, "cityBombard", "cityId" to cityId, "x" to 1, "y" to 0)
        assertGameplayEquals("城市轰击", d.expected, session.game!!)
        val result = response["battleResult"]!!.jsonObject
        assertEquals("bombarded", result.text("outcome"))
        val dealt = result.integer("damageToDefender")
        assertTrue("实际伤害应落在预览区间内", dealt in minDamage..maxDamage)
        val defender = enemy(session.game!!).units.getCivUnits().first { it.currentTile.position == HexCoord(1, 0) }
        assertEquals(100 - dealt, defender.health)
        assertTrue(session.game!!.currentPlayerCiv.cities.first().attackedThisTurn)
        // 同回合重复轰击被拒
        assertFalse(bombardDto(session, cityId)["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(bombardDto(session, cityId).text("reason").contains("已攻击"))
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to 1, "y" to 0), "CANNOT_ATTACK")
        // 保存重载与下一回合恢复轰击资格
        val saved = run(session, "save", "name" to "test-bombard").text("savedPath")
        run(session, "load", "path" to saved)
        assertGameplayEquals("轰击重载", UnitCityActionsFixtures.copy(d.expected), session.game!!)
        d.expected = UnitCityActionsFixtures.apply(d.expected, "nextTurn")
        run(session, "nextTurn")
        assertGameplayEquals("轰击跨回合", d.expected, session.game!!)
        assertFalse(session.game!!.currentPlayerCiv.cities.first().attackedThisTurn)
    }

    @Test fun bombardKillsLowHealthTargetMatchingNative() {
        val game = UnitCityActionsFixtures.bombard()
        val d = scenario(game, "diff-bombard-kill")
        val cityId = playerId(d.session)
        val cityIdParam = game.currentPlayerCiv.cities.first().id
        d.expected = UnitCityActionsFixtures.apply(d.expected, "bombard", dto("cityId" to cityIdParam, "x" to 1, "y" to 0))
        run(d.session, "cityBombard", "cityId" to cityId, "x" to 1, "y" to 0)
        assertGameplayEquals("轰击击杀", d.expected, d.session.game!!)
        assertFalse("低血量目标应被击杀", enemy(d.session.game!!).units.getCivUnits().any { it.currentTile.position == HexCoord(1, 0) })
    }

    @Test fun bombardExcludesEnemyCityTarget() {
        val game = NavalFixtures.game().also { g -> native(g) {
            enemy(g).addCity(HexCoord(2, 0))
            NavalFixtures.ready(g)
        } }
        val session = scenario(game, "diff-bombard-city").session
        val cityId = playerId(session)
        val bombard = bombardDto(session, cityId)
        assertFalse("敌方城市不是合法轰击目标", targets(bombard).contains(2 to 0))
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to 2, "y" to 0), "CANNOT_ATTACK")
        reject(session, request(session, "cityBombardPreview", "cityId" to cityId, "x" to 2, "y" to 0), "CANNOT_ATTACK")
    }

    @Test fun bombardRejectionsAndInvariants() {
        val game = NavalFixtures.game().also { g -> native(g) {
            add(g, "Warrior", 1, 0, enemy(g)).health = 100
            add(g, "Warrior", 2, 0, enemy(g)).health = 100
            NavalFixtures.ready(g)
        } }
        val session = scenario(game, "diff-bombard-reject").session
        val cityId = playerId(session)
        val enemyCityId = enemy(session.game!!).cities.first().id
        // 非己方城市
        reject(session, request(session, "cityBombard", "cityId" to enemyCityId, "x" to 1, "y" to 0), "NOT_OWNED")
        // 越界与空地目标
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to 999, "y" to 999), "CANNOT_ATTACK")
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to 0, "y" to 2), "CANNOT_ATTACK")
        // 参数类型与过期 revision
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to "a", "y" to 0), "INVALID_ARGUMENT")
        reject(session, JsonObject(request(session, "cityBombard", "cityId" to cityId, "x" to 1, "y" to 0)
            + ("revision" to JsonPrimitive(0))), "STALE_STATE")
        // 未接入的待决事件阻塞
        session.game!!.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.RecapturedCivilian, "worker"))
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to 1, "y" to 0), "PENDING_DECISION")
        session.game!!.currentPlayerCiv.popupAlerts.clear()
        // 轰击一处后本回合不能轰击另一处
        assertTrue(targets(bombardDto(session, cityId)).containsAll(listOf(1 to 0, 2 to 0)))
        val command = request(session, "cityBombard", "cityId" to cityId, "x" to 1, "y" to 0)
        val first = session.handle(command)
        assertTrue(first.toString(), first["ok"]!!.jsonPrimitive.boolean)
        assertEquals("同 requestId 重复提交只执行一次", first, session.handle(command))
        val reused = session.handle(JsonObject(command + ("x" to JsonPrimitive(2))))
        assertEquals("REQUEST_REUSED", reused["error"]!!.jsonObject.text("code"))
        reject(session, request(session, "cityBombard", "cityId" to cityId, "x" to 2, "y" to 0), "CANNOT_ATTACK")
    }
}
