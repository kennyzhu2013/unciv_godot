package com.unciv.godot

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.DevelopmentFixtures.native
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.WorkerImprovementActions
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 工人施工差分：期望端直接调用原生 [WorkerImprovementActions]／[UnitActionsFromUniques] 与 nextTurn，
 * 网关端走 workerOrder 命令；两端各自独立加载同一原生存档，逐步比较完整持久化状态。
 */
class WorkerCommandsTest {
    private class Native(var game: GameInfo)

    private fun workerOf(game: GameInfo): MapUnit = unit(game, "Worker")
    private fun improvement(game: GameInfo, name: String): TileImprovement = game.ruleset.tileImprovements[name]!!
    private fun tileOf(game: GameInfo, at: HexCoord): Tile = game.tileMap[at]

    /** 生成含一个工人的场景存档；网关与期望端各自独立加载。 */
    private fun scenario(name: String, at: HexCoord, extra: (GameInfo) -> Unit = {}): Pair<GameSession, Native> {
        val file = DevelopmentFixtures.file(name) { game ->
            DevelopmentFixtures.worker(game, at)
            extra(game)
        }
        return load(file) to Native(UncivFiles.gameInfoFromString(file.readText()))
    }

    /** 两端各推进一回合（工期由 Tile.doWorkerTurn 推进）并比较完整状态。 */
    private fun advance(session: GameSession, nat: Native, step: String) {
        native(nat.game) {
            val clone = nat.game.clone()
            clone.setTransients()
            UncivGame.Current.gameInfo = clone
            clone.nextTurn()
            nat.game = clone
        }
        run(session, "nextTurn")
        assertGameplayEquals(step, nat.game, session.game!!)
    }

    /** 两端把工人移动到同一地块（与 GameSessionTest.pairedCommand 的 move 一致）并比较。 */
    private fun moveTo(session: GameSession, nat: Native, to: HexCoord) {
        val own = workerOf(session.game!!)
        native(nat.game) {
            val expected = workerOf(nat.game)
            expected.action = null
            expected.movement.moveToTile(nat.game.tileMap[to])
        }
        run(session, "move", "unitId" to own.id, "x" to to.x, "y" to to.y)
        assertGameplayEquals("移动到 $to", nat.game, session.game!!)
    }

