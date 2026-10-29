package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Religion
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import java.io.File

/**
 * T3.3 宗教／伟人主动能力的测试专用状态构造与期望端原生入口。
 * 期望端只取对应类型的可执行原生 [UnitAction] 并调用一次，委托原生闭包自身的结算与 consume()；
 * 不复制科研、宗教压力、外交或瞬建公式，也不依赖 UnitAbilityCommands／DTO。
 */
internal object UnitAbilityFixtures {
    val root get() = BattleFixtures.root
    fun <T> native(game: GameInfo, operation: () -> T): T = BattleFixtures.native(game, operation)
    val center = HexCoord(0, 0)
    val greeceCapital = HexCoord(-6, 0)
    val cityStateAt = HexCoord(6, 0)

    /** 平坦草原局：己方首都(0,0)、希腊首都(-6,0)、可选城邦(6,0)；无初始单位、无野蛮人、科研挂 Globalization。 */
    fun game(cityState: Boolean = false): GameInfo {
        KernelRuntime.initialize(root)
        val parameters = GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_GnK.fullName
            players = arrayListOf(Player("Rome", PlayerType.Human), Player("Greece"))
            numberOfCityStates = if (cityState) 1 else 0
            noBarbarians = true
        }
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, MapParameters().apply {
            mapSize = MapSize.Tiny; seed = 4602L; noRuins = true
        }))
        native(game) {
            // 新开局的参数默认不含胜利类型；科学胜利需要启用后才能投影部件的需求数量。
            game.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys.filter { it != Constants.neutralVictoryType })
            game.civilizations.forEach { civ -> civ.units.getCivUnits().toList().forEach { it.destroy() } }
            game.tileMap = TileMap(8, game.ruleset).apply {
                gameInfo = game; mapParameters.mapSize = MapSize(8); mapParameters.seed = 4602L
            }
            game.setTransients()
            for (tile in game.tileMap.tileList) {
                tile.baseTerrain = Constants.grassland
                tile.setTerrainFeatures(emptyList())
                tile.tileResource = null
                tile.setTerrainTransients()
            }
            val player = game.currentPlayerCiv
            val capital = player.addCity(center)
            BattleFixtures.enemy(game).addCity(greeceCapital)
            if (cityState) game.civilizations.first { it.isCityState }.addCity(cityStateAt)
            game.civilizations.forEach { civ ->
                civ.cities.forEach { city ->
                    city.cityConstructions.constructionQueue = arrayListOf("Nothing")
                    city.expansion.cultureStored = 0
                }
                civ.tech.techsToResearch = arrayListOf("Globalization")
                civ.religionManager.storedFaith = 0
            }
            capital.population.setPopulation(3)
            game.setTransients()
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
        }
        return game
    }

    fun player(game: GameInfo): Civilization = game.currentPlayerCiv
    fun enemy(game: GameInfo): Civilization = BattleFixtures.enemy(game)
    fun capital(game: GameInfo) = player(game).cities.single { it.location == center }
    fun add(game: GameInfo, name: String, coord: HexCoord): MapUnit = BattleFixtures.add(game, name, coord.x, coord.y)

    /**
     * 强制把单位落在指定地块（含敌方城市中心或城邦中心），仅用于构造原生归属拒绝／移交场景。
     * 先在无主空地创建，再绕过 canMoveTo 强制落位；敌方领土与城市中心原生会拒绝进入，
     * 强制落位只存在于测试 fixture，不进入生产网关。军事单位写入 militaryUnit 槽位，平民写入 civilianUnit。
     */
    fun placeAt(game: GameInfo, name: String, coord: HexCoord, staging: HexCoord = HexCoord(2, 2)): MapUnit = native(game) {
        val unit = BattleFixtures.add(game, name, staging.x, staging.y)
        val tile = game.tileMap[coord]
        if (unit.currentTile.position != coord) {
            unit.removeFromTile()
            if (unit.isCivilian()) tile.civilianUnit = unit else tile.militaryUnit = unit
            unit.currentTile = tile
        }
        unit
    }

    /** 放置宗教单位并绑定己方宗教名（原生 spread／heresy 门禁依赖 unit.religion）。 */
    fun addReligiousUnit(game: GameInfo, name: String, coord: HexCoord): MapUnit = add(game, name, coord).also {
        it.religion = player(game).religionManager.religion?.name
    }

    /** 为希腊构造一个含 Founder 信条的真实宗教并登记，供异教压力／圣城场景引用。 */
    fun greeceReligion(game: GameInfo): String = native(game) {
        val symbol = game.ruleset.religions.first { !game.religions.containsKey(it) }
        val founder = game.ruleset.beliefs.values.first { it.type == com.unciv.models.ruleset.BeliefType.Founder }.name
        Religion(symbol, game, enemy(game)).apply { addBelief(founder) }.also { game.religions[symbol] = it }
        game.setTransients()
        symbol
    }

    fun export(game: GameInfo, name: String): File = File(root, "godot/.local/tests/$name.json").apply {
        parentFile.mkdirs(); writeText(UncivFiles.gameInfoToString(game, true))
    }
    fun copy(game: GameInfo): GameInfo = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(game, true))
    fun file(name: String, configure: (GameInfo) -> Unit): File {
        val game = game()
        native(game) { configure(game) }
        return export(game, name)
    }

    /**
     * 为真实窗口 smoke 导出可加载的持久 fixture（能力精确差分由本套件其它测试与独立原生期望覆盖，此处只导出状态，
     * 不经网关 DTO／UnitAbilityCommands 生成期望）：
     * - unit-abilities-scientist：己方大科学家(2,0)——加速研究可执行且消耗单位；学院因所在为中立地块不可执行（前端展示禁用原因）。
     * - unit-abilities-missionary：已创立宗教局 + 传教士(5,0)——传播宗教可执行，首次仅扣次数不消耗单位（验证非消耗路径）。
     * - unit-abilities-spaceship：首都已建 Apollo Program + SS Booster 驻首都——组装可执行、消耗部件并推进科学胜利进度。
     */
    fun emit() {
        val scientist = game()
        native(scientist) { add(scientist, "Great Scientist", HexCoord(2, 0)) }
        export(scientist, "unit-abilities-scientist")
        val missionary = ReligionFixtures.foundedGame()
        native(missionary) { addReligiousUnit(missionary, "Missionary", HexCoord(5, 0)) }
        export(missionary, "unit-abilities-missionary")
        val spaceship = game()
        native(spaceship) {
            capital(spaceship).cityConstructions.addBuilding("Apollo Program")
            add(spaceship, "SS Booster", center)
        }
        export(spaceship, "unit-abilities-spaceship")
        // 赠送城邦：军事单位强制落在城邦中心（绕过进入限制），双方已互相认识；窗口只验证真实交互与状态反馈。
        val gift = game(cityState = true)
        native(gift) {
            val cityState = gift.civilizations.first { it.isCityState }
            gift.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(cityState)
            val unit = BattleFixtures.add(gift, "Warrior", 2, 2)
            val tile = gift.tileMap[cityStateAt]
            unit.removeFromTile()
            tile.militaryUnit = unit
            unit.currentTile = tile
            gift.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
        }
        export(gift, "unit-abilities-gift")
    }

    /** 期望端：取该类型唯一可执行原生动作（可按标题／关联 Unique 过滤）并调用一次。 */
    fun nativeAbility(game: GameInfo, unit: MapUnit, type: UnitActionType, filter: (UnitAction) -> Boolean = { true }) =
        native(game) {
            val action = UnitActions.getUnitActions(unit, type).firstOrNull { it.action != null && filter(it) }
                ?: error("原生没有可执行的 ${type.name} 动作")
            action.action!!()
        }

    fun instantBuildFilter(improvement: String) = { action: UnitAction ->
        action.associatedUnique?.type == UniqueType.ConstructImprovementInstantly && action.title.contains(improvement)
    }
    val goldenAgeFilter: (UnitAction) -> Boolean = { it.associatedUnique?.type == UniqueType.OneTimeEnterGoldenAgeTurns }
}
