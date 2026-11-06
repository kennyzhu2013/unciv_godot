package com.unciv.godot

import com.unciv.godot.BattleFixtures.run
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 第 8B 批（t13 科学／海洋窗口复用前置）：验证 main-source [AutoplayDriver] 的 **science／naval 路线参数**
 * 能从真实 newGame 一路打到原生胜负结算，且驱动全程状态可持久还原（save→独立原生 load 往返一致）。
 *
 * 与 [AutoplayGameTest]（land 路线）同构，唯一差别是把 [AutoplayDriver.playTurn] 的 route 参数换成
 * "science"／"naval"，并镜像内核 [FullGameRoutesTest]．Science／．Naval 的同源场景（Babylon／England、
 * Quick、Small、Archipelago、固定种子）与验收语义：
 * 1. 只走真实网关命令（子请求经 [GameSession.handle]），不注入胜利、不清空待决、不改原生胜利条件；
 * 2. 任意检查点存档后“网关重载 vs 独立原生重载”完全一致（[GameplayAssertions]），证明 autoplay
 *    命令序列不留半提交状态——这是真实窗口 HTTP 驱动可以安全复用同一 driver 的前提；
 * 3. 原生结算出现后可按原生语义 victoryContinue 续玩并保留 oneMoreTurnMode。
 *
 * 验收按路线分支（与 FullGameRoutesTest.verifyRoute 同口径，玩家胜负非确定——core AI 无种子 shuffle）：
 * - science：深入科技树（峰值 ≥35 项）+ 真实建成科学建筑（峰值 ≥2 座）；仅当玩家以 Domination
 *   获胜才追加占城链路断言，和平发展到 Scientific／Time 或被 AI 征服均属原生结算。
 * - naval：真实海上改良（waterImprovements 峰值 ≥1）+ 海军单位（navalUnits 峰值 ≥1）+ 登船跨海
 *   （embarkedUnits 峰值 ≥1）；若玩家以 Domination 获胜须真实进入过战争。
 *
 * 覆盖边界与 [AutoplayGameTest] 相同：`autoplay` 传输动词外壳（UNCIV_SMOKE 门控 + route 透传）在 gradle
 * 测试进程中门控关闭（见 [autoplayRouteVerbIsGatedOffWithoutSmokeEnv]），本测试直接实例化同一 driver 并
 * 传入路线名，忠实复刻窗口“每回合 HTTP 调用一次持久 driver”的语义；动词外壳 route 透传由真实窗口 Smoke
 * （run.ps1 -Smoke 设 UNCIV_SMOKE=1，t13d／t13e）端到端覆盖。
 */
class AutoplayRouteTest {
    companion object {
        private val root = File(System.getProperty("unciv.root"))
        // Quick 速度原生 500 回合即触发 Time 结算，取 560 为守卫上界（FullGameRoutesTest 实测两路线
        // 在 ~500 回合内收敛），只接受原生结算、不接受因超限而“人为截断”的局。
        private const val TURN_LIMIT = 560
        private const val CHECK_EVERY = 40

        @BeforeClass @JvmStatic fun setUp() { KernelRuntime.initialize(root) }
    }

