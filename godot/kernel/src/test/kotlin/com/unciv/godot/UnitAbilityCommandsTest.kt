package com.unciv.godot

import com.unciv.godot.UnitAbilityFixtures.add
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.godot.UnitAbilityFixtures.capital
import com.unciv.godot.UnitAbilityFixtures.center
import com.unciv.godot.UnitAbilityFixtures.cityStateAt
import com.unciv.godot.UnitAbilityFixtures.greeceCapital
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.removeConditionals
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 宗教／伟人主动能力闭环：期望端只调用 core 的类型化 [com.unciv.ui.screens.worldscreen.unit.actions.UnitActions]
 * 并执行一次原生闭包，不使用待测网关生成期望；每步比较完整持久化状态（含宗教压力、信徒、外交标记、
 * 圣城状态、伟人收益、建筑进度、瞬建与单位消耗）。
 */
class UnitAbilityCommandsTest {
    private fun state(game: GameInfo) = json().apply { setUsePrototypes(false) }.toJson(game)
    private fun own(game: GameInfo, id: Int): MapUnit = game.currentPlayerCiv.units.getCivUnits().first { it.id == id }
    private fun sessionFor(game: GameInfo) = BattleFixtures.load(UnitAbilityFixtures.export(game, "unit-abilities"))
    private fun expectedFor(game: GameInfo) = UnitAbilityFixtures.copy(game)

    private fun reject(session: GameSession, command: JsonObject, code: String) {
        val game = session.game!!
        val before = state(game)
        val revision = session.revision
        val result = session.handle(command)
        assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
        assertSame("拒绝不得触发回滚重建", game, session.game)
        assertEquals(before, state(game))
        assertEquals(revision, session.revision)
    }

    private fun options(session: GameSession, unitId: Int): JsonObject =
        run(session, "unitAbilityOptions", "unitId" to unitId)["data"]!!.jsonObject

    private fun candidate(data: JsonObject, type: String): JsonObject =
        data["candidates"]!!.jsonArray.map { it.jsonObject }.first { it.text("type") == type }

    private fun useAbility(session: GameSession, unitId: Int, data: JsonObject, type: String): JsonObject {
        val c = candidate(data, type)
        assertTrue("候选 $type 应可执行：${c.text("reason")}", c.boolean("enabled"))
        return run(session, "unitAbility", "unitId" to unitId, "candidateId" to c.text("id"), "token" to data.text("token"))
    }

