package com.unciv.godot

import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 第 8 批终局专项：文化、时间与玩家灭亡。
 *
 * 这三条此前只有夹具注入覆盖（直接写 victoryData、直接加部件、直接改 turns）。这里改为夹具只预置
 * “动作前”条件——政策分支进度、差一回合的乌托邦工程进度、回合上限、城市血量——最终结算一律由真实
 * 网关动作（policy / production / nextTurn）触发，不写入胜利记录、不改太空船计数、不清空队列。
 *
 * 科学（6 件真实组装）与征服（真实 attack + cityDecision）已分别在 [UnitAbilityCommandsTest] 与
 * [VictoryCommandsTest] 覆盖；外交投票由 [DiplomaticVoteCommandsTest] 的真实投票覆盖。
 */
class EndgameCommandsTest {
    companion object {
        private val root = File(System.getProperty("unciv.root"))
        @BeforeClass @JvmStatic fun setup() { KernelRuntime.initialize(root) }
    }

    private fun export(game: GameInfo, name: String): File =
        File(root, "godot/.local/tests/endgame-$name.json").apply {
            parentFile.mkdirs(); writeText(UncivFiles.gameInfoToString(game, true))
        }

    private fun load(fixture: GameInfo, name: String) = BattleFixtures.load(export(fixture, name))

    private fun expected(fixture: GameInfo) = VictoryFixtures.copy(fixture).also { VictoryFixtures.screenUpdate(it) }

    /** 实际端必须读取自己刚保存的文件；期望端独立序列化，不能拿期望文件冒充存档回归。 */
    private fun reloadSaved(session: GameSession, native: GameInfo, label: String): GameInfo {
        val saved = run(session, "save", "name" to "endgame-$label").text("savedPath")
        val expected = expected(native)
        val resumed = GameSession(root)
        run(resumed, "load", "path" to saved)
        assertGameplayEquals("$label 跨会话继续", expected, resumed.game!!)
        run(session, "load", "path" to saved)
        assertGameplayEquals("$label 原会话重载", expected, session.game!!)
        return expected
    }

    // ---- 文化：真实采纳第五分支末项政策 + 真实选择并完成乌托邦工程 ----

    @Test fun culturalVictoryCompletesThroughRealPolicyAndProduction() {
        val fixture = VictoryFixtures.cultural()
        val policy = VictoryFixtures.traditionPolicy(fixture)
        val cityId = fixture.currentPlayerCiv.cities.first().id
        val session = load(fixture, "cultural")
        var expected = expected(fixture)
        assertEquals(4, session.game!!.currentPlayerCiv.policies.completedBranches.size)
        assertTrue("乌托邦工程此刻应尚未可建", PlayerSnapshot(session.game!!).pending().none { it.text("kind") == "victory" })

        run(session, "policy", "name" to policy)
        native(expected) { expected.currentPlayerCiv.policies.adopt(expected.ruleset.policies[policy]!!) }
        VictoryFixtures.screenUpdate(expected)
        assertGameplayEquals("真实采纳第五分支末项政策", expected, session.game!!)
        assertEquals("政策分支应已齐五", 5, session.game!!.currentPlayerCiv.policies.completedBranches.size)
        assertNull("项目尚未建成不得结算", session.game!!.victoryData)
        assertTrue("政策到齐后乌托邦工程应可生产",
            session.game!!.currentPlayerCiv.cities.first().cityConstructions.canAddToQueue(
                session.game!!.ruleset.buildings["Utopia Project"]!!))

        run(session, "production", "cityId" to cityId, "name" to "Utopia Project")
        expected = DiplomaticVoteFixtures.apply(expected, "production",
            dto("cityId" to cityId, "name" to "Utopia Project"))
        assertGameplayEquals("真实选择乌托邦工程", expected, session.game!!)
        assertNull("队列还没走完不得结算", session.game!!.victoryData)
        expected = reloadSaved(session, expected, "cultural-before")

        run(session, "nextTurn")
        expected = DiplomaticVoteFixtures.apply(expected, "nextTurn")
        assertGameplayEquals("乌托邦工程在原生回合完工", expected, session.game!!)
        assertEquals("Cultural", session.game!!.victoryData!!.victoryType)
        assertEquals(session.game!!.currentPlayerCiv.civID, session.game!!.victoryData!!.winningCiv)
        val data = run(session, "victoryOptions")["data"]!!.jsonObject
        assertEquals("won", data.text("outcome"))
        assertTrue(data["progress"]!!.jsonArray.map { it.jsonObject }.single().let { progress ->
            progress["milestones"]!!.jsonArray.all { it.jsonObject.text("status") == "Completed" }
        })
        run(session, "victoryContinue", "resultToken" to data["continue"]!!.jsonObject.text("token"))
        expected.oneMoreTurnMode = true
        assertGameplayEquals("文化胜利后继续", expected, session.game!!)
        reloadSaved(session, expected, "cultural-continued")
    }

