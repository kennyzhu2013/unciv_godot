package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
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
 * 单位晋升与升级差分：期望端只调用 [UnitCityActionsFixtures.apply]（原生 UnitPromotions／UnitActionsUpgrade），
 * 网关端走 unitPromote／unitUpgrade；两端各自独立加载同一原生存档，逐步比较完整持久化状态。
 * 拒绝用例校验错误码、revision、序列化状态与 GameInfo 对象身份均不变。
 */
class UnitProgressionCommandsTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() = KernelRuntime.initialize(UnitCityActionsFixtures.root)
    }

    private class Diff(val session: GameSession, var expected: GameInfo)

    private fun scenario(game: GameInfo, name: String): Diff {
        val file = UnitCityActionsFixtures.export(game, name)
        return Diff(BattleFixtures.load(file), UnitCityActionsFixtures.copy(game))
    }
    private fun state(session: GameSession) = UncivFiles.gameInfoToString(session.game!!, false)
    private fun idOf(game: GameInfo, name: String) = game.currentPlayerCiv.units.getCivUnits().first { it.name == name }.id
    private fun progressionOf(session: GameSession, unitId: Int) =
        run(session, "unitOptions", "unitId" to unitId)["data"]!!.jsonObject["progression"]!!.jsonObject
    private fun option(dto: JsonObject, kind: String, name: String) =
        dto[kind]!!.jsonArray.map { it.jsonObject }.first { it.text("name") == name }

    /** 只读 unitOptions 不得改动状态或 revision；随后期望端原生晋升、网关端 unitPromote，比较完整状态。 */
    private fun promote(d: Diff, label: String, unitId: Int, promotion: String) {
        val before = state(d.session)
        val revision = d.session.revision
        run(d.session, "unitOptions", "unitId" to unitId)
        assertEquals("只读查询不得改动存档", before, state(d.session))
        assertEquals("只读查询不得推进 revision", revision, d.session.revision)
        d.expected = UnitCityActionsFixtures.apply(d.expected, "promote", dto("unitId" to unitId, "promotion" to promotion))
        run(d.session, "unitPromote", "unitId" to unitId, "promotion" to promotion)
        assertGameplayEquals(label, d.expected, d.session.game!!)
    }

    private fun upgrade(d: Diff, label: String, unitId: Int, target: String) {
        d.expected = UnitCityActionsFixtures.apply(d.expected, "upgrade", dto("unitId" to unitId, "targetUnit" to target))
        run(d.session, "unitUpgrade", "unitId" to unitId, "targetUnit" to target)
        assertGameplayEquals(label, d.expected, d.session.game!!)
    }

    private fun roundtrip(d: Diff, label: String, saveName: String) {
        val saved = run(d.session, "save", "name" to saveName).text("savedPath")
        run(d.session, "load", "path" to saved)
        assertGameplayEquals(label, UnitCityActionsFixtures.copy(d.expected), d.session.game!!)
    }

    private fun nextTurn(d: Diff, label: String) {
        d.expected = UnitCityActionsFixtures.apply(d.expected, "nextTurn")
        run(d.session, "nextTurn")
        assertGameplayEquals(label, d.expected, d.session.game!!)
    }

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

    @Test fun landAndNavalSinglePromotionMatchNative() {
        val game = UnitCityActionsFixtures.promotion()
        val d = scenario(game, "diff-promotion-single")
        val warriorId = idOf(game, "Warrior")
        val triremeId = idOf(game, "Trireme")
        val warrior = option(progressionOf(d.session, warriorId), "promotions", "Shock I")
        assertTrue(warrior.toString(), warrior["enabled"]!!.jsonPrimitive.boolean)
        assertFalse("普通晋升不是免费晋升", warrior["free"]!!.jsonPrimitive.boolean)
        promote(d, "陆军 Shock I", warriorId, "Shock I")
        roundtrip(d, "陆军晋升重载", "test-promotion-land")
        promote(d, "海军 Boarding Party I", triremeId, "Boarding Party I")
        assertTrue(progressionOf(d.session, triremeId)["acquired"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("Boarding Party I"))
        nextTurn(d, "晋升跨回合")
    }

    @Test fun sequentialPromotionsConsumeExperienceAndIncrementCount() {
        val game = NavalFixtures.game().also { g -> native(g) { add(g, "Warrior", 1, 0).promotions.XP = 200; NavalFixtures.ready(g) } }
        val d = scenario(game, "diff-promotion-sequence")
        val id = idOf(game, "Warrior")
        val initial = progressionOf(d.session, id)
        assertEquals(0, initial.integer("numberOfPromotions"))
        val firstCost = initial.integer("xpForNextPromotion")
        promote(d, "陆军 Shock I", id, "Shock I")
        val afterFirst = progressionOf(d.session, id)
        assertEquals(1, afterFirst.integer("numberOfPromotions"))
        assertEquals(200 - firstCost, afterFirst.integer("xp"))
        // 前置满足后 Shock II 解锁且可负担
        val shockII = option(afterFirst, "promotions", "Shock II")
        assertTrue(shockII.toString(), shockII["enabled"]!!.jsonPrimitive.boolean)
        val secondCost = afterFirst.integer("xpForNextPromotion")
        promote(d, "陆军 Shock II", id, "Shock II")
        val afterSecond = progressionOf(d.session, id)
        assertEquals(2, afterSecond.integer("numberOfPromotions"))
        assertEquals(200 - firstCost - secondCost, afterSecond.integer("xp"))
        assertEquals(listOf("Shock I", "Shock II"), afterSecond["acquired"]!!.jsonArray.map { it.jsonPrimitive.content })
        roundtrip(d, "逐级晋升重载", "test-promotion-sequence")
    }

    @Test fun healInstantlyRestoresHealthMatchingNative() {
        val game = NavalFixtures.game().also { g -> native(g) {
            add(g, "Warrior", 1, 0).apply { health = 40; promotions.XP = 15 }
            NavalFixtures.ready(g)
        } }
        val d = scenario(game, "diff-promotion-heal")
        val id = idOf(game, "Warrior")
        val heal = option(progressionOf(d.session, id), "promotions", "Heal Instantly")
        assertTrue(heal.toString(), heal["enabled"]!!.jsonPrimitive.boolean)
        promote(d, "即时治疗", id, "Heal Instantly")
        assertTrue("治疗后 HP 应恢复", unit(d.session.game!!, "Warrior").health > 40)
    }

    @Test fun insufficientExperienceAndTurnStateRejectPromotion() {
        val game = NavalFixtures.game().also { g -> native(g) { add(g, "Warrior", 1, 0); NavalFixtures.ready(g) } }
        val d = scenario(game, "diff-promotion-reject")
        val session = d.session
        val id = idOf(game, "Warrior")
        val shock = option(progressionOf(session, id), "promotions", "Shock I")
        assertFalse("XP 不足不能晋升", shock["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(shock.text("reason").contains("经验不足"))
        reject(session, request(session, "unitPromote", "unitId" to id, "promotion" to "Shock I"), "CANNOT_PROMOTE")
        reject(session, request(session, "unitPromote", "unitId" to id, "promotion" to "NotAPromotion"), "INVALID_ARGUMENT")
        reject(session, request(session, "unitPromote", "unitId" to id + 999999, "promotion" to "Shock I"), "NOT_OWNED")
        reject(session, request(session, "unitPromote", "unitId" to "abc", "promotion" to "Shock I"), "INVALID_ARGUMENT")
        reject(session, JsonObject(request(session, "unitPromote", "unitId" to id, "promotion" to "Shock I")
            + ("revision" to JsonPrimitive(0))), "STALE_STATE")
        val warrior = unit(session.game!!, "Warrior")
        warrior.promotions.XP = 100
        // 未接入的待决事件阻塞写入
        session.game!!.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.RecapturedCivilian, "worker"))
        reject(session, request(session, "unitPromote", "unitId" to id, "promotion" to "Shock I"), "PENDING_DECISION")
        session.game!!.currentPlayerCiv.popupAlerts.clear()
        // 行动力耗尽 / 本回合已攻击
        warrior.currentMovement = 0f
        reject(session, request(session, "unitPromote", "unitId" to id, "promotion" to "Shock I"), "CANNOT_PROMOTE")
        warrior.currentMovement = warrior.getMaxMovement().toFloat()
        warrior.attacksThisTurn = 1
        reject(session, request(session, "unitPromote", "unitId" to id, "promotion" to "Shock I"), "CANNOT_PROMOTE")
    }

    @Test fun airUnitsStayUnsupportedForProgression() {
        val game = NavalFixtures.game().also { g -> native(g) { add(g, "Fighter", 0, 0); NavalFixtures.ready(g) } }
        val session = scenario(game, "diff-progression-air").session
        val id = idOf(game, "Fighter")
        assertFalse(progressionOf(session, id)["supported"]!!.jsonPrimitive.boolean)
        reject(session, request(session, "unitPromote", "unitId" to id, "promotion" to "Shock I"), "UNSUPPORTED")
        reject(session, request(session, "unitUpgrade", "unitId" to id, "targetUnit" to "Caravel"), "UNSUPPORTED")
    }

    @Test fun navalUpgradeDeductsGoldAndKeepsIdentity() {
        val game = UnitCityActionsFixtures.upgrade()
        val d = scenario(game, "diff-upgrade")
        val id = idOf(game, "Trireme")
        val goldBefore = d.session.game!!.currentPlayerCiv.gold
        val caravel = option(progressionOf(d.session, id), "upgrades", "Caravel")
        assertTrue(caravel.toString(), caravel["enabled"]!!.jsonPrimitive.boolean)
        val cost = caravel.integer("gold")
        assertTrue("升级应消耗金币", cost > 0)
        val before = state(d.session)
        val revision = d.session.revision
        progressionOf(d.session, id)
        assertEquals(before, state(d.session))
        assertEquals(revision, d.session.revision)
        upgrade(d, "海军升级", id, "Caravel")
        assertEquals(goldBefore - cost, d.session.game!!.currentPlayerCiv.gold)
        assertTrue("升级后应保留同 ID 的新兵种对象",
            d.session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == id && it.name == "Caravel" })
        roundtrip(d, "升级重载", "test-upgrade")
        nextTurn(d, "升级跨回合")
    }

    @Test fun upgradeInheritsPromotions() {
        val game = NavalFixtures.game().also { g -> native(g) {
            NavalFixtures.research(g, "Astronomy", g.currentPlayerCiv)
            g.currentPlayerCiv.cities.first().expansion.takeOwnership(g.tileMap[HexCoord(1, -1)])
            add(g, "Trireme", 1, -1).promotions.XP = 15
            NavalFixtures.ready(g)
        } }
        val d = scenario(game, "diff-upgrade-inherit")
        val id = idOf(game, "Trireme")
        promote(d, "升级前晋升", id, "Boarding Party I")
        upgrade(d, "晋升后升级", id, "Caravel")
        val caravel = d.session.game!!.currentPlayerCiv.units.getCivUnits().first { it.id == id }
        assertEquals("Caravel", caravel.name)
        assertTrue("升级应继承既有晋升", caravel.promotions.promotions.contains("Boarding Party I"))
    }

    @Test fun landUpgradeRespectsTechResourceAndTerritory() {
        // 己方领土内但缺铁器科技：剑士升级候选存在但不可执行。
        val game = NavalFixtures.game().also { g -> native(g) {
            g.currentPlayerCiv.cities.first().expansion.takeOwnership(g.tileMap[HexCoord(1, 0)])
            add(g, "Warrior", 1, 0)
            NavalFixtures.ready(g)
        } }
        val d = scenario(game, "diff-upgrade-land")
        val id = idOf(game, "Warrior")
        // 缺铁器科技时剑士升级可能根本不进入原生候选；无论是否列出，升级命令都必须拒绝。
        val swordsman = progressionOf(d.session, id)["upgrades"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it.text("name") == "Swordsman" }
        if (swordsman != null) {
            assertFalse("缺科技或资源时不可升级", swordsman["enabled"]!!.jsonPrimitive.boolean)
            assertTrue(swordsman.text("reason").isNotEmpty())
        }
        reject(d.session, request(d.session, "unitUpgrade", "unitId" to id, "targetUnit" to "Swordsman"), "CANNOT_UPGRADE")
    }

    @Test fun upgradeRejectionsAndInvariants() {
        val game = UnitCityActionsFixtures.upgrade()
        val d = scenario(game, "diff-upgrade-reject")
        val session = d.session
        val id = idOf(game, "Trireme")
        reject(session, request(session, "unitUpgrade", "unitId" to id, "targetUnit" to "NotAUnit"), "INVALID_ARGUMENT")
        reject(session, request(session, "unitUpgrade", "unitId" to id + 999999, "targetUnit" to "Caravel"), "NOT_OWNED")
        reject(session, request(session, "unitUpgrade", "unitId" to id, "targetUnit" to 5), "INVALID_ARGUMENT")
        reject(session, JsonObject(request(session, "unitUpgrade", "unitId" to id, "targetUnit" to "Caravel")
            + ("revision" to JsonPrimitive(0))), "STALE_STATE")
        val ship = NavalFixtures.unit(session.game!!, id)
        ship.currentMovement = 0f
        reject(session, request(session, "unitUpgrade", "unitId" to id, "targetUnit" to "Caravel"), "CANNOT_UPGRADE")
        ship.currentMovement = ship.getMaxMovement().toFloat()
        val gold = session.game!!.currentPlayerCiv.gold
        session.game!!.currentPlayerCiv.addGold(-gold)
        reject(session, request(session, "unitUpgrade", "unitId" to id, "targetUnit" to "Caravel"), "CANNOT_UPGRADE")
        session.game!!.currentPlayerCiv.addGold(gold)
        // 成功升级只执行一次；同 requestId 不同参数被拒。
        val command = request(session, "unitUpgrade", "unitId" to id, "targetUnit" to "Caravel")
        val first = session.handle(command)
        assertTrue(first.toString(), first["ok"]!!.jsonPrimitive.boolean)
        assertEquals(first, session.handle(command))
        val reused = session.handle(JsonObject(command + ("targetUnit" to JsonPrimitive("Ironclad"))))
        assertEquals("REQUEST_REUSED", reused["error"]!!.jsonObject.text("code"))
    }
}
