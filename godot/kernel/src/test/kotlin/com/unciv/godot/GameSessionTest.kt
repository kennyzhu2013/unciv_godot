package com.unciv.godot

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

class GameSessionTest {
    companion object {
        private lateinit var root: File
        private lateinit var fixture: File
        /** 仓库自带的真实存档：G&K 规则、单个人类玩家、第 141 回合、处于战争；不是用户个人存档。 */
        private lateinit var screenshotSave: File

        @BeforeClass @JvmStatic fun setup() {
            root = File(System.getProperty("unciv.root"))
            KernelRuntime.initialize(root)
            fixture = File(root, "godot/.local/tests/start.json")
            fixture.parentFile.mkdirs()
            fixture.writeText(UncivFiles.gameInfoToString(KernelRuntime.createDemo(), false))
            screenshotSave = File(root, "extraImages/Screenshots/ScreenshotGenerationGame")
        }
    }
    private fun request(session: GameSession, action: String, vararg params: Pair<String, Any?>) = dto(
        "protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
        "requestId" to UUID.randomUUID().toString(), "action" to action, *params)
    private fun run(session: GameSession, action: String, vararg params: Pair<String, Any?>): JsonObject {
        val response = session.handle(request(session, action, *params))
        assertEquals(response.toString(), true, response["ok"]!!.jsonPrimitive.boolean)
        return response
    }
    private fun loadedSession(file: File = fixture) = GameSession(root).also {
        run(it, "load", "path" to file.absolutePath)
    }

    private fun <T> withNativeGame(game: GameInfo, operation: () -> T): T {
        val previous = UncivGame.Current.gameInfo
        UncivGame.Current.gameInfo = game
        return try { operation() } finally { UncivGame.Current.gameInfo = previous }
    }