    // ---- 时间：真实 nextTurn 跨越回合上限，胜者由原生分数决定 ----

    private fun time(playerLeads: Boolean): GameInfo = VictoryFixtures.time(playerLeads)

    @Test fun timeVictoryCrossesMaxTurnsThroughRealNextTurn() {
        for (playerLeads in listOf(true, false)) {
            val fixture = time(playerLeads)
            val session = load(fixture, if (playerLeads) "time-win" else "time-lose")
            var expected = expected(fixture)
            assertEquals(1, session.game!!.gameParameters.maxTurns)
            assertNull("尚未跨越回合上限时不得结算", session.game!!.victoryData)
            expected = reloadSaved(session, expected, "time-$playerLeads-before")

            run(session, "nextTurn")
            expected = DiplomaticVoteFixtures.apply(expected, "nextTurn")
            assertGameplayEquals("真实跨越回合上限 playerLeads=$playerLeads", expected, session.game!!)
            assertEquals("Time", session.game!!.victoryData!!.victoryType)
            assertEquals("胜者应是原生分数最高者", expected.victoryData!!.winningCiv, session.game!!.victoryData!!.winningCiv)
            val data = run(session, "victoryOptions")["data"]!!.jsonObject
            assertEquals(if (playerLeads) "won" else "lost", data.text("outcome"))
            assertEquals(expected.turns, data["recorded"]!!.jsonObject.integer("turn"))
            run(session, "victoryContinue", "resultToken" to data["continue"]!!.jsonObject.text("token"))
            expected.oneMoreTurnMode = true
            assertGameplayEquals("时间胜利后继续 playerLeads=$playerLeads", expected, session.game!!)
            reloadSaved(session, expected, "time-$playerLeads-continued")
        }
    }

    // ---- 灭亡：真实 AI 回合夺取最后一座城市 ----

    private fun lastStand(): GameInfo = VictoryFixtures.lastStand()