    /** 与 debugInjectEvent／autoplay-land 同口径的门控证明：未设 UNCIV_SMOKE 时带 route 的动词请求等同不存在。 */
    @Test fun autoplayRouteVerbIsGatedOffWithoutSmokeEnv() {
        val session = GameSession(root, File(root, "godot/.local/tests/full-game/autoplay-route-gate"))
        val request = dto("protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
            "requestId" to UUID.randomUUID().toString(), "action" to "autoplay", "route" to "science")
        val response = session.handle(request)
        assertFalse("未设 UNCIV_SMOKE 时 autoplay 动词必须关闭", response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("UNKNOWN_COMMAND", response["error"]!!.jsonObject.text("code"))
    }

    @Test fun autoplayScienceRouteReachesNativeSettlement() = RouteDrive("science").play()
    @Test fun autoplayNavalRouteReachesNativeSettlement() = RouteDrive("naval").play()

    /** 单条路线的连续会话驱动，与 AutoplayGameTest.Autoplay 同构，仅场景／路线／验收不同。 */
    private inner class RouteDrive(private val route: String) {
        private val name = "autoplay-$route"
        private val directory = File(root, "godot/.local/tests/full-game/$name")
        private val report = File(root, "godot/.local/tests/full-game/$name.json")
        private val started = System.nanoTime()
        private val session = GameSession(root, directory)
        private val driver = AutoplayDriver(session)
        private val checkpoints = ArrayList<JsonObject>()
        private var cumulativeActions = 0
        private var everWar = false
        private var maxCities = 0
        private var maxTechnologies = 0
        private var maxScienceBuildings = 0
        private var maxWaterImprovements = 0
        private var maxNavalUnits = 0
        private var maxEmbarkedUnits = 0

        fun setup(): JsonObject = when (route) {
            // 与 FullGameRoutesTest.Science 完全同场景（群岛长期和平发展，被偷袭时防御反击）。
            "science" -> dto("nation" to "Babylon", "difficulty" to "Chieftain", "speed" to "Quick",
                "mapSize" to "Small", "mapType" to "Archipelago", "aiPlayers" to 1, "cityStates" to 0,
                "barbarians" to false, "ruins" to false, "seed" to "860606")
            // 与 FullGameRoutesTest.Naval 完全同场景（Work Boat 海上改良／海军交战／登船跨海）。
            else -> dto("nation" to "England", "difficulty" to "Chieftain", "speed" to "Quick",
                "mapSize" to "Small", "mapType" to "Archipelago", "aiPlayers" to 1, "cityStates" to 0,
                "barbarians" to false, "ruins" to false, "seed" to "860707")
        }

        fun play() {
            try {
                drive()
                verify()
            } catch (error: Throwable) {
                writeEvidence(error)
                throw error
            }
            writeEvidence(null)
        }

        private fun drive() {
            run(session, "newGame", "setup" to setup())
            assertEquals("新局从第 0 回合开始", 0, session.game!!.turns)
            var lastCheck = 0
            while (session.game!!.victoryData == null) {
                val turn = session.game!!.turns
                assertTrue("$name 到第 $turn 回合仍未产生原生胜负结算", turn < TURN_LIMIT)
                // 与窗口完全同构：每回合一次持久 driver（route 参数即窗口经 HTTP 请求携带的路线），随后 nextTurn
                // 推进 AI 回合（等价窗口真实点击“下一回合”）。
                val summary = driver.playTurn(route)
                tally(summary)
                if (summary.boolean("victoryReached") || session.game!!.victoryData != null) break
                if (turn > 0 && turn - lastCheck >= CHECK_EVERY) {
                    roundtrip(turn)
                    lastCheck = turn
                }
                run(session, "nextTurn")
            }
            assertNotNull("autoplay $route 应驱动到原生胜负结算", session.game!!.victoryData)
            continueAfterVictory()
            roundtrip(session.game!!.turns)
            assertTrue("续玩阶段胜利记录必须保留", session.game!!.victoryData != null)
            assertTrue("续玩阶段应保持 oneMoreTurnMode", session.game!!.oneMoreTurnMode)
        }

        private fun tally(summary: JsonObject) {
            cumulativeActions += summary.integer("actionsIssued")
            everWar = everWar || summary.boolean("warStarted")
            maxCities = maxOf(maxCities, summary.integer("cities"))
            maxTechnologies = maxOf(maxTechnologies, summary.integer("technologies"))
            maxScienceBuildings = maxOf(maxScienceBuildings, summary.integer("scienceBuildingsBuilt"))
            maxWaterImprovements = maxOf(maxWaterImprovements, summary.integer("waterImprovements"))
            maxNavalUnits = maxOf(maxNavalUnits, summary.integer("navalUnits"))
            maxEmbarkedUnits = maxOf(maxEmbarkedUnits, summary.integer("embarkedUnits"))
        }

        private fun roundtrip(turn: Int) {
            val saved = run(session, "save", "name" to "$name-t$turn")["savedPath"]!!.jsonPrimitive.content
            val file = File(saved)
            assertTrue("存档应已写盘：$file", file.isFile)
            val gatewayReload = GameSession(root, directory)
            run(gatewayReload, "load", "path" to file.path)
            val nativeReload = NativeReplay.fromSaveText(file.readText())
            GameplayAssertions.assertGameplayEquals(
                "$name 第 $turn 回合 autoplay 存档往返（网关重载 vs 独立原生重载）",
                gatewayReload.game!!, nativeReload.game)
            checkpoints += dto("turn" to turn, "save" to file.name, "matched" to true)
        }

        private fun continueAfterVictory() {
            val data = run(session, "victoryOptions")["data"]!!.jsonObject
            val continuation = data["continue"]!!.jsonObject
            assertTrue("胜负结算必须可以继续游戏：$data", continuation.boolean("enabled"))
            run(session, "victoryContinue", "resultToken" to continuation.text("token"))
        }

        private fun verify() {
            val game = session.game!!
            val victory = game.victoryData!!
            val player = game.currentPlayerCiv
            val winner = game.civilizations.firstOrNull { it.civID == victory.winningCiv }
            assertNotNull("获胜者应为真实文明", winner)
            assertTrue("获胜者应为主要文明", winner!!.isMajorCiv())
            assertFalse("获胜者不应被灭亡", winner.isDefeated())
            assertTrue("autoplay 应发出真实网关命令（生产/研发/移动/攻击/建城等），实际累计 $cumulativeActions 条",
                cumulativeActions > 20)
            assertTrue("长局应真实推进多个回合，实际第 ${game.turns} 回合", game.turns > 40)
            assertTrue("结算类型应为原生胜负结算之一",
                victory.victoryType in listOf("Scientific", "Domination", "Cultural", "Time"))
            when (route) {
                "science" -> {
                    assertTrue("科学路线应深入研发科技树（峰值 $maxTechnologies 项）", maxTechnologies >= 35)
                    // 快照统计的是建成实例数（跨城累计）：≥2 座科学建筑即真实科学产出的硬证据，
                    // 比 FullGameRoutesTest 的“≥2 种排产”更强（排产种类含未建成者）。
                    assertTrue("科学路线应真实建成 ≥2 座科学建筑（峰值 $maxScienceBuildings 座）",
                        maxScienceBuildings >= 2)
                    if (victory.victoryType == "Domination" && winner.civID == player.civID) {
                        assertTrue("科学路线征服获胜须真实进入战争", everWar)
                        assertTrue("科学路线征服获胜应真实扩张过城市（峰值 $maxCities）", maxCities >= 2)
                    }
                }
                "naval" -> {
                    assertTrue("海洋路线应真实创建海上改良（峰值 $maxWaterImprovements 处）", maxWaterImprovements >= 1)
                    assertTrue("海洋路线应真实拥有海军单位（峰值 $maxNavalUnits 艘）", maxNavalUnits >= 1)
                    assertTrue("海洋路线应真实登船跨海（峰值 $maxEmbarkedUnits 个登船单位）", maxEmbarkedUnits >= 1)
                    if (victory.victoryType == "Domination" && winner.civID == player.civID)
                        assertTrue("海洋路线征服获胜须真实进入战争", everWar)
                }
            }
            assertTrue("应有存档往返检查点", checkpoints.isNotEmpty())
            assertTrue("每个检查点都应通过存档往返差分", checkpoints.all { it.boolean("matched") })
        }

        private fun writeEvidence(error: Throwable?) {
            val game = session.game
            val victory = game?.victoryData
            val player = game?.currentPlayerCiv
            val evidence = dto(
                "profile" to name, "route" to route,
                "mapSize" to "Small", "mapType" to "Archipelago",
                "seed" to if (route == "science") "860606" else "860707",
                "aiPlayers" to 1, "cityStates" to 0, "turnLimit" to TURN_LIMIT,
                "gameId" to game?.gameId, "turns" to game?.turns,
                "victoryType" to victory?.victoryType,
                "victoryWinner" to victory?.let { record ->
                    game?.civilizations?.firstOrNull { it.civID == record.winningCiv }?.civName
                },
                "victoryTurn" to victory?.victoryTurn,
                "playerWon" to (victory != null && player != null && victory.winningCiv == player.civID),
                "oneMoreTurnMode" to game?.oneMoreTurnMode,
                "cities" to player?.cities?.size, "maxCities" to maxCities,
                "units" to player?.units?.getCivUnits()?.count(),
                "technologies" to player?.tech?.techsResearched?.size, "maxTechnologies" to maxTechnologies,
                "maxScienceBuildingsBuilt" to maxScienceBuildings,
                "maxWaterImprovements" to maxWaterImprovements,
                "maxNavalUnits" to maxNavalUnits, "maxEmbarkedUnits" to maxEmbarkedUnits,
                "warStarted" to everWar, "cumulativeActions" to cumulativeActions,
                "checkpoints" to checkpoints,
                "error" to error?.let { "${it.javaClass.simpleName}: ${it.message}" },
                "elapsedMillis" to (System.nanoTime() - started) / 1_000_000,
                "heapUsedBytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            report.parentFile.mkdirs()
            report.writeText(evidence.toString())
            if (error != null) return
            println("autoplay 整局 $name：第 ${game?.turns} 回合原生结算 ${victory?.victoryType}" +
                "（获胜 ${victory?.let { record -> game?.civilizations?.firstOrNull { it.civID == record.winningCiv }?.civName }}，" +
                "玩家胜=${victory?.winningCiv == player?.civID}），累计命令 $cumulativeActions，战争=$everWar，" +
                "科技峰值 $maxTechnologies，科学建筑峰值 $maxScienceBuildings，海上改良峰值 $maxWaterImprovements，" +
                "海军峰值 $maxNavalUnits，登船峰值 $maxEmbarkedUnits，检查点 ${checkpoints.size}，" +
                "耗时 ${evidence.integer("elapsedMillis")} ms")
        }
    }
}