    private fun promiseFixture(): File {
        val game = UncivFiles.gameInfoFromString(fixture.readText())
        withNativeGame(game) {
            val player = game.currentPlayerCiv
            val other = game.civilizations.single { it.isMajorCiv() && it.isAI() }
            val settlerTile = player.units.getCivUnits().first { it.name == "Settler" }.currentTile
            val otherCityTile = game.tileMap.tileList.first {
                it.aerialDistanceTo(settlerTile) == 5 && it.canBeSettled(other) && it.getUnits().none()
            }
            other.addCity(otherCityTile.position)
            if (!player.knows(other)) player.diplomacyFunctions.makeCivilizationsMeet(other)
            otherCityTile.setExplored(other, true)
            settlerTile.setExplored(other, true)
            // 承诺标记属于被承诺方；先建立外交关系，避免随后被相遇初始化覆盖。
            other.getDiplomacyManager(player)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 100)
            assertTrue("承诺地点仍符合普通建城规则", settlerTile.canBeSettled(player))
        }
        return File(root, "godot/.local/tests/settlement-promise.json").apply {
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    @Test fun settlementPromiseRequiresConfirmationWithoutMutation() {
        val session = loadedSession(promiseFixture())
        val original = session.game!!
        val settler = original.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        val before = UncivFiles.gameInfoToString(original, false)
        val revision = session.revision
        val options = run(session, "unitOptions", "unitId" to settler.id)["data"]!!.jsonObject
        assertTrue(options["canFound"]!!.jsonPrimitive.boolean)
        val unconfirmed = request(session, "foundCity", "unitId" to settler.id)
        val rejected = session.handle(unconfirmed)
        assertFalse(rejected["ok"]!!.jsonPrimitive.boolean)
        assertEquals("CONFIRM_PROMISE", rejected["error"]!!.jsonObject.text("code"))
        assertEquals(rejected, session.handle(unconfirmed))
        val cancelled = session.handle(request(session, "foundCity", "unitId" to settler.id, "confirmPromise" to false))
        assertEquals("CONFIRM_PROMISE", cancelled["error"]!!.jsonObject.text("code"))
        // 前端取消时不再发命令；后续查询也不能提交之前被拒绝的建城。
        run(session, "snapshot")
        assertEquals(revision, session.revision)
        // 被拒绝的命令会换回命令前备份，对象可以不同，但持久化状态必须与命令前完全一致。
        assertSame(session.game, UncivGame.Current.gameInfo)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertNull(Gdx.app)
        assertNull(UncivGame.Current.worldScreen)
    }

    @Test fun confirmedSettlementMatchesNativeActionAndIsIdempotent() {
        val file = promiseFixture()
        val native = UncivFiles.gameInfoFromString(file.readText())
        val session = loadedSession(file)
        val settler = native.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        val position = settler.currentTile.position
        val other = native.civilizations.single { it.isMajorCiv() && it.isAI() }
        val before = UncivFiles.gameInfoToString(native, false)
        var confirmations = 0
        withNativeGame(native) {
            // 原动作取消回调不执行提交；确认回调才执行原建城闭包。
            UnitActionsFromUniques.getFoundCityAction(settler, settler.currentTile) { leaders, _ ->
                confirmations++
                assertTrue(leaders.contains(other.getLeaderDisplayName()))
            }!!.action!!()
            assertEquals(before, UncivFiles.gameInfoToString(native, false))
            UnitActionsFromUniques.getFoundCityAction(settler, settler.currentTile) { leaders, commit ->
                confirmations++
                assertTrue(leaders.contains(other.getLeaderDisplayName()))
                commit()
            }!!.action!!()
        }
        assertEquals(2, confirmations)
        val revision = session.revision
        val confirmed = request(session, "foundCity", "unitId" to settler.id, "confirmPromise" to true)
        val response = session.handle(confirmed)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        val committed = session.game!!
        assertGameplayEquals("承诺确认建城", native, committed)
        val city = committed.currentPlayerCiv.cities.single()
        assertEquals(position, city.location)
        assertTrue(city.isOriginalCapital)
        assertTrue(city.isCapital())
        assertFalse(committed.currentPlayerCiv.units.getCivUnits().any { it.id == settler.id })
        val diplomacy = committed.getCivilization(other.civID).getDiplomacyManager(committed.currentPlayerCiv)!!
        assertTrue("保留承诺，待原外交流程结算", diplomacy.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        assertTrue("记录附近建城事件", diplomacy.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
        assertEquals(response, session.handle(confirmed))
        assertSame(committed, session.game)
        assertEquals(revision + 1, session.revision)
        val saved = run(session, "save", "name" to "test-promise-roundtrip")
        run(session, "load", "path" to saved.text("savedPath"))
        assertGameplayEquals("承诺建城后保存重载", native, session.game!!)
        assertNull(Gdx.app)
        assertNull(UncivGame.Current.worldScreen)
    }

    @Test fun sameSaveAndCommandsMatchNativeCoreForFifteenTurns() {
        val native = Native(UncivFiles.gameInfoFromString(fixture.readText()))
        val session = loadedSession()
        assertNotSame(native.game, session.game)
        assertGameplayEquals("同一初始存档", native.game, session.game!!)
        val warrior = native.game.currentPlayerCiv.units.getCivUnits().first { it.isMilitary() }
        val target = warrior.currentTile.neighbors.first { warrior.movement.canMoveTo(it) }.position
        pairedCommand(session, native, "move", "unitId" to warrior.id, "x" to target.x, "y" to target.y)
        val settler = native.game.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        pairedCommand(session, native, "foundCity", "unitId" to settler.id)
        val cityId = native.game.currentPlayerCiv.cities.single().id
        pairedCommand(session, native, "production", "cityId" to cityId, "name" to "Warrior")
        pairedCommand(session, native, "production", "cityId" to cityId, "name" to "Monument")
        pairedCommand(session, native, "production", "cityId" to cityId, "name" to "Warrior")
        assertEquals(listOf("Warrior", "Monument"), native.game.currentPlayerCiv.cities.single().cityConstructions.constructionQueue)
        pairedCommand(session, native, "research", "name" to "Pottery")
        for (turn in 1..15) {
            resolveSupportedDecisions(session, native)
            pairedCommand(session, native, "nextTurn")
            assertEquals(turn, session.game!!.turns)
            if (turn % 5 == 0) {
                val saved = run(session, "save", "name" to "test-native-differential")
                assertGameplayEquals("第 $turn 回合保存", native.game, session.game!!)
                // 两端各自序列化、各自重载，不能将网关结果当作原生端的新基准。
                native.game = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(native.game, true))
                run(session, "load", "path" to saved.text("savedPath"))
                assertGameplayEquals("第 $turn 回合独立重载", native.game, session.game!!)
            }
        }
        assertTrue(native.game.civilizations.any { it.isAI() && it.cities.isNotEmpty() })
        assertTrue(native.game.currentPlayerCiv.tech.techsResearched.contains("Pottery"))
        assertTrue(native.game.currentPlayerCiv.units.getCivUnits().count() > 1)
        assertNull(Gdx.app)
        assertNull(Gdx.gl)
        assertNull(Gdx.audio)
        println("原生／网关差分通过：移动、建城、生产队列、科研、决策、15 回合及独立保存重载")
    }

    @Test fun realMidGameSaveMatchesNativeCoreForFiveTurns() {
        val native = Native(UncivFiles.gameInfoFromString(screenshotSave.readText(Charsets.UTF_8)))
        val session = loadedSession(screenshotSave)
        val player = session.game!!.currentPlayerCiv
        assertTrue(player.isHuman() && !player.isSpectator() && player.cities.isNotEmpty())
        assertTrue("应为已推进多回合的中期局面", session.game!!.turns >= 100)
        assertTrue("应处于战争中的中期局面", player.isAtWar())
        assertTrue("应包含宗教与城邦", session.game!!.religions.isNotEmpty() && session.game!!.civilizations.any { it.isCityState })
        assertGameplayEquals("真实存档加载", native.game, session.game!!)
        val snapshot = run(session, "snapshot")["snapshot"]!!.jsonObject
        assertEquals(player.cities.size, snapshot["cities"]!!.jsonArray.count { it.jsonObject["own"]!!.jsonPrimitive.boolean })
        val start = session.game!!.turns
        val handled = mutableListOf<String>()
        for (step in 1..5) {
            handled += resolveSupportedDecisions(session, native)
            pairedCommand(session, native, "nextTurn")
            assertEquals(start + step, session.game!!.turns)
        }
        assertTrue("中期局面应至少处理过生产与提示决策：$handled", handled.contains("production") && handled.contains("alert"))
        val saved = run(session, "save", "name" to "test-real-save")
        assertNotEquals("不覆盖仓库原存档", screenshotSave.absolutePath, saved.text("savedPath"))
        assertEquals(native.game.gameId, UncivFiles.gameInfoFromString(screenshotSave.readText(Charsets.UTF_8)).gameId)
        native.game = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(native.game, true))
        run(session, "load", "path" to saved.text("savedPath"))
        assertGameplayEquals("真实存档独立保存重载", native.game, session.game!!)
        assertNull(Gdx.app)
        println("真实中期存档差分通过：第 $start → ${start + 5} 回合，含战争、宗教与城邦；处理决策 ${handled.groupingBy { it }.eachCount()}")
    }

    /** 原生对照端：与 WorldScreen.nextTurn 一致，回合推进在 clone()+setTransients() 的副本上执行，之后以副本为当前局。 */
    private class Native(var game: GameInfo)

    private fun pairedCommand(session: GameSession, native: Native, action: String, vararg params: Pair<String, Any?>) {
        val args = dto(*params)
        withNativeGame(native.game) {
            val civ = native.game.currentPlayerCiv
            when (action) {
                "move" -> {
                    val unit = civ.units.getCivUnits().first { it.id == args.integer("unitId") }
                    unit.action = null
                    unit.movement.moveToTile(native.game.tileMap[HexCoord(args.integer("x"), args.integer("y"))])
                }
                "foundCity" -> {
                    val unit = civ.units.getCivUnits().first { it.id == args.integer("unitId") }
                    UnitActionsFromUniques.getFoundCityAction(unit, unit.currentTile) { _, _ ->
                        fail("普通差分开局不应有建城承诺")
                    }!!.action!!()
                }
                "production" -> {
                    val city = civ.cities.first { it.id == args.text("cityId") }
                    val construction: com.unciv.models.ruleset.IConstruction =
                        native.game.ruleset.units[args.text("name")] ?: native.game.ruleset.buildings[args.text("name")]!!
                    val existing = city.cityConstructions.constructionQueue.indexOf(construction.name)
                    if (existing >= 0) city.cityConstructions.moveEntryToTop(existing)
                    else city.cityConstructions.addToQueue(construction, addToTop = true)
                    city.reassignPopulation()
                    city.cityStats.update()
                }
                "research" -> {
                    if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology(args.text("name"))
                    else civ.tech.techsToResearch = arrayListOf(args.text("name"))
                }
                "policy" -> civ.policies.adopt(native.game.ruleset.policies[args.text("name")]!!)
                "acknowledge" -> civ.popupAlerts.removeAt(0)
                "diplomacyAlertDecision" -> DiplomacyFixtures.alertDecision(native.game, args.text("choice"))
                "declineTrade" -> {
                    // 与 TradePopup 的“Not this time.”按钮逐行一致。
                    val tradeRequest = civ.tradeRequests.first()
                    val requestingCiv = native.game.getCivilization(tradeRequest.requestingCiv)
                    tradeRequest.decline(civ)
                    civ.tradeRequests.remove(tradeRequest)
                    requestingCiv.addNotification("[${civ.civName}] has denied your trade request",
                        NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
                }
                "nextTurn" -> {
                    val clone = native.game.clone()
                    clone.setTransients()
                    UncivGame.Current.gameInfo = clone
                    clone.nextTurn()
                    native.game = clone
                }
                else -> error("原生对照尚未定义操作：$action")
            }
        }
        run(session, action, *params)
        assertGameplayEquals("第 ${native.game.turns} 回合 $action $args", native.game, session.game!!)
    }

    @Test fun loadMoveFoundProduceTurnSaveReload() {
        val session = loadedSession()
        assertNull("无 LibGDX Application", Gdx.app)
        assertNull("无 GL 上下文", Gdx.gl)
        val initial = session.game!!
        val warrior = initial.currentPlayerCiv.units.getCivUnits().first { it.isMilitary() }
        val reachable = run(session, "unitOptions", "unitId" to warrior.id)["data"]!!.jsonObject["reachable"]!!.jsonArray
        val tile = reachable.map { it.jsonObject }.first { it.integer("x") != warrior.currentTile.position.x || it.integer("y") != warrior.currentTile.position.y }
        val movementBefore = warrior.currentMovement
        run(session, "move", "unitId" to warrior.id, "x" to tile.integer("x"), "y" to tile.integer("y"))
        val moved = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.id == warrior.id }
        assertEquals(tile.integer("x"), moved.currentTile.position.x)
        assertTrue(moved.currentMovement < movementBefore)
        val settler = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        run(session, "foundCity", "unitId" to settler.id)
        val city = session.game!!.currentPlayerCiv.cities.single()
        assertFalse(session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == settler.id })
        run(session, "production", "cityId" to city.id, "name" to "Warrior")
        run(session, "research", "name" to "Pottery")
        while (session.game!!.currentPlayerCiv.popupAlerts.isNotEmpty()) run(session, "acknowledge")
        val beforeTurn = session.game!!.turns
        run(session, "nextTurn")
        assertEquals(beforeTurn + 1, session.game!!.turns)
        assertTrue("AI 应按原规则建立城市", session.game!!.civilizations.any { it.isAI() && it.cities.isNotEmpty() })
        assertTrue("生产应累计", session.game!!.currentPlayerCiv.cities.single().cityConstructions.getWorkDone("Warrior") > 0)
        val saved = run(session, "save", "name" to "test-roundtrip")
        val path = File(saved.text("savedPath"))
        assertFalse("使用原压缩格式", path.readText().startsWith('{'))
        val nativeReload = UncivFiles.gameInfoFromString(path.readText())
        val before = gameplay(session.game!!)
        assertEquals(before, gameplay(nativeReload))
        run(session, "load", "path" to path.absolutePath)
        assertEquals(before, gameplay(session.game!!))
        println("闭环通过：原存档 → 移动 → 建城 → 生产/科研 → AI 回合 → 原格式保存重载")
    }

