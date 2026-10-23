package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

class GameLifecycleTest {
    companion object {
        private val root = File(System.getProperty("unciv.root"))
        @BeforeClass @JvmStatic fun setup() { KernelRuntime.initialize(root) }
    }
    private val directory = File(root, "godot/.local/tests/lifecycle/${UUID.randomUUID()}")
    private fun session() = GameSession(root, directory)
    private fun request(session: GameSession, action: String, vararg parameters: Pair<String, Any?>) = dto(
        "protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
        "requestId" to UUID.randomUUID().toString(), "action" to action, *parameters)
    private fun run(session: GameSession, action: String, vararg parameters: Pair<String, Any?>): JsonObject =
        session.handle(request(session, action, *parameters)).also { assertTrue(it.toString(), it.boolean("ok")) }
    private fun smallSetup() = dto("nation" to "Rome", "mapSize" to "Small", "aiPlayers" to 2,
        "cityStates" to 2, "seed" to "4602")
    private fun state(session: GameSession) = json().apply { setUsePrototypes(false) }.toJson(session.game)

    @Test fun optionsAndEmptySavesAreReadOnlyWithoutAGame() {
        val session = session()
        val options = run(session, "newGameOptions")["data"]!!.jsonObject
        assertTrue(options["nations"]!!.jsonArray.contains(JsonPrimitive("Rome")))
        assertFalse(options["victories"]!!.jsonArray.isEmpty())
        assertEquals(false, options.boolean("worldWrap"))
        val listing = run(session, "listSaves")["data"]!!.jsonObject
        assertTrue(listing["saves"]!!.jsonArray.isEmpty())
        assertEquals(JsonNull, listing["latestPath"])
        assertFalse(directory.exists())
        assertEquals(0, session.revision)
        assertNull(session.game)
        assertEquals("NO_SAVE", session.handle(request(session, "resume", "path" to ""))["error"]!!.jsonObject.text("code"))
    }

    @Test fun defaultsAndSelectedParametersMatchNativeConfiguration() {
        val defaults = NewGameSetup.parse(dto("setup" to dto()))
        val native = GameParameters()
        assertEquals(native.baseRuleset, defaults.gameParameters.baseRuleset)
        assertEquals(native.difficulty, defaults.gameParameters.difficulty)
        assertEquals(native.speed, defaults.gameParameters.speed)
        assertEquals(native.startingEra, defaults.gameParameters.startingEra)
        assertEquals(native.noBarbarians, defaults.gameParameters.noBarbarians)
        assertEquals(native.numberOfCityStates, defaults.gameParameters.numberOfCityStates)
        assertEquals(MapParameters().noRuins, defaults.mapParameters.noRuins)
        assertEquals(MapSize.Medium.name, defaults.mapParameters.mapSize.name)
        assertEquals(RulesetCache.getComplexRuleset(native).selectableVictories().map { it.name }, defaults.gameParameters.victoryTypes)
        val selected = NewGameSetup.parse(dto("setup" to smallSetup()))
        assertEquals("Rome", selected.gameParameters.players.first().chosenCiv)
        assertEquals(PlayerType.Human, selected.gameParameters.players.first().playerType)
        assertEquals(2, selected.gameParameters.players.count { it.playerType == PlayerType.AI })
        assertEquals(2, selected.gameParameters.numberOfCityStates)
        assertEquals("Small", selected.mapParameters.mapSize.name)
        assertEquals(4602L, selected.mapParameters.seed)
    }

    @Test fun newGamePersistsNativeSaveAndReplayDoesNotCreateAnotherGame() {
        val session = session()
        val payload = request(session, "newGame", "setup" to smallSetup())
        val result = session.handle(payload)
        assertTrue(result.toString(), result.boolean("ok"))
        val game = session.game!!
        assertEquals("Rome", game.currentPlayerCiv.civName)
        assertEquals(3, game.civilizations.count { it.isMajorCiv() })
        assertTrue(game.civilizations.any { it.isCityState })
        assertFalse(game.gameParameters.noBarbarians)
        assertFalse(game.tileMap.mapParameters.noRuins)
        assertTrue(game.gameParameters.victoryTypes.isNotEmpty())
        val auto = File(result.text("autosavedPath"))
        assertTrue(auto.isFile)
        assertFalse(auto.readText().startsWith("{"))
        GameplayAssertions.assertGameplayEquals("新局原格式自动存档", game, UncivFiles.gameInfoFromString(auto.readText()))
        val bytes = auto.readBytes()
        val time = auto.lastModified()
        assertEquals(result, session.handle(payload))
        assertSame(game, session.game)
        assertEquals(1, session.revision)
        assertArrayEquals(bytes, auto.readBytes())
        assertEquals(time, auto.lastModified())
        val before = state(session)
        val list = run(session, "listSaves")["data"]!!.jsonObject
        assertEquals(before, state(session))
        assertEquals(1, session.revision)
        assertTrue(list["saves"]!!.jsonArray.single().jsonObject.boolean("loadable"))
        val restarted = session()
        run(restarted, "resume", "path" to list.text("latestPath"))
        GameplayAssertions.assertGameplayEquals("跨会话继续新局", game, restarted.game!!)
        assertEquals(game.gameId, restarted.game!!.gameId)
    }

