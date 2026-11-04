package com.unciv.godot

import com.unciv.godot.BattleFixtures.run
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 第 8B 批（真实窗口整局前置）：验证 main-source 冒烟整局自动驱动 [AutoplayDriver] 能从真实 newGame
 * 一路打到原生胜负结算，且驱动全程的对局状态持久稳定（save→独立原生 load 往返一致）。
 *
 * 与 [FullGameTest]（测试内联征服驱动 + 逐命令 twin 差分 + 每回合 resync）互补：本测试驱动的是**产品
 * main-source** 里真实窗口经 HTTP `autoplay` 动词调用的同一套 [AutoplayDriver]，并且像窗口那样**连续持有
 * 单一会话/单一驱动实例**（不换会话、不逐回合重载），以快反馈在接入较慢的真实窗口 Smoke 前先证明：
 * 1. 该 driver 从真实 newGame 逐回合（autoplay 处置待决+指挥单位 → nextTurn 推进 AI 回合，与窗口“真实点击
 *    下一回合”等价）能收敛到原生 checkForVictory 产生的 victoryData（征服路线目标 Domination），不注入胜利、
 *    不清空待决、不改原生胜利条件；
 * 2. 驱动全程只走真实网关命令，故任意检查点把当前对局存档后，“网关重载 vs 独立原生重载”完全一致——即
 *    autoplay 命令序列不会留下半提交/不可持久化的状态（[GameplayAssertions]）；
 * 3. 达到原生结算后仍可按原生语义 victoryContinue 续玩，保留胜负记录并进入 oneMoreTurnMode。
 *
 * 覆盖边界：GameSession 的 `autoplay` 传输动词外壳（UNCIV_SMOKE 门控 + revision 校验 + reply 包装）在
 * gradle 测试进程中因门控关闭而无法走 handle() 路径（见 [autoplayVerbIsGatedOffWithoutSmokeEnv]），故本测试
 * 直接实例化同一 [AutoplayDriver] 并全程复用，忠实复刻窗口“每回合调用一次持久驱动”的语义；动词外壳由真实
 * 窗口 Smoke（run.ps1 -Smoke 设 UNCIV_SMOKE=1）端到端覆盖。玩家胜负非确定（core AI 文明选择/起始位用无种子
 * shuffle，见 [FullGameTest.verify] 注释），故与其同口径按结局分支断言，不重开抽“更快获胜”的局、不降断言。
 */
class AutoplayGameTest {
    companion object {
        private val root = File(System.getProperty("unciv.root"))
        // 回合上限：Standard 速度原生 500 回合触发 Score/Time 结算，这里取 450 守卫——留足长局余量
        // （FullGameTest 陆地征服实测 71~255 回合达成 Domination），又在 Score 结算前失败，只接受真实 Domination。
        private const val TURN_LIMIT = 450
        // 存档往返检查点间隔：每 40 回合验证一次“网关重载 vs 独立原生重载”，外加结算处一次。
        private const val CHECK_EVERY = 40
        // 与 FullGameTest 同源：WAR_TURN=12 后才可能宣战，长局必须远超此回合数才算真实推进。
        private const val WAR_TURN_GUARD = 12

        // 与 FullGameTest 陆地征服完全同场景，使 main-source 驱动面对与已锁定内核路线一致的对局。
        private fun setup() = dto("nation" to "Rome", "difficulty" to "Chieftain", "speed" to "Standard",
            "mapSize" to "Tiny", "mapType" to "Pangaea", "aiPlayers" to 1, "cityStates" to 0,
            "barbarians" to false, "ruins" to false, "seed" to "860505")

        @BeforeClass @JvmStatic fun setUp() { KernelRuntime.initialize(root) }
    }

