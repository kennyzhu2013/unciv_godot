package com.unciv.godot

import com.unciv.godot.BattleFixtures.request
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 第一阶段夹具验证：四项动作必须能只走原生入口完成（无 UI、无网关）；
 * 四个网关失败用例锁定尚未登记的命令，内核实现后必须转绿。
 */
class UnitCityActionsFixturesTest {
    companion object {
        // emit() 会初始化 KernelRuntime 并生成 unitcity-*.json fixture；命令测试 load 这些文件，必须先于它们运行。
        @BeforeClass @JvmStatic fun setup() = UnitCityActionsFixtures.emit()
    }

    @Test fun emitIndependentUnitCityFixtures() {
        UnitCityActionsFixtures.emit()
        val exported = Json.parseToJsonElement(java.io.File(UnitCityActionsFixtures.root,
            "godot/.local/tests/unitcity-expected.json").readText()).jsonObject
        val scenarios = exported["scenarios"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("promotion", "upgrade", "bombard", "workboat"), scenarios.map { it.text("name") })
        // 原生重放逐步成功即证明四项均可无 UI 调用；期望状态与 raw 证据已落盘。
        for (scenario in scenarios) {
            val states = scenario["states"]!!.jsonObject
            assertTrue(states.keys.contains("initial"))
            for (step in scenario["steps"]!!.jsonArray.map { it.jsonObject })
                assertTrue(states.keys.contains(step.text("step")))
        }
    }

    @Test fun unitPromoteCommandRegistered() {
        val session = GameSession(UnitCityActionsFixtures.root)
        BattleFixtures.run(session, "load", "path" to fixture("promotion"))
        val unit = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" }
        val response = session.handle(request(session, "unitPromote", "unitId" to unit.id, "promotion" to "Shock I"))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun unitUpgradeCommandRegistered() {
        val session = GameSession(UnitCityActionsFixtures.root)
        BattleFixtures.run(session, "load", "path" to fixture("upgrade"))
        val unit = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Trireme" }
        val response = session.handle(request(session, "unitUpgrade", "unitId" to unit.id, "targetUnit" to "Caravel"))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun cityBombardCommandRegistered() {
        val session = GameSession(UnitCityActionsFixtures.root)
        BattleFixtures.run(session, "load", "path" to fixture("bombard"))
        val city = session.game!!.currentPlayerCiv.cities.first()
        val preview = session.handle(request(session, "cityBombardPreview", "cityId" to city.id, "x" to 1, "y" to 0))
        assertTrue(preview.toString(), preview["ok"]!!.jsonPrimitive.boolean)
        val response = session.handle(request(session, "cityBombard", "cityId" to city.id, "x" to 1, "y" to 0))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun workboatWaterImprovementCommandRegistered() {
        val session = GameSession(UnitCityActionsFixtures.root)
        BattleFixtures.run(session, "load", "path" to fixture("workboat"))
        val unit = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Work Boats" }
        val response = session.handle(request(session, "workerOrder",
            "unitId" to unit.id, "type" to "createWaterImprovement", "name" to "Fishing Boats"))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
    }

    private fun fixture(name: String) = java.io.File(UnitCityActionsFixtures.root,
        "godot/.local/tests/unitcity-$name.json").absolutePath
}