    /** 读取 unitOptions.worker 不得改动状态或 revision；随后两端执行同一施工动作并比较。 */
    private fun order(session: GameSession, nat: Native, step: String, type: String, name: String? = null) {
        val own = workerOf(session.game!!)
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        val worker = run(session, "unitOptions", "unitId" to own.id)["data"]!!.jsonObject["worker"]!!.jsonObject
        assertEquals("只读查询不得改动存档", before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals("只读查询不得推进 revision", revision, session.revision)
        native(nat.game) {
            val expected = workerOf(nat.game)
            when (type) {
                "repair" -> UnitActionsFromUniques.getRepairAction(expected)!!.action!!.invoke()
                "cancel" -> WorkerImprovementActions.accept(expected.currentTile, expected,
                    improvement(nat.game, Constants.cancelImprovementOrder))
                else -> WorkerImprovementActions.accept(expected.currentTile, expected, improvement(nat.game, name!!))
            }
        }
        val args = if (name == null) arrayOf<Pair<String, Any?>>("unitId" to own.id, "type" to type)
            else arrayOf<Pair<String, Any?>>("unitId" to own.id, "type" to type, "name" to name)
        run(session, "workerOrder", *args)
        assertGameplayEquals(step, nat.game, session.game!!)
        assertTrue(worker["supported"]!!.jsonPrimitive.boolean)
    }

    /** 生成固定 development.json（含己方工人与可管理城市）供 Godot smoke 发展流程消费；登记为测试输出。 */
    @Test fun emitDevelopmentScenarioForGodotSmoke() {
        val file = DevelopmentFixtures.file("development") { game ->
            DevelopmentFixtures.worker(game, DevelopmentFixtures.farm)
        }
        assertTrue("development.json 应写入测试输出目录", file.isFile && file.length() > 0)
    }

    /** 推进到当前工程结束（improvementInProgress 归零）；具体产出由调用方断言。 */
    private fun advanceUntilDone(session: GameSession, nat: Native, at: HexCoord, label: String) {
        var guard = 0
        while (tileOf(session.game!!, at).improvementInProgress != null && guard++ < 40)
            advance(session, nat, "$label 推进 $guard")
        assertTrue("工程应在有限回合内完成：$label", guard < 40)
        assertNull(tileOf(session.game!!, at).improvementInProgress)
        assertNull(tileOf(nat.game, at).improvementInProgress)
    }

    @Test fun farmStartsAndCompletesMatchingNative() {
        val (session, nat) = scenario("dev-farm", DevelopmentFixtures.farm)
        order(session, nat, "开始农场", "start", "Farm")
        assertEquals("Farm", tileOf(session.game!!, DevelopmentFixtures.farm).improvementInProgress)
        assertTrue(tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement > 0)
        advanceUntilDone(session, nat, DevelopmentFixtures.farm, "农场")
        assertEquals("Farm", tileOf(session.game!!, DevelopmentFixtures.farm).improvement)
    }

    @Test fun resourceImprovementConnectsWheat() {
        val (session, nat) = scenario("dev-resource", DevelopmentFixtures.resource)
        val options = run(session, "unitOptions", "unitId" to workerOf(session.game!!).id)["data"]!!.jsonObject
        val farmOption = options["worker"]!!.jsonObject["improvements"]!!.jsonArray.map { it.jsonObject }
            .first { it.text("name") == "Farm" }
        assertTrue(farmOption.toString(), farmOption["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("Wheat", farmOption.text("providesResource"))
        order(session, nat, "改良小麦", "start", "Farm")
        advanceUntilDone(session, nat, DevelopmentFixtures.resource, "小麦农场")
        assertEquals("Farm", tileOf(session.game!!, DevelopmentFixtures.resource).improvement)
        assertEquals("Wheat", tileOf(session.game!!, DevelopmentFixtures.resource).tileResource?.name)
    }

    @Test fun roadThenRailroadMatchNative() {
        val (session, nat) = scenario("dev-road", DevelopmentFixtures.road)
        order(session, nat, "开始道路", "start", "Road")
        advanceUntilDone(session, nat, DevelopmentFixtures.road, "道路")
        assertEquals(com.unciv.logic.map.tile.RoadStatus.Road, tileOf(session.game!!, DevelopmentFixtures.road).roadStatus)
        // 同一工人原地继续升级为铁路。
        order(session, nat, "开始铁路", "start", "Railroad")
        advanceUntilDone(session, nat, DevelopmentFixtures.road, "铁路")
        assertEquals(com.unciv.logic.map.tile.RoadStatus.Railroad, tileOf(session.game!!, DevelopmentFixtures.road).roadStatus)
    }

    @Test fun removeRoadIsRemovalMatchingNative() {
        val (session, nat) = scenario("dev-remove-road", DevelopmentFixtures.road)
        order(session, nat, "开始道路", "start", "Road")
        advanceUntilDone(session, nat, DevelopmentFixtures.road, "道路")
        order(session, nat, "拆除道路", "start", "Remove Road")
        advanceUntilDone(session, nat, DevelopmentFixtures.road, "拆除道路")
        assertEquals(com.unciv.logic.map.tile.RoadStatus.None, tileOf(session.game!!, DevelopmentFixtures.road).roadStatus)
    }

    @Test fun clearingForestGrantsProductionToNearestCity() {
        val (session, nat) = scenario("dev-forest", DevelopmentFixtures.forest) { game ->
            DevelopmentFixtures.city(game).cityConstructions.addToQueue(game.ruleset.buildings["Monument"]!!)
        }
        val before = DevelopmentFixtures.city(session.game!!).cityConstructions.getWorkDone("Monument")
        order(session, nat, "清除森林", "start", "Remove Forest")
        advanceUntilDone(session, nat, DevelopmentFixtures.forest, "清除森林")
        assertFalse("森林应被清除", tileOf(session.game!!, DevelopmentFixtures.forest).terrainFeatures.contains("Forest"))
        // 清林生产奖励计入城市当前项目：可能表现为进度增加，或项目因此完工。精确数值由 advance 内完整差分保证。
        val city = DevelopmentFixtures.city(session.game!!)
        val after = city.cityConstructions.getWorkDone("Monument")
        val completed = !city.cityConstructions.isBeingConstructedOrEnqueued("Monument")
        assertTrue("清林生产奖励应计入当前项目", completed || after > before)
    }

    @Test fun repairRestoresPillagedImprovementAndRoad() {
        val (session, nat) = scenario("dev-repair", DevelopmentFixtures.repair)
        val tile = tileOf(session.game!!, DevelopmentFixtures.repair)
        assertTrue("场景应含受损设施", tile.isPillaged())
        val repair = run(session, "unitOptions", "unitId" to workerOf(session.game!!).id)["data"]!!.jsonObject
        assertTrue(repair["worker"]!!.jsonObject["repair"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        order(session, nat, "修复改良", "repair")
        advanceUntilDone(session, nat, DevelopmentFixtures.repair, "修复改良")
        val repairedTile = tileOf(session.game!!, DevelopmentFixtures.repair)
        assertFalse("首次修复应修好改良", repairedTile.improvementIsPillaged)
        assertTrue("道路仍受损，需要再次修复", repairedTile.isPillaged())
        // setRepaired 每次只修一处；再次修复处理道路。
        order(session, nat, "修复道路", "repair")
        advanceUntilDone(session, nat, DevelopmentFixtures.repair, "修复道路")
        assertFalse("修复后不再受损", tileOf(session.game!!, DevelopmentFixtures.repair).isPillaged())
        assertFalse(tileOf(session.game!!, DevelopmentFixtures.repair).roadIsPillaged)
    }

    @Test fun reacceptingSameImprovementDoesNotRestartWork() {
        val (session, nat) = scenario("dev-same", DevelopmentFixtures.farm)
        order(session, nat, "开始农场", "start", "Farm")
        advance(session, nat, "推进一回合")
        val turnsLeft = tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement
        assertTrue(turnsLeft > 0)
        order(session, nat, "重复下达同名工程", "start", "Farm")
        assertEquals("同名续工不得重置工期", turnsLeft, tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement)
        assertEquals("Farm", tileOf(session.game!!, DevelopmentFixtures.farm).improvementInProgress)
    }

    @Test fun reselectingAnotherImprovementSwitchesWork() {
        val (session, nat) = scenario("dev-reselect", DevelopmentFixtures.farm)
        order(session, nat, "开始农场", "start", "Farm")
        val options = run(session, "unitOptions", "unitId" to workerOf(session.game!!).id)["data"]!!.jsonObject
        val other = options["worker"]!!.jsonObject["improvements"]!!.jsonArray.map { it.jsonObject }
            .first { it["enabled"]!!.jsonPrimitive.boolean && it.text("name") != "Farm" }.text("name")
        order(session, nat, "改选 $other", "start", other)
        assertEquals(other, tileOf(session.game!!, DevelopmentFixtures.farm).improvementInProgress)
    }

    @Test fun cancelStopsWorkWithoutRewakingOrRefunding() {
        val (session, nat) = scenario("dev-cancel", DevelopmentFixtures.farm)
        order(session, nat, "开始农场", "start", "Farm")
        order(session, nat, "取消施工", "cancel")
        assertNull(tileOf(session.game!!, DevelopmentFixtures.farm).improvementInProgress)
        assertNull(tileOf(session.game!!, DevelopmentFixtures.farm).improvement)
    }

    @Test fun movementAwayAndBackContinuesExistingWork() {
        val (session, nat) = scenario("dev-return", DevelopmentFixtures.farm)
        val away = HexCoord(1, 0)
        order(session, nat, "开始农场", "start", "Farm")
        advance(session, nat, "推进一回合")
        val turnsLeft = tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement
        // 工人离开地块：该回合不再推进工期，但地块工程与剩余工期保留。
        moveTo(session, nat, away)
        advance(session, nat, "离开后推进")
        assertEquals("离开期间不应推进工期", turnsLeft, tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement)
        // 返回并同名续工：不得重置工期。
        moveTo(session, nat, DevelopmentFixtures.farm)
        order(session, nat, "返回后续工", "start", "Farm")
        assertEquals(turnsLeft, tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement)
        advanceUntilDone(session, nat, DevelopmentFixtures.farm, "农场")
        assertEquals("Farm", tileOf(session.game!!, DevelopmentFixtures.farm).improvement)
    }

    @Test fun unfinishedWorkSurvivesSaveAndReload() {
        val (session, nat) = scenario("dev-persist", DevelopmentFixtures.farm)
        order(session, nat, "开始农场", "start", "Farm")
        advance(session, nat, "推进一回合")
        assertTrue(tileOf(session.game!!, DevelopmentFixtures.farm).turnsToImprovement > 0)
        val saved = run(session, "save", "name" to "test-development-worker")
        val reloaded = load(java.io.File(saved.text("savedPath")))
        assertGameplayEquals("未完成施工保存重载", nat.game, reloaded.game!!)
        assertEquals("Farm", tileOf(reloaded.game!!, DevelopmentFixtures.farm).improvementInProgress)
    }

    @Test fun illegalWorkerRequestsAreRejectedWithoutMutation() {
        val file = DevelopmentFixtures.file("dev-illegal") { game ->
            DevelopmentFixtures.worker(game, DevelopmentFixtures.farm)
        }
        val session = load(file)
        val game = session.game!!
        val worker = workerOf(game)
        fun rejected(command: JsonObject, code: String) {
            val before = UncivFiles.gameInfoToString(game, false)
            val revision = session.revision
            val result = session.handle(command)
            assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
            assertEquals(revision, session.revision)
            assertEquals(before, UncivFiles.gameInfoToString(game, false))
        }
        // 行动力耗尽不能施工。
        worker.currentMovement = 0f
        rejected(request(session, "workerOrder", "unitId" to worker.id, "type" to "start", "name" to "Farm"), "CANNOT_IMPROVE")
        worker.currentMovement = worker.getMaxMovement().toFloat()
        // 未知改良／未知类型／缺参数。
        rejected(request(session, "workerOrder", "unitId" to worker.id, "type" to "start", "name" to "NotAnImprovement"), "INVALID_ARGUMENT")
        rejected(request(session, "workerOrder", "unitId" to worker.id, "type" to "bogus", "name" to "Farm"), "INVALID_ARGUMENT")
        rejected(request(session, "workerOrder", "unitId" to worker.id, "type" to "repair"), "CANNOT_IMPROVE")
        // 无在建工程时取消被拒。
        rejected(request(session, "workerOrder", "unitId" to worker.id, "type" to "cancel"), "CANNOT_IMPROVE")
        // 非己方单位。
        rejected(request(session, "workerOrder", "unitId" to worker.id + 999999, "type" to "start", "name" to "Farm"), "NOT_OWNED")
    }

    @Test fun cityCenterAndNonWorkerUnitsAreNotSupported() {
        val file = DevelopmentFixtures.file("dev-center") { game ->
            DevelopmentFixtures.worker(game, DevelopmentFixtures.center)
            BattleFixtures.add(game, "Warrior", DevelopmentFixtures.farm.x, DevelopmentFixtures.farm.y)
        }
        val session = load(file)
        val game = session.game!!
        val worker = workerOf(game)
        val workerDto = run(session, "unitOptions", "unitId" to worker.id)["data"]!!.jsonObject["worker"]!!.jsonObject
        assertFalse("城市中心不能施工", workerDto["supported"]!!.jsonPrimitive.boolean)
        assertEquals("城市中心地块不能施工", workerDto.text("reason"))
        val warrior = unit(game, "Warrior")
        val warriorDto = run(session, "unitOptions", "unitId" to warrior.id)["data"]!!.jsonObject["worker"]!!.jsonObject
        assertFalse("非施工单位不支持", warriorDto["supported"]!!.jsonPrimitive.boolean)
        assertTrue(warriorDto["improvements"]!!.jsonArray.isEmpty())
    }

    @Test fun createsOneImprovementReservationBlocksOrders() {
        val file = DevelopmentFixtures.file("dev-reserved") { game ->
            DevelopmentFixtures.worker(game, DevelopmentFixtures.farm)
            tileOf(game, DevelopmentFixtures.farm).improvementFunctions.markForCreatesOneImprovement("Farm")
        }
        val session = load(file)
        val game = session.game!!
        val worker = workerOf(game)
        val before = UncivFiles.gameInfoToString(game, false)
        val result = session.handle(request(session, "workerOrder", "unitId" to worker.id, "type" to "start", "name" to "Farm"))
        assertEquals("CANNOT_IMPROVE", result["error"]!!.jsonObject.text("code"))
        assertEquals(before, UncivFiles.gameInfoToString(game, false))
        assertTrue("预留标记必须保留", tileOf(game, DevelopmentFixtures.farm).isMarkedForCreatesOneImprovement("Farm"))
    }
}