    @Test fun fifteenTurnsWithExplicitDecisionsAndPeriodicReload() {
        val session = loadedSession()
        val settler = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        run(session, "foundCity", "unitId" to settler.id)
        for (expectedTurn in 1..15) {
            resolveSupportedDecisions(session)
            run(session, "nextTurn")
            assertEquals(expectedTurn, session.game!!.turns)
            assertNull("连续回合不创建 LibGDX Application", Gdx.app)
            assertNull("连续回合不创建音频上下文", Gdx.audio)
            if (expectedTurn % 5 == 0) {
                val before = gameplay(session.game!!)
                val saved = run(session, "save", "name" to "test-multiturn")
                run(session, "load", "path" to saved.text("savedPath"))
                assertEquals("第 $expectedTurn 回合重载保持状态", before, gameplay(session.game!!))
            }
        }
        assertTrue("AI 持续经营城市", session.game!!.civilizations.any { it.isAI() && it.cities.isNotEmpty() })
        assertTrue("生产已完成并生成单位", session.game!!.currentPlayerCiv.units.getCivUnits().count() > 1)
        println("连续 15 回合通过：显式处理决策，每 5 回合保存重载，无图形与音频上下文")
    }

    /** 按前端会看到的 pending 列表逐项处理已接入的决策；返回实际处理过的决策类型，便于确认路径确实被走到。 */
    private fun resolveSupportedDecisions(session: GameSession, native: Native? = null): List<String> {
        val handled = mutableListOf<String>()
        fun decide(action: String, vararg params: Pair<String, Any?>) {
            if (native == null) run(session, action, *params)
            else pairedCommand(session, native, action, *params)
        }
        repeat(40) {
            val snapshot = run(session, "snapshot")["snapshot"]!!.jsonObject
            val pending = snapshot["pending"]!!.jsonArray.map { it.jsonObject }
            if (pending.isEmpty()) return handled
            val decision = pending.first()
            assertTrue("测试遇到尚未接入的决策：$decision", decision["supported"]!!.jsonPrimitive.boolean)
            handled += decision.text("kind")
            when (decision.text("kind")) {
                "production" -> {
                    val city = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.first {
                        it["own"]!!.jsonPrimitive.boolean && it.text("production").isEmpty()
                    }
                    // 中期存档里战士已淘汰，按城市当前可生产项选择，与前端列表一致。
                    val enabled = run(session, "cityOptions", "cityId" to city.text("id"))["data"]!!.jsonObject["constructions"]!!
                        .jsonArray.map { it.jsonObject }.filter { it["enabled"]!!.jsonPrimitive.boolean }.map { it.text("name") }
                    decide("production", "cityId" to city.text("id"), "name" to (enabled.firstOrNull { it == "Warrior" } ?: enabled.first()))
                }
                "research" -> decide("research", "name" to snapshot["technologies"]!!.jsonArray.first().jsonPrimitive.content)
                "policy" -> decide("policy", "name" to snapshot["policies"]!!.jsonArray.first().jsonPrimitive.content)
                "alert" -> decide("acknowledge")
                "diplomacyAlert" -> {
                    val alert = run(session, "diplomacyOptions")["data"]!!.jsonObject["pendingAlert"]!!.jsonObject
                    val choices = alert["choices"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("enabled") }
                    val choice = choices.firstOrNull { it.text("id") in listOf("decline", "dismiss", "agree") } ?: choices.first()
                    decide("diplomacyAlertDecision", "alertToken" to alert.text("alertToken"), "choice" to choice.text("id"))
                }
                "trade" -> decide("declineTrade")
                else -> fail("未处理的决策：$decision")
            }
        }
        fail("决策未在有限次数内处理完成")
        return handled
    }

