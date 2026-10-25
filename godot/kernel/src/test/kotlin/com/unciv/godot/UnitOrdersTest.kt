package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 多回合移动闭环：期望端只调用 core（复刻 WorldMapHolder 确认移动与原生 nextTurn），
 * 不使用网关候选或准备动作生成期望；每步比较完整持久化状态。
 */
class UnitOrdersTest {
    private fun own(game: GameInfo, id: Int) = game.currentPlayerCiv.units.getCivUnits().first { it.id == id }
    // 只读前后比较保留全部持久字段；与 NavalCommandsTest 同一严格探针。
    private fun state(game: GameInfo) = json().apply { setUsePrototypes(false) }.toJson(game)

    /** 期望端复刻原生确认移动：本回合落点 → 移动 → 未抵达则挂原生 moveTo 指令；只调用 core。 */
    private fun nativeMoveTo(game: GameInfo, id: Int, x: Int, y: Int) = native(game) {
        val unit = own(game, id)
        val target = game.tileMap[x, y]
        val from = unit.getTile().position
        val landing = unit.movement.getTileToMoveToThisTurn(target)
        unit.movement.moveToTile(landing)
        if (unit.getTile().position != from) {
            if (unit.isExploring() || unit.isMoving()) unit.action = null
            if (unit.getTile().position != target.position)
                unit.action = "moveTo ${target.position.x},${target.position.y}"
        }
    }

    private fun nativeStop(game: GameInfo, id: Int) = native(game) { own(game, id).action = null }

    /** 确定性陆地图：全草原、无野蛮人、与希腊和平、无初始单位；用于多回合精确差分。 */
    private fun landGame(configure: (GameInfo) -> Unit): GameInfo =
        GreatPersonFixtures.game(free = 0).also { game -> native(game) { configure(game); NavalFixtures.ready(game) } }

    private fun sessionFor(game: GameInfo) = BattleFixtures.load(GreatPersonFixtures.export(game, "unit-orders"))
    private fun expectedFor(game: GameInfo) = GreatPersonFixtures.copy(game)
    private fun nextTurn(expected: GameInfo) = GreatPersonFixtures.nextTurn(expected)

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