    /**
     * 与 debugInjectEvent 同口径的门控证明：gradle 测试进程未设 UNCIV_SMOKE，`autoplay` 动词等同不存在，
     * 返回 UNKNOWN_COMMAND 且不驱动任何对局——确保它不会成为真实游玩产品面的一部分（仅冒烟运行时可用）。
     */
    @Test fun autoplayVerbIsGatedOffWithoutSmokeEnv() {
        val session = GameSession(root, File(root, "godot/.local/tests/full-game/autoplay-gate"))
        val request = dto("protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
            "requestId" to UUID.randomUUID().toString(), "action" to "autoplay")
        val response = session.handle(request)
        assertFalse("未设 UNCIV_SMOKE 时 autoplay 动词必须关闭", response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("UNKNOWN_COMMAND", response["error"]!!.jsonObject.text("code"))
    }

    @Test fun autoplayDrivesNewGameToNativeSettlement() = Autoplay().play()

    private inner class Autoplay {
        private val name = "autoplay-land-domination"
        private val directory = File(root, "godot/.local/tests/full-game/$name")
        private val report = File(root, "godot/.local/tests/full-game/$name.json")
        private val started = System.nanoTime()
        private val session = GameSession(root, directory)
        // 产品里 GameSession 按会话持久持有单一 AutoplayDriver（跨回合保留 warStarted/knownEnemyCities 记忆）；
        // 本测试直接实例化同一 main-source driver 并全程复用，忠实复刻窗口经 autoplay 动词的连续调用方式。
        private val driver = AutoplayDriver(session)
        private val checkpoints = ArrayList<JsonObject>()
        private var cumulativeActions = 0
        private var everWar = false
        private var maxCities = 0
        private var maxTechnologies = 0

        fun play() {
            try {
                drive()
                verify()
            } catch (error: Throwable) {
                // 失败也落盘证据：完整长局的失败现场必须能按回合/结算/命令数复盘。
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
                // 窗口每回合：HTTP autoplay 处置待决+指挥单位（不推进回合）→ 真实点击“下一回合”推进 AI 回合。
                val summary = driver.playTurn()
                tally(summary)
                if (summary.boolean("victoryReached") || session.game!!.victoryData != null) break
                if (turn > 0 && turn - lastCheck >= CHECK_EVERY) {
                    roundtrip(turn)
                    lastCheck = turn
                }
                // nextTurn 要求待决为空（GameSession registerCore）；playTurn 末尾已 resolvePending，故此处安全。
                run(session, "nextTurn")
            }
            assertNotNull("autoplay 应驱动到原生胜负结算", session.game!!.victoryData)
            // 原生结算出现后 driver 已停手（victoryReached）；按原生语义继续游戏（等价窗口真实点击“继续”），
            // 再验证胜利态存档往返与 oneMoreTurnMode 持久化（与 FullGameTest 尾部同口径：先确认后续存/重载）。
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
        }

        /**
         * 非破坏性存档往返：把当前对局存档分别经“网关重载”与“独立原生重载”，断言二者完全一致；不切换驱动
         * 会话（与 FullGameTest 的 resyncFromSave 不同——那会换 session 以同步 twin，而本测试无 twin，只需证明
         * 当前连续会话所驱动出的状态可被两条独立加载路径一致还原）。
         */
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
            // 真实原生 Domination 结算（占领全部原始首都），不注入胜利记录。
            assertEquals("autoplay 征服长局应达成原生 Domination 结算", "Domination", victory.victoryType)
            val winner = game.civilizations.firstOrNull { it.civID == victory.winningCiv }
            assertNotNull("获胜者应为真实文明", winner)
            assertTrue("获胜者应为主要文明", winner!!.isMajorCiv())
            assertFalse("获胜者不应被灭亡", winner.isDefeated())
            // 真实征服行为证据：driver 只走真实网关命令，故命令量与战争态即真实操作证据。
            assertTrue("autoplay 征服长局应真实进入战争（宣战或被宣战）", everWar)
            assertTrue("autoplay 应发出真实网关命令（生产/研发/移动/攻击/建城等），实际累计 $cumulativeActions 条",
                cumulativeActions > 20)
            assertTrue("征服长局应真实推进多个回合，实际第 ${game.turns} 回合", game.turns > WAR_TURN_GUARD)
            // 玩家胜负非确定（core AI 无种子 shuffle）：按结局分支断言，与 FullGameTest.verify 同口径。
            if (winner.civID == player.civID) {
                assertFalse("玩家获胜时不应被灭亡", player.isDefeated())
                assertTrue("玩家获胜时应真实扩张过城市（峰值 $maxCities）", maxCities >= 2)
                // Domination(CaptureAllCapitals) 精确含义（core Victory.kt）：对每个其他主要文明，其原始首都若仍
                // 存在则必被获胜方持有，若已不存在则该文明必被消灭；失败方仍可保留非首都城市。
                val capitals = game.getCities().filter { it.isOriginalCapital }.associateBy { it.foundingCivObject }
                assertTrue("玩家达成 Domination 时应持有所有仍存在的其他文明原始首都",
                    game.civilizations.filter { it.isMajorCiv() && it != player }.all { other ->
                        val capital = capitals[other]
                        if (capital != null) capital.civ == player else other.isDefeated()
                    })
            } else {
                // 玩家被先征服：Domination 下玩家原始首都必被占领（或玩家已无城市）。真实征服命令路径已由
                // 上面的战争态 + 命令量覆盖。
                assertTrue("玩家失败时应已灭亡或失去原始首都",
                    player.isDefeated() || player.cities.none { it.isOriginalCapital })
            }
            // 存档往返：每个检查点都真实通过“网关重载 vs 独立原生重载”差分，证明连续会话所驱动状态可持久还原。
            assertTrue("应有存档往返检查点", checkpoints.isNotEmpty())
            assertTrue("每个检查点都应通过存档往返差分", checkpoints.all { it.boolean("matched") })
        }

        private fun writeEvidence(error: Throwable?) {
            val game = session.game
            val victory = game?.victoryData
            val player = game?.currentPlayerCiv
            val evidence = dto(
                "profile" to name, "mapSize" to "Tiny", "mapType" to "Pangaea", "seed" to "860505",
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
                "warStarted" to everWar, "cumulativeActions" to cumulativeActions,
                "checkpoints" to checkpoints,
                "error" to error?.let { "${it.javaClass.simpleName}: ${it.message}" },
                "elapsedMillis" to (System.nanoTime() - started) / 1_000_000,
                "heapUsedBytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            report.parentFile.mkdirs()
            report.writeText(evidence.toString())
            if (error != null) return
            println("autoplay 整局 $name：第 ${game?.turns} 回合原生结算 ${victory?.victoryType}" +
                "（获胜 ${victory?.winningCiv}，玩家胜=${victory?.winningCiv == player?.civID}），" +
                "累计 autoplay 命令 $cumulativeActions，战争=$everWar，城市峰值 $maxCities，" +
                "检查点 ${checkpoints.size}，耗时 ${evidence.integer("elapsedMillis")} ms")
        }
    }
}