    @Test fun duplicateStaleAndInvalidCommandsDoNotMutateGame() {
        val session = loadedSession()
        val save = request(session, "save", "name" to "test-dedup")
        val first = session.handle(save)
        assertTrue(first["ok"]!!.jsonPrimitive.boolean)
        assertEquals(first, session.handle(save))
        assertEquals("REQUEST_REUSED", session.handle(JsonObject(save + ("name" to JsonPrimitive("other"))))["error"]!!.jsonObject.text("code"))
        val stale = JsonObject(request(session, "nextTurn") + ("revision" to JsonPrimitive(0)))
        assertEquals("STALE_STATE", session.handle(stale)["error"]!!.jsonObject.text("code"))
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val version = session.revision
        val enemy = session.game!!.civilizations.first { it.isAI() && it.units.getCivUnits().any() }.units.getCivUnits().first()
        val response = session.handle(request(session, "move", "unitId" to enemy.id, "x" to 0, "y" to 0))
        assertEquals("NOT_OWNED", response["error"]!!.jsonObject.text("code"))
        assertFalse(session.handle(request(session, "save", "name" to "../outside"))["ok"]!!.jsonPrimitive.boolean)
        assertFalse(session.handle(request(session, "load", "path" to "missing-file"))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(version, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
    }

    @Test fun snapshotDoesNotExposeUnexploredTilesOrHiddenEnemies() {
        val session = loadedSession()
        val response = run(session, "snapshot")["snapshot"]!!.jsonObject
        val unknown = response["tiles"]!!.jsonArray.map { it.jsonObject }.filter { it.text("visibility") == "unknown" }
        assertTrue(unknown.isNotEmpty())
        assertTrue(unknown.all { it.keys == setOf("x", "y", "visibility") })
        val allowedUnits = session.game!!.tileMap.tileList.flatMap { it.getUnits().toList() }
            .filter { it.isVisibleTo(session.game!!.currentPlayerCiv) }.map { it.id }.toSet()
        assertTrue(response["units"]!!.jsonArray.all { it.jsonObject.integer("id") in allowedUnits })
        assertTrue(response["cities"]!!.jsonArray.isEmpty())
    }

    @Test fun snapshotExposesOwnConstructionAndDamageWithoutLeakingProgress() {
        // 发展场景：己方工人在农场地块开工，另有受损设施地块；验证快照补充的可见态字段。
        val file = DevelopmentFixtures.file("snapshot-dev") { game ->
            DevelopmentFixtures.worker(game, DevelopmentFixtures.farm)
        }
        val session = loadedSession(file)
        val worker = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Worker" }.id
        run(session, "workerOrder", "unitId" to worker, "type" to "start", "name" to "Farm")
        val tiles = run(session, "snapshot")["snapshot"]!!.jsonObject["tiles"]!!.jsonArray.map { it.jsonObject }
        val farm = tiles.first { it.integer("x") == DevelopmentFixtures.farm.x && it.integer("y") == DevelopmentFixtures.farm.y }
        assertEquals("Farm", farm.text("improvementInProgress"))
        assertTrue("己方施工应报告剩余回合", farm.integer("turnsToImprovement") > 0)
        val repair = tiles.first { it.integer("x") == DevelopmentFixtures.repair.x && it.integer("y") == DevelopmentFixtures.repair.y }
        assertTrue("受损改良应在快照中标记", repair["pillaged"]!!.jsonPrimitive.boolean)
        // 未探索地块只携带坐标与可见性，不得泄漏任何新增字段。
        val unknown = tiles.filter { it.text("visibility") == "unknown" }
        assertTrue(unknown.isNotEmpty())
        assertTrue(unknown.all { it.keys == setOf("x", "y", "visibility") })
        // 已探索但当前不可见的地块不得携带施工进度或受损态（避免泄漏敌方工程）。
        assertTrue(tiles.filter { it.text("visibility") == "explored" }.all {
            it["improvementInProgress"] is JsonNull && it["pillaged"] is JsonNull && it["turnsToImprovement"] is JsonNull
        })
    }

    @Test fun snapshotHidesForeignConstructionProgressOnVisibleTiles() {
        // 敌方在建地块对己方可见，但不属于己方、也没有己方施工单位；施工进度与工期不得泄漏。
        val file = DevelopmentFixtures.file("snapshot-enemy-dev") { game ->
            DevelopmentFixtures.enemyConstruction(game)
        }
        val session = loadedSession(file)
        val tiles = run(session, "snapshot")["snapshot"]!!.jsonObject["tiles"]!!.jsonArray.map { it.jsonObject }
        val enemy = tiles.first {
            it.integer("x") == DevelopmentFixtures.enemyWork.x && it.integer("y") == DevelopmentFixtures.enemyWork.y
        }
        assertEquals("敌方在建地块应对己方可见", "visible", enemy.text("visibility"))
        assertTrue("可见敌方地块不得泄漏在建工程", enemy["improvementInProgress"] is JsonNull)
        assertTrue("可见敌方地块不得泄漏剩余工期", enemy["turnsToImprovement"] is JsonNull)
    }

    @Test fun turnIsBlockedUntilDecisionsAreResolved() {
        val session = loadedSession()
        val result = session.handle(request(session, "nextTurn"))
        assertEquals("PENDING_DECISION", result["error"]!!.jsonObject.text("code"))
        assertEquals(0, session.game!!.turns)
    }

    /**
     * 会话级接线守护：religionOptions 经只读分支返回且不推进 revision；宗教待决经通用 snapshot.pending() 阻塞 nextTurn；
     * religionFound 经 preparedAction／execute／inPlaceCommands 事务写入，与独立原生期望全存档一致且重放不重复写入。
     * 期望端仅用原生桥接与 chooseBeliefs，不经 ReligionCommands／DTO。
     */
    @Test fun religionCommandsRouteThroughSessionTransactionAndGateNextTurn() {
        val file = ReligionFixtures.export(ReligionFixtures.foundingGame(true), "session-religion")
        val session = loadedSession(file)
        val expected = UncivFiles.gameInfoFromString(file.readText())

        assertEquals("PENDING_DECISION", session.handle(request(session, "nextTurn"))["error"]!!.jsonObject.text("code"))

        val game = session.game!!
        val revision = session.revision
        val options = run(session, "religionOptions")["data"]!!.jsonObject
        assertSame(game, session.game)
        assertEquals(revision, session.revision)
        val decision = options["decision"]!!.jsonObject
        assertEquals("foundReligion", decision.text("mode"))

        val symbol = options["symbols"]!!.jsonArray.map { it.jsonObject }.first { it.boolean("available") }.text("id")
        val used = HashSet<String>()
        val beliefs = decision["slots"]!!.jsonArray.map { slot ->
            slot.jsonObject["candidateIds"]!!.jsonArray.map { it.jsonPrimitive.content }.first { used.add(it) }
        }
        ReligionFixtures.foundReligion(expected, "会话信仰", symbol, beliefs)

        val body = request(session, "religionFound", "decisionToken" to decision.text("token"),
            "religionId" to symbol, "displayName" to "会话信仰", "beliefs" to beliefs)
        val response = session.handle(body)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertEquals(revision + 1, session.revision)
        assertSame(game, session.game)
        assertGameplayEquals("religionFound 会话事务", expected, session.game!!)

        assertEquals(response, session.handle(body))
        assertEquals(revision + 1, session.revision)
        assertGameplayEquals("religionFound 重放", expected, session.game!!)
    }

    private fun gameplay(game: GameInfo) = GameplayAssertions.gameplay(game)
    private fun assertGameplayEquals(step: String, expected: GameInfo, actual: GameInfo) =
        GameplayAssertions.assertGameplayEquals(step, expected, actual)
}