    @Test fun multiTurnLandRouteAdvancesWithNativeTurns() {
        val game = landGame { add(it, "Warrior", 3, 3) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        var expected = expectedFor(game)

        // unitRoute 只读预览：不改状态、不推进 revision；后续落点为估算、末尾即目的地。
        val before = state(session.game!!)
        val revision = session.revision
        val route = run(session, "unitRoute", "unitId" to id, "x" to 3, "y" to -5)["data"]!!.jsonObject
        assertEquals("unitRoute 不得改动状态", before, state(session.game!!))
        assertEquals(revision, session.revision)
        val destination = route["destination"]!!.jsonObject
        assertEquals(3, destination.integer("x"))
        assertEquals(-5, destination.integer("y"))
        assertFalse(route["arrivesThisTurn"]!!.jsonPrimitive.boolean)
        assertTrue("应跨越多个回合", route["turns"]!!.jsonPrimitive.int >= 3)
        assertTrue(route["thisTurn"]!!.jsonArray.isNotEmpty())
        val future = route["future"]!!.jsonArray
        assertTrue(future.isNotEmpty())
        assertEquals("未来落点末尾即目的地", destination, future.last().jsonObject)

        // moveTo：本回合前进并挂原生 moveTo 指令，尚未抵达。
        nativeMoveTo(expected, id, 3, -5)
        run(session, "moveTo", "unitId" to id, "x" to 3, "y" to -5)
        assertGameplayEquals("多回合移动 起始", expected, session.game!!)
        assertEquals("moveTo 3,-5", own(session.game!!, id).action)
        assertNotEquals(HexCoord(3, -5), own(session.game!!, id).getTile().position)

        // 逐回合由原生 nextTurn/doAction 推进，直到抵达；每回合完整差分。
        var turns = 0
        while (own(session.game!!, id).getTile().position != HexCoord(3, -5) && turns < 8) {
            expected = nextTurn(expected)
            run(session, "nextTurn")
            assertGameplayEquals("多回合移动 第${++turns}回合", expected, session.game!!)
        }
        assertEquals(HexCoord(3, -5), own(session.game!!, id).getTile().position)
        assertNull("抵达后应清空移动指令", own(session.game!!, id).action)
        assertTrue("确实跨越多回合", turns >= 2)
    }

    @Test fun seaLandTransitionEmbarksAcrossTurns() {
        val game = NavalFixtures.game(technology = "Astronomy").also { g -> native(g) { add(g, "Warrior", 2, 0); NavalFixtures.ready(g) } }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = BattleFixtures.load(NavalFixtures.export(game, "unit-orders-sea"))
        var expected = NavalFixtures.copy(game)

        val route = run(session, "unitRoute", "unitId" to id, "x" to 2, "y" to -2)["data"]!!.jsonObject
        assertFalse("远洋目标不应本回合抵达", route["arrivesThisTurn"]!!.jsonPrimitive.boolean)

        // 第1回合：由陆地登船到海岸，挂 moveTo 指令。
        nativeMoveTo(expected, id, 2, -2)
        run(session, "moveTo", "unitId" to id, "x" to 2, "y" to -2)
        assertGameplayEquals("海陆转换 登船", expected, session.game!!)
        assertEquals("moveTo 2,-2", own(session.game!!, id).action)
        assertTrue("登船后应处于水上", own(session.game!!, id).isEmbarked())

        // 第2回合：跨回合推进到远洋并抵达，清空指令。
        expected = nextTurn(expected)
        run(session, "nextTurn")
        assertGameplayEquals("海陆转换 抵达", expected, session.game!!)
        assertEquals(HexCoord(2, -2), own(session.game!!, id).getTile().position)
        assertNull(own(session.game!!, id).action)
    }

    @Test fun stopMovementClearsMultiTurnOrder() {
        val game = landGame { add(it, "Warrior", 3, 3) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        var expected = expectedFor(game)

        nativeMoveTo(expected, id, 3, -5)
        run(session, "moveTo", "unitId" to id, "x" to 3, "y" to -5)
        assertGameplayEquals("停止移动 起始", expected, session.game!!)
        assertTrue(own(session.game!!, id).isMoving())

        nativeStop(expected, id)
        run(session, "unitOrder", "unitId" to id, "type" to "stopMovement")
        assertGameplayEquals("停止移动", expected, session.game!!)
        assertNull(own(session.game!!, id).action)

        // 停止后不再推进：跨回合单位保持原地。
        val stayed = own(session.game!!, id).getTile().position
        expected = nextTurn(expected)
        run(session, "nextTurn")
        assertGameplayEquals("停止后跨回合", expected, session.game!!)
        assertEquals(stayed, own(session.game!!, id).getTile().position)
    }

    @Test fun enemyInWalkingDistanceInterruptsMovement() {
        val game = landGame { g ->
            add(g, "Warrior", 3, 3)
            val greece = enemy(g)
            if (!g.currentPlayerCiv.knows(greece)) g.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(greece)
            g.currentPlayerCiv.getDiplomacyManager(greece)!!.declareWar()
        }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        var expected = expectedFor(game)

        // 第1回合：路径暂无敌军，单位前进并挂 moveTo 指令。
        nativeMoveTo(expected, id, 3, -5)
        run(session, "moveTo", "unitId" to id, "x" to 3, "y" to -5)
        assertGameplayEquals("遇敌中断 起始", expected, session.game!!)
        assertEquals("moveTo 3,-5", own(session.game!!, id).action)
        val landed = own(session.game!!, id).getTile().position

        // 敌军进入行进距离：两端同步加入相邻敌军（朝向目的地一侧）。
        val ex = landed.x
        val ey = landed.y - 1
        for (g in listOf(expected, session.game!!)) native(g) { add(g, "Warrior", ex, ey, enemy(g)) }

        // 第2回合：原生 doAction 检测行进距离内敌军，停止移动指令且不再前进。
        expected = nextTurn(expected)
        run(session, "nextTurn")
        assertGameplayEquals("遇敌中断", expected, session.game!!)
        val stopped = own(session.game!!, id)
        assertNull("遇敌应清空移动指令", stopped.action)
        assertEquals("遇敌停止后不应继续前进", landed, stopped.getTile().position)
    }

    @Test fun saveReloadMidRouteContinuesToDestination() {
        val game = landGame { add(it, "Warrior", 3, 3) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        var expected = expectedFor(game)

        nativeMoveTo(expected, id, 3, -5)
        run(session, "moveTo", "unitId" to id, "x" to 3, "y" to -5)
        assertGameplayEquals("中途重载 起始", expected, session.game!!)

        // 中途保存并重载：moveTo 指令随原格式存档保留。
        val saved = run(session, "save", "name" to "unit-orders-midroute")
        run(session, "load", "path" to saved.text("savedPath"))
        expected = NavalFixtures.copy(expected)
        assertGameplayEquals("中途重载后", expected, session.game!!)
        assertEquals("moveTo 3,-5", own(session.game!!, id).action)

        var turns = 0
        while (own(session.game!!, id).getTile().position != HexCoord(3, -5) && turns < 8) {
            expected = nextTurn(expected)
            run(session, "nextTurn")
            assertGameplayEquals("中途重载续行 第${++turns}回合", expected, session.game!!)
        }
        assertEquals(HexCoord(3, -5), own(session.game!!, id).getTile().position)
        assertNull(own(session.game!!, id).action)
    }

    @Test fun illegalOrdersRejectBeforeAnyWrite() {
        val game = landGame { g ->
            add(g, "Warrior", 3, 3)
            add(g, "Fighter", 0, 0)
            val greece = enemy(g)
            if (!g.currentPlayerCiv.knows(greece)) g.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(greece)
            // 夹具默认希腊无单位；补一个希腊单位，供 NOT_OWNED 拒绝用例引用其 id。
            add(g, "Warrior", -5, 0, greece)
        }
        val session = sessionFor(game)
        val warrior = game.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" }.id
        val fighter = game.currentPlayerCiv.units.getCivUnits().first { it.name == "Fighter" }.id
        val foreign = enemy(session.game!!).units.getCivUnits().first().id

        // 只读预览与写命令都拒绝：空军、非己方、越界、坐标类型错误。
        for (command in listOf("unitRoute", "moveTo")) {
            reject(session, request(session, command, "unitId" to fighter, "x" to 3, "y" to -5), "UNSUPPORTED")
            reject(session, request(session, command, "unitId" to foreign, "x" to 3, "y" to -5), "NOT_OWNED")
            reject(session, request(session, command, "unitId" to warrior, "x" to 999, "y" to 0), "TARGET")
            reject(session, request(session, command, "unitId" to warrior, "x" to 3.5, "y" to -5), "INVALID_ARGUMENT")
        }
        // 行动力耗尽：拒绝移动。
        own(session.game!!, warrior).currentMovement = 0f
        for (command in listOf("unitRoute", "moveTo"))
            reject(session, request(session, command, "unitId" to warrior, "x" to 3, "y" to -5), "CANNOT_MOVE")
        own(session.game!!, warrior).currentMovement = 2f
        // 未在移动时不能停止；未知指令类型拒绝。
        reject(session, request(session, "unitOrder", "unitId" to warrior, "type" to "stopMovement"), "UNIT_ACTION")
        reject(session, request(session, "unitOrder", "unitId" to warrior, "type" to "nonsense"), "INVALID_ARGUMENT")
        // revision 不匹配的票据拒绝：本用例全程只拒绝、未写入，当前 revision 仍为 0，故用 +1 制造过期票据。
        reject(session, JsonObject(request(session, "moveTo", "unitId" to warrior, "x" to 3, "y" to -5)
            + ("revision" to JsonPrimitive(session.revision + 1))), "STALE_STATE")
    }

    @Test fun unreachableTargetRejectsWithoutFakeArrival() {
        // 目标及其全部邻格都是已探索山脉：邻格已知不可通行，getShortestPath 在源头短路判定无路，canReach 为假；
        // unitRoute 与 moveTo 均以 CANNOT_MOVE 拒绝，绝不伪报已抵达。
        // 注：未探索地块被乐观假设为可通行，单独一座山脉仍被视为“可达”，故必须让整圈邻格已知不可通行。
        val game = landGame { g ->
            add(g, "Warrior", 3, 3)
            val player = g.currentPlayerCiv
            val peak = g.tileMap[HexCoord(3, -5)]
            for (tile in listOf(peak) + peak.neighbors) {
                tile.baseTerrain = "Mountain"
                tile.setTerrainFeatures(emptyList())
                tile.tileResource = null
                tile.setTerrainTransients()
                tile.setExplored(player, true)
            }
        }
        val session = sessionFor(game)
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        assertFalse(native(game) { own(game, id).movement.canReach(game.tileMap[3, -5]) })
        for (command in listOf("unitRoute", "moveTo"))
            reject(session, request(session, command, "unitId" to id, "x" to 3, "y" to -5), "CANNOT_MOVE")
    }

    @Test fun routeIsReadOnlyAndMoveToIsIdempotentPerRequest() {
        val game = landGame { add(it, "Warrior", 3, 3) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)

        // unitRoute 反复查询零改动。
        val before = state(session.game!!)
        val revision = session.revision
        repeat(2) { run(session, "unitRoute", "unitId" to id, "x" to 3, "y" to -5) }
        assertEquals(before, state(session.game!!))
        assertEquals(revision, session.revision)

        // moveTo 双击：同一 requestId 重放返回缓存、状态不再变化；异参拒绝 REQUEST_REUSED。
        val command = request(session, "moveTo", "unitId" to id, "x" to 3, "y" to -5)
        val first = session.handle(command)
        assertTrue(first.toString(), first["ok"]!!.jsonPrimitive.boolean)
        assertEquals(revision + 1, session.revision)
        val after = state(session.game!!)
        assertEquals("重复 requestId 应返回缓存", first, session.handle(command))
        assertEquals(after, state(session.game!!))
        assertEquals(revision + 1, session.revision)
        val reused = session.handle(JsonObject(command + ("x" to JsonPrimitive(4))))
        assertEquals("REQUEST_REUSED", reused["error"]!!.jsonObject.text("code"))
        assertEquals(after, state(session.game!!))
        assertEquals(revision + 1, session.revision)
    }

    // —— T3.2 自动探索 / 自动工人：期望端只调用 core 的 UnitAutomation 与原生指令字段 ——

    /** 期望端复刻原生自动探索：挂 Explore 指令，有行动力则立即跑原生 automatedExplore。 */
    private fun nativeExplore(game: GameInfo, id: Int) = native(game) {
        val unit = own(game, id)
        unit.action = UnitActionType.Explore.value
        if (unit.hasMovement()) UnitAutomation.automatedExplore(unit)
    }

    /** 期望端复刻原生工人自动化：置 automated 并跑原生 automateUnitMoves（平民分支→WorkerAutomation）。 */
    private fun nativeAutomateWorker(game: GameInfo, id: Int) = native(game) {
        val unit = own(game, id)
        unit.automated = true
        UnitAutomation.automateUnitMoves(unit)
    }

    private fun nativeStopAutomation(game: GameInfo, id: Int) = native(game) {
        val unit = own(game, id)
        unit.action = null
        unit.automated = false
    }

    /** DevelopmentFixtures 场景的期望端推进：克隆 + nextTurn，与 WorkerCommandsTest.advance 同构。 */
    private fun advanceTurn(expected: GameInfo): GameInfo = native(expected) {
        val clone = expected.clone()
        clone.setTransients()
        UncivGame.Current.gameInfo = clone
        clone.nextTurn()
        clone
    }

    @Test fun exploreStartsAdvancesAndStops() {
        val game = landGame { add(it, "Warrior", 3, 3) }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        var expected = expectedFor(game)

        nativeExplore(expected, id)
        run(session, "unitOrder", "unitId" to id, "type" to "explore")
        assertGameplayEquals("自动探索 起始", expected, session.game!!)
        val exploringNow = own(session.game!!, id).isExploring()
        assertEquals("两端探索状态一致", own(expected, id).isExploring(), exploringNow)
        if (exploringNow) {
            // 已在探索时重复下达应拒绝（零写入）。
            reject(session, request(session, "unitOrder", "unitId" to id, "type" to "explore"), "UNIT_ACTION")
        }

        // 跨回合由原生 nextTurn/doAction 继续探索，每回合完整差分。
        repeat(2) {
            expected = nextTurn(expected)
            run(session, "nextTurn")
            assertGameplayEquals("自动探索 推进", expected, session.game!!)
        }

        // 停止探索清空指令；若已自然结束则 stopExploration 应拒绝。
        if (own(session.game!!, id).isExploring()) {
            nativeStop(expected, id)
            run(session, "unitOrder", "unitId" to id, "type" to "stopExploration")
            assertGameplayEquals("停止探索", expected, session.game!!)
            assertNull(own(session.game!!, id).action)
        } else {
            reject(session, request(session, "unitOrder", "unitId" to id, "type" to "stopExploration"), "UNIT_ACTION")
        }
    }

    @Test fun exploreWithNoUnknownTilesFinishesImmediately() {
        // 全图已探索：原生 automatedExplore 找不到未知格，立即结束探索并清空指令（不伪报仍在探索）。
        val game = landGame { g ->
            add(g, "Warrior", 3, 3)
            val player = g.currentPlayerCiv
            for (tile in g.tileMap.tileList) tile.setExplored(player, true)
        }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val session = sessionFor(game)
        val expected = expectedFor(game)

        nativeExplore(expected, id)
        run(session, "unitOrder", "unitId" to id, "type" to "explore")
        assertGameplayEquals("无可探索区域", expected, session.game!!)
        assertNull("无未知格时原生应结束探索并清空指令", own(session.game!!, id).action)
        reject(session, request(session, "unitOrder", "unitId" to id, "type" to "stopExploration"), "UNIT_ACTION")
    }

    @Test fun workerAutomationBuildsImprovementStopsAndSurvivesReload() {
        val file = DevelopmentFixtures.file("unit-orders-automate") { g ->
            DevelopmentFixtures.worker(g, DevelopmentFixtures.farm)
            // 预先与 Greece 建立联系并清理提示：自动工人随回合推进扩大视野时不再触发 FirstContact 待决事件阻断 nextTurn。
            val player = g.currentPlayerCiv
            val greece = g.civilizations.single { it.civName == "Greece" }
            if (!player.knows(greece)) player.diplomacyFunctions.makeCivilizationsMeet(greece)
            g.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear(); it.tradeRequests.clear() }
        }
        val session = BattleFixtures.load(file)
        var expected = UncivFiles.gameInfoFromString(file.readText())
        val id = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Worker" }.id

        nativeAutomateWorker(expected, id)
        run(session, "unitOrder", "unitId" to id, "type" to "automateWorker")
        assertGameplayEquals("自动工人 起始", expected, session.game!!)
        assertTrue("工人应处于自动化", own(session.game!!, id).isAutomated())

        // 记录原生 WorkerAutomation 选定的施工格（适配层不干预选地），推进到完工并逐回合差分。
        val workPos = own(session.game!!, id).getTile().position
        val inProgress = session.game!!.tileMap[workPos].improvementInProgress
        if (inProgress != null) {
            var turns = 0
            while (session.game!!.tileMap[workPos].improvement == null && turns < 10) {
                expected = advanceTurn(expected)
                run(session, "nextTurn")
                assertGameplayEquals("自动工人 第${++turns}回合", expected, session.game!!)
            }
            assertEquals("完工改良应与开工一致", inProgress, session.game!!.tileMap[workPos].improvement)
        }

        // 中途保存重载：automated 为原生字段随原格式存档保留，续行仍与原生一致。
        val saved = run(session, "save", "name" to "unit-orders-automate-mid")
        run(session, "load", "path" to saved.text("savedPath"))
        assertTrue("重载后应保持自动化", own(session.game!!, id).isAutomated())
        expected = advanceTurn(expected)
        run(session, "nextTurn")
        assertGameplayEquals("自动工人 重载续行", expected, session.game!!)

        // 停止自动化：清空 automated 与 action。
        nativeStopAutomation(expected, id)
        run(session, "unitOrder", "unitId" to id, "type" to "stopAutomation")
        assertGameplayEquals("停止自动化", expected, session.game!!)
        assertFalse(own(session.game!!, id).isAutomated())
        assertNull(own(session.game!!, id).action)
    }

    @Test fun automationRejections() {
        val game = landGame { g ->
            add(g, "Warrior", 3, 3)
            add(g, "Fighter", 0, 0)
            add(g, "Worker", 1, 1)
        }
        val session = sessionFor(game)
        val warrior = game.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" }.id
        val fighter = game.currentPlayerCiv.units.getCivUnits().first { it.name == "Fighter" }.id
        val worker = game.currentPlayerCiv.units.getCivUnits().first { it.name == "Worker" }.id

        // 军事单位与空军不得托管为自动工人；空军不得自动探索。
        reject(session, request(session, "unitOrder", "unitId" to warrior, "type" to "automateWorker"), "UNSUPPORTED")
        reject(session, request(session, "unitOrder", "unitId" to fighter, "type" to "automateWorker"), "UNSUPPORTED")
        reject(session, request(session, "unitOrder", "unitId" to fighter, "type" to "explore"), "UNSUPPORTED")
        // 空闲单位不得停止自动化／探索。
        reject(session, request(session, "unitOrder", "unitId" to warrior, "type" to "stopAutomation"), "UNIT_ACTION")
        reject(session, request(session, "unitOrder", "unitId" to warrior, "type" to "stopExploration"), "UNIT_ACTION")
        // 陆地施工单位行动力耗尽不得启动自动化（isLandWorker／非自动化检查先通过，才轮到 CANNOT_MOVE）。
        own(session.game!!, worker).currentMovement = 0f
        reject(session, request(session, "unitOrder", "unitId" to worker, "type" to "automateWorker"), "CANNOT_MOVE")
        own(session.game!!, worker).currentMovement = 2f

        // 工作船（水上）不开放自动工人。
        val naval = NavalFixtures.game(technology = "Astronomy").also { g -> native(g) { add(g, "Work Boats", 2, -2); NavalFixtures.ready(g) } }
        val navalSession = BattleFixtures.load(NavalFixtures.export(naval, "unit-orders-workboat"))
        val boat = navalSession.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Work Boats" }.id
        reject(navalSession, request(navalSession, "unitOrder", "unitId" to boat, "type" to "automateWorker"), "UNSUPPORTED")
    }
}