    @Test fun playerDefeatArrivesThroughRealNativeTurn() {
        val fixture = lastStand()
        val session = load(fixture, "defeat")
        var expected = expected(fixture)
        assertFalse("开局时玩家尚未灭亡", session.game!!.currentPlayerCiv.isDefeated())
        expected = reloadSaved(session, expected, "defeat-before")

        run(session, "nextTurn")
        expected = DiplomaticVoteFixtures.apply(expected, "nextTurn")
        assertGameplayEquals("AI 回合夺取最后城市", expected, session.game!!)
        assertTrue("玩家应已灭亡", session.game!!.currentPlayerCiv.isDefeated())
        // 原生回合里 AI 占下玩家最后的首都，同时满足“占领全部原始首都”的征服条件；
        // 胜者与回合都来自原生回合，夹具没有写入任何胜利记录。
        assertEquals("Domination", expected.victoryData!!.victoryType)
        assertEquals(BattleFixtures.enemy(session.game!!).civID, expected.victoryData!!.winningCiv)
        val victory = PlayerSnapshot(session.game!!).pending().first { it.text("kind") == "victory" }
        assertEquals("lost", victory.text("target"))
        assertTrue("胜负结算必须可继续", victory.boolean("supported"))
        val data = run(session, "victoryOptions")["data"]!!.jsonObject
        assertEquals("lost", data.text("outcome"))
        // 征服记录写入原生回合内的回合数，直接与原生记录值对齐。
        assertEquals(expected.victoryData!!.victoryTurn, data["recorded"]!!.jsonObject.integer("turn"))
        assertEquals(expected.victoryData!!.winningCiv, data["recorded"]!!.jsonObject.text("civId"))
        run(session, "victoryContinue", "resultToken" to data["continue"]!!.jsonObject.text("token"))
        expected.oneMoreTurnMode = true
        assertGameplayEquals("灭亡后继续", expected, session.game!!)
        reloadSaved(session, expected, "defeat-continued")
        assertFalse(run(session, "victoryOptions")["data"]!!.jsonObject.boolean("requiresAcknowledgement"))
    }

    // ---- 科学：六件部件全部经真实 move + unitAbility(assembleSpaceship) ----

    @Test fun scientificVictoryCompletesThroughRealAssemblyCommands() {
        val fixture = VictoryFixtures.science()
        val session = load(fixture, "science")
        var expected = expected(fixture)
        assertEquals(0, session.game!!.currentPlayerCiv.victoryManager.currentsSpaceshipParts.sumValues())
        val parts = session.game!!.currentPlayerCiv.units.getCivUnits().filter { it.name.startsWith("SS ") }
            .map { it.id to it.name }.toList()
        assertEquals(6, parts.size)
        for ((index, part) in parts.withIndex()) {
            if (index == parts.lastIndex) expected = reloadSaved(session, expected, "science-before-last")
            val (id, name) = part
            run(session, "move", "unitId" to id, "x" to 0, "y" to 0)
            expected = VictoryFixtures.apply(expected, "move", dto("unitId" to id, "x" to 0, "y" to 0))
            assertGameplayEquals("部件 $name 移入首都", expected, session.game!!)
            val data = run(session, "unitAbilityOptions", "unitId" to id)["data"]!!.jsonObject
            val candidate = data["candidates"]!!.jsonArray.map { it.jsonObject }
                .first { it.text("type") == "assembleSpaceship" }
            assertTrue("部件 $name 应可组装：${candidate.text("reason")}", candidate.boolean("enabled"))
            // 进度只读投影来自原生计数，总进度逐件递增。
            assertEquals(index, candidate["spaceship"]!!.jsonObject.integer("totalAssembled"))
            assertEquals(6, candidate["spaceship"]!!.jsonObject.integer("totalRequired"))
            run(session, "unitAbility", "unitId" to id, "candidateId" to candidate.text("id"),
                "token" to data.text("token"))
            expected = VictoryFixtures.apply(expected, "assembleSpaceship", dto("unitId" to id))
            assertGameplayEquals("真实组装 $name", expected, session.game!!)
            if (index < parts.size - 1) assertNull("部件未齐不得结算", session.game!!.victoryData)
        }
        assertEquals("Scientific", session.game!!.victoryData!!.victoryType)
        val victory = PlayerSnapshot(session.game!!).pending().first { it.text("kind") == "victory" }
        assertEquals("won", victory.text("target"))
        val data = run(session, "victoryOptions")["data"]!!.jsonObject
        assertEquals("won", data.text("outcome"))
        run(session, "victoryContinue", "resultToken" to data["continue"]!!.jsonObject.text("token"))
        expected.oneMoreTurnMode = true
        assertGameplayEquals("科学胜利后继续", expected, session.game!!)
        reloadSaved(session, expected, "science-continued")
    }

    @Test fun exportEndgameSmokeScenarios() { VictoryFixtures.emitEndgames() }
}