    @Test fun invalidConfigurationAndStaleRequestsPreserveCurrentGame() {
        val session = session()
        run(session, "newGame", "setup" to smallSetup())
        val before = state(session)
        val revision = session.revision
        val global = UncivGame.Current.gameInfo
        for (invalid in listOf(dto("nation" to "Atlantis"), dto("nation" to "Barbarians"),
            dto("aiPlayers" to 0), dto("aiPlayers" to 2.5), dto("aiPlayers" to "2"),
            dto("cityStates" to 99), dto("speed" to "invalid"), dto("difficulty" to "invalid"),
            dto("mapSize" to "Huge"), dto("mapType" to "Empty"), dto("barbarians" to "true"),
            dto("seed" to "9223372036854775808"), dto("worldWrap" to true), dto("mods" to listOf("x")))) {
            val result = session.handle(request(session, "newGame", "setup" to invalid))
            assertEquals(result.toString(), "GAME_SETUP", result["error"]!!.jsonObject.text("code"))
            assertEquals(before, state(session))
            assertEquals(revision, session.revision)
            assertSame(global, UncivGame.Current.gameInfo)
        }
        val stale = JsonObject(request(session, "newGame", "setup" to smallSetup()) + ("revision" to JsonPrimitive(0)))
        assertEquals("STALE_STATE", session.handle(stale)["error"]!!.jsonObject.text("code"))
        assertEquals(before, state(session))
    }

    @Test fun corruptNewestSaveDoesNotSilentlyResumeAnOlderGame() {
        val session = session()
        run(session, "newGame", "setup" to smallSetup())
        val before = state(session)
        val broken = File(directory, "broken.json").apply { writeText("not-a-save"); setLastModified(System.currentTimeMillis() + 10000) }
        val listing = run(session, "listSaves")["data"]!!.jsonObject
        assertEquals(broken.absolutePath, listing.text("latestPath"))
        assertFalse(listing["saves"]!!.jsonArray.first().jsonObject.boolean("loadable"))
        val result = session.handle(request(session, "resume", "path" to broken.absolutePath))
        assertFalse(result.boolean("ok"))
        assertEquals(before, state(session))
        assertEquals(1, session.revision)
        assertEquals("not-a-save", broken.readText())
        val changed = session.handle(request(session, "resume", "path" to "previous-file"))
        assertEquals("SAVE_CHANGED", changed["error"]!!.jsonObject.text("code"))
    }

    @Test fun automaticSlotsRotatePerGameAndLeaveImportedSourceUntouched() {
        val saves = GameSaves(directory)
        val game = KernelRuntime.createDemo()
        val source = File(directory, "original.json")
        saves.save(game, source)
        val bytes = source.readBytes()
        val session = session()
        run(session, "load", "path" to source.absolutePath)
        assertArrayEquals(bytes, source.readBytes())
        // 此处只测存储轮转；真实逐回合原生差分另由长局测试覆盖。
        for (turn in 1..8) { game.turns = turn; saves.autosave(game) }
        val autos = File(directory, "autosaves").listFiles()!!.filter { it.extension == "json" }
        assertEquals(5, autos.size)
        assertEquals((4..8).toSet(), autos.map { UncivFiles.gameInfoFromString(it.readText()).turns }.toSet())
        saves.autosave(KernelRuntime.createDemo())
        assertEquals(6, File(directory, "autosaves").listFiles()!!.size)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun failedAutomaticSaveDoesNotCommitGameOrRevision() {
        val session = session()
        run(session, "demo")
        while (session.game!!.currentPlayerCiv.popupAlerts.isNotEmpty()) run(session, "acknowledge")
        run(session, "research", "name" to "Pottery")
        val source = File(run(session, "save", "name" to "baseline").text("savedPath"))
        val sourceBytes = source.readBytes()
        val before = state(session)
        val current = session.game
        val revision = session.revision
        assertTrue(PlayerSnapshot(current!!).pending().toString(), PlayerSnapshot(current).pending().isEmpty())
        // 路径被普通文件占据，模拟自动存档目录不可用，不依赖平台权限差异。
        File(directory, "autosaves").writeText("blocked")
        for ((action, parameters) in listOf("newGame" to dto("setup" to smallSetup()),
            "load" to dto("path" to source.absolutePath), "resume" to dto("path" to source.absolutePath),
            "nextTurn" to dto())) {
            val result = session.handle(request(session, action, *parameters.toList().toTypedArray()))
            assertEquals("$action 必须确实到达自动存档失败路径", "KERNEL_ERROR", result["error"]!!.jsonObject.text("code"))
            assertSame(current, session.game)
            assertSame(current, UncivGame.Current.gameInfo)
            assertEquals(before, state(session))
            assertEquals(revision, session.revision)
            assertArrayEquals(sourceBytes, source.readBytes())
            assertEquals("blocked", File(directory, "autosaves").readText())
        }
    }
}