    // —— 只读与摘要 ——
    @Test fun optionsAreReadOnlyAndSummarizedInUnitOptions() {
        val game = UnitAbilityFixtures.game().also { add(it, "Great Scientist", center) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val before = state(session.game!!)
        val revision = session.revision
        val data = options(session, id)
        assertEquals("unitAbilityOptions 不得改动状态", before, state(session.game!!))
        assertEquals(revision, session.revision)
        assertEquals(id, data.integer("unitId"))
        val types = data["candidates"]!!.jsonArray.map { it.jsonObject.text("type") }.toSet()
        assertTrue("应含加速研究", "hurryResearch" in types)
        assertTrue("应含瞬建", "createImprovement" in types)
        assertFalse("不开放通用 TriggerUnique", "goldenAge" in types)
        // 快照 unitOptions 摘要与独立查询同源。
        val summary = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject["abilities"]!!.jsonObject
        assertEquals(data["candidates"]!!.jsonArray.size, summary.integer("count"))
        assertTrue(summary.boolean("usable"))
    }

    // —— 伟人能力差分 ——
    @Test fun hurryResearchMatchesNativeAndConsumesUnit() {
        val game = UnitAbilityFixtures.game().also { add(it, "Great Scientist", HexCoord(2, 0)) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, id)
        assertTrue(candidate(data, "hurryResearch").boolean("consumesUnit"))
        useAbility(session, id, data, "hurryResearch")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, id), UnitActionType.HurryResearch)
        assertGameplayEquals("加速研究应与原生一致并消耗单位", expected, session.game!!)
        assertTrue("单位应被消耗", session.game!!.currentPlayerCiv.units.getCivUnits().none { it.id == id })
    }

    @Test fun academyInstantBuildMatchesNativeAndClaimsTile() {
        // 学院不可建于中立地块（ImprovementFunctions.OutsideBorders）：先把首都 ring1 划归己方，
        // 再把大科学家放在己方领土 (1,0) 上瞬建学院。
        val game = UnitAbilityFixtures.game()
        native(game) {
            val city = capital(game)
            val origin = game.tileMap[center]
            for (tile in game.tileMap.tileList) {
                if (tile.aerialDistanceTo(origin) == 1 && tile.getOwner() == null) {
                    city.tiles.add(tile.position)
                    tile.setOwningCity(city)
                }
            }
            game.setTransients()
            add(game, "Great Scientist", HexCoord(1, 0))
        }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, id)
        val c = candidate(data, "createImprovement")
        assertTrue(c.text("title").contains("Academy"))
        useAbility(session, id, data, "createImprovement")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, id), UnitActionType.CreateImprovement,
            UnitAbilityFixtures.instantBuildFilter("Academy"))
        assertGameplayEquals("瞬建学院应与原生一致", expected, session.game!!)
        assertEquals("Academy", session.game!!.tileMap[HexCoord(1, 0)].improvement)
    }

    @Test fun hurryBuildingAddsNativeProductionAndConsumesUnit() {
        val game = UnitAbilityFixtures.game().also {
            capital(it).cityConstructions.constructionQueue = arrayListOf("Library")
            add(it, "Great Engineer", center)
        }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, id)
        val c = candidate(data, "hurryBuilding")
        assertTrue("目标应为首都", c["target"]!!.jsonObject.text("city").isNotEmpty())
        useAbility(session, id, data, "hurryBuilding")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, id), UnitActionType.HurryBuilding)
        assertGameplayEquals("加速建筑应与原生一致", expected, session.game!!)
    }

    @Test fun goldenAgeUsesNativeOneTimeEnterGoldenAgeTurns() {
        val game = UnitAbilityFixtures.game().also { add(it, "Great Artist", HexCoord(2, 0)) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, id)
        useAbility(session, id, data, "goldenAge")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, id), UnitActionType.TriggerUnique,
            UnitAbilityFixtures.goldenAgeFilter)
        assertGameplayEquals("黄金时代应与原生一致", expected, session.game!!)
        assertTrue("应进入黄金时代", session.game!!.currentPlayerCiv.goldenAges.turnsLeftForCurrentGoldenAge > 0)
    }

    @Test fun tradeMissionRequiresCityStateTileAndMatchesNative() {
        val game = UnitAbilityFixtures.game(cityState = true).also { add(it, "Great Merchant", HexCoord(5, 0)) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, id)
        val c = candidate(data, "tradeMission")
        assertTrue("城邦地块应可执行", c.boolean("enabled"))
        useAbility(session, id, data, "tradeMission")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, id), UnitActionType.ConductTradeMission)
        assertGameplayEquals("城邦贸易任务应与原生一致", expected, session.game!!)
    }

    @Test fun tradeMissionDisabledAwayFromCityStates() {
        val game = UnitAbilityFixtures.game(cityState = true).also { add(it, "Great Merchant", HexCoord(2, 0)) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val data = options(session, id)
        val c = candidate(data, "tradeMission")
        assertFalse("非城邦地块不可执行", c.boolean("enabled"))
        assertTrue(c.text("reason").isNotEmpty())
        reject(session, request(session, "unitAbility", "unitId" to id,
            "candidateId" to c.text("id"), "token" to data.text("token")), "UNIT_ACTION")
    }

    // —— 宗教能力差分 ——
    @Test fun spreadReligionAddsPressureFollowersAndDiplomaticFlag() {
        val game = ReligionFixtures.foundedGame()
        val unit = native(game) { UnitAbilityFixtures.addReligiousUnit(game, "Missionary", HexCoord(5, 0)) }
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, unit.id)
        val c = candidate(data, "spreadReligion")
        assertTrue("传教目标应为异族城市", c["target"]!!.jsonObject.text("city").isNotEmpty())
        assertEquals(2, c.integer("maxUsages"))
        useAbility(session, unit.id, data, "spreadReligion")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, unit.id), UnitActionType.SpreadReligion)
        assertGameplayEquals("传教压力／信徒／外交标记应与原生一致", expected, session.game!!)
        // 首次使用仅扣次数不消耗单位。
        assertTrue("首次传教后单位仍存在", session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == unit.id })
    }

    @Test fun removeHeresyRestoresBlockedHolyCityState() {
        val game = ReligionFixtures.foundedGame()
        var foreign = ""
        native(game) {
            foreign = UnitAbilityFixtures.greeceReligion(game)
            val city = capital(game)
            city.religion.religionThisIsTheHolyCityOf = foreign
            city.religion.addPressure(foreign, 100)
        }
        val unit = native(game) { UnitAbilityFixtures.addReligiousUnit(game, "Inquisitor", center) }
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, unit.id)
        useAbility(session, unit.id, data, "removeHeresy")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, unit.id), UnitActionType.RemoveHeresy)
        assertGameplayEquals("清除异教后的圣城状态应与原生一致", expected, session.game!!)
        // removeAllPressuresExceptFor 会保留无神论(Atheism)键，故只断言异教压力归零且圣城被标记受阻。
        val religion = session.game!!.currentPlayerCiv.cities.single { it.location == center }.religion
        assertEquals("异教压力应被清除", 0, religion.getPressures()[foreign])
        assertTrue("异教圣城应被标记为受阻", religion.isBlockedHolyCity)
        assertEquals("圣城归属标记应保留为异教", foreign, religion.religionThisIsTheHolyCityOf)
    }

    // —— 门禁、票据与重复提交 ——
    @Test fun ownershipMovementAndStaleTicketsAreRejected() {
        val game = UnitAbilityFixtures.game().also {
            add(it, "Great Scientist", HexCoord(2, 0))
            BattleFixtures.add(it, "Warrior", greeceCapital.x, greeceCapital.y, UnitAbilityFixtures.enemy(it))
        }
        val id = game.currentPlayerCiv.units.getCivUnits().first { it.name == "Great Scientist" }.id
        val enemyId = UnitAbilityFixtures.enemy(game).units.getCivUnits().first().id
        val session = sessionFor(game)
        reject(session, request(session, "unitAbilityOptions", "unitId" to enemyId), "NOT_OWNED")
        reject(session, request(session, "unitAbility", "unitId" to enemyId, "candidateId" to "x", "token" to "y"), "NOT_OWNED")

        val data = options(session, id)
        // 推进一次普通移动（bump revision 且改变位置）后旧票据失效。
        run(session, "move", "unitId" to id, "x" to 2, "y" to 1)
        val c = candidate(data, "hurryResearch")
        reject(session, request(session, "unitAbility", "unitId" to id,
            "candidateId" to c.text("id"), "token" to data.text("token")), "ABILITY_DECISION")
        // 伪造票据与未知候选同样被拒。
        val fresh = options(session, id)
        reject(session, request(session, "unitAbility", "unitId" to id,
            "candidateId" to c.text("id"), "token" to "forged"), "ABILITY_DECISION")
        reject(session, request(session, "unitAbility", "unitId" to id,
            "candidateId" to "nope", "token" to fresh.text("token")), "ABILITY_CANDIDATE")
    }

    @Test fun exhaustedChargesConsumeUnitAndDropCandidates() {
        val game = ReligionFixtures.foundedGame()
        // 传教一次即耗光行动力，无法同回合二次传教；改为预置已用 1 次（Missionary 共 2 次），
        // 使下一次传教成为最后一次，触发原生“用尽后消耗单位”（UnitActionAfterWhichConsumed）。
        val unit = native(game) {
            val missionary = UnitAbilityFixtures.addReligiousUnit(game, "Missionary", HexCoord(5, 0))
            val key = missionary.getMatchingUniques(UniqueType.CanSpreadReligion).first().text.removeConditionals()
            missionary.abilityToTimesUsed[key] = 1
            missionary
        }
        val session = sessionFor(game)
        val expected = expectedFor(game)
        val data = options(session, unit.id)
        val c = candidate(data, "spreadReligion")
        assertEquals("应只剩最后一次", 1, c.integer("usesLeft"))
        assertTrue("最后一次传教应标注消耗单位", c.boolean("consumesUnit"))
        useAbility(session, unit.id, data, "spreadReligion")
        UnitAbilityFixtures.nativeAbility(expected, own(expected, unit.id), UnitActionType.SpreadReligion)
        assertGameplayEquals("耗尽次数并消耗单位应与原生一致", expected, session.game!!)
        assertTrue("次数耗尽后单位应被消耗", session.game!!.currentPlayerCiv.units.getCivUnits().none { it.id == unit.id })
        reject(session, request(session, "unitAbilityOptions", "unitId" to unit.id), "NOT_OWNED")
    }

    @Test fun duplicateSubmitWithSameRequestIdConsumesOnce() {
        val game = UnitAbilityFixtures.game().also { add(it, "Great Scientist", HexCoord(2, 0)) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val data = options(session, id)
        val c = candidate(data, "hurryResearch")
        val body = request(session, "unitAbility", "unitId" to id, "candidateId" to c.text("id"), "token" to data.text("token"))
        val first = session.handle(body)
        assertTrue(first["ok"]!!.jsonPrimitive.boolean)
        val revision = session.revision
        val second = session.handle(body)
        assertEquals("同 requestId 重试应返回同一响应", first, second)
        assertEquals("重复提交不得二次写入", revision, session.revision)
        assertTrue("单位只被消耗一次", session.game!!.currentPlayerCiv.units.getCivUnits().none { it.id == id })
    }

    // —— 为真实窗口 smoke 导出独立原生夹具（不经网关生成期望）——
    @Test fun exportNativeSmokeScenarios() { UnitAbilityFixtures.emit() }
}
