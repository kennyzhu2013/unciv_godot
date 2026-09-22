package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** 只在测试中生成原格式交战场景，不向生产网关加入作弊命令。 */
internal object BattleFixtures {
    val root get() = File(System.getProperty("unciv.root"))
    fun <T> native(game: GameInfo, operation: () -> T): T {
        val previous = UncivGame.Current.gameInfo
        UncivGame.Current.gameInfo = game
        return try { operation() } finally { UncivGame.Current.gameInfo = previous }
    }
    fun enemy(game: GameInfo) = game.civilizations.single { it.civName == "Greece" }
    fun add(game: GameInfo, name: String, x: Int, y: Int, civ: Civilization = game.currentPlayerCiv): MapUnit =
        game.tileMap.placeUnitNearTile(HexCoord(x, y), name, civ)!!.also {
            check(it.currentTile.position == HexCoord(x, y))
            it.currentMovement = it.getMaxMovement().toFloat()
        }
    fun game(): GameInfo {
        KernelRuntime.initialize(root)
        val game = KernelRuntime.createDemo()
        native(game) {
            game.civilizations.forEach { civ -> civ.units.getCivUnits().toList().forEach { it.destroy() } }
            game.tileMap = TileMap(8, game.ruleset).apply {
                gameInfo = game
                mapParameters.mapSize = MapSize(8)
                mapParameters.seed = 4602L
            }
            game.setTransients()
            val player = game.currentPlayerCiv
            val enemy = enemy(game)
            player.addCity(HexCoord(-6, 0)).cityConstructions.addToQueue(game.ruleset.buildings["Monument"]!!, addToTop = true)
            enemy.addCity(HexCoord(6, 0))
            enemy.addCity(HexCoord(1, 0)).apply { name = "陆战测试城"; health = 1; population.setPopulation(3) }
            if (!player.knows(enemy)) player.diplomacyFunctions.makeCivilizationsMeet(enemy)
            player.getDiplomacyManager(enemy)!!.declareWar()
            add(game, "Warrior", 0, 0)
            add(game, "Archer", 0, 1)
            add(game, "Warrior", 0, 2, enemy)
            player.tech.techsToResearch = arrayListOf("Pottery")
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            game.setTransients()
        }
        return game
    }
    fun file(name: String = "combat", configure: (GameInfo) -> Unit = {}): File {
        val game = game()
        native(game) { configure(game) }
        return File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }
    fun request(session: GameSession, action: String, vararg args: Pair<String, Any?>) = dto(
        "protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
        "requestId" to UUID.randomUUID().toString(), "action" to action, *args)
    fun run(session: GameSession, action: String, vararg args: Pair<String, Any?>): JsonObject =
        session.handle(request(session, action, *args)).also { assertTrue(it.toString(), it["ok"]!!.jsonPrimitive.boolean) }
    fun load(file: File) = GameSession(root).also { run(it, "load", "path" to file.absolutePath) }
    fun unit(game: GameInfo, name: String) = game.currentPlayerCiv.units.getCivUnits().first { it.name == name }
}
