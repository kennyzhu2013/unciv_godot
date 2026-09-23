package com.unciv.godot

import com.unciv.Constants
import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Counter
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import java.io.File

/**
 * 测试专用原格式宗教场景；期望端只使用原生 ReligionManager／类型化 UnitActions 与无逻辑 Java 桥接，
 * 不依赖 ReligionCommands、GameSession、DTO 或前端资格函数。所有信仰、单位与额度仅在导出初始 fixture 前设置。
 */
internal object ReligionFixtures {
    val root get() = BattleFixtures.root
    val center = HexCoord(0, 0)   // 己方首都：创立宗教的城市中心
    val aiCity = HexCoord(6, 0)   // 远方和平 AI 城市

    fun enemy(game: GameInfo): Civilization = BattleFixtures.enemy(game)
    fun <T> native(game: GameInfo, operation: () -> T): T = BattleFixtures.native(game, operation)
    fun player(game: GameInfo): Civilization = game.currentPlayerCiv
    fun religion(game: GameInfo): ReligionManager = player(game).religionManager
    fun capital(game: GameInfo) = player(game).cities.single { it.location == center }

    /**
     * 和平两文明基础局：己方首都（人口 3、生产 Idle、科研昂贵科技避免无关待决）＋远方 AI 城市；
     * 信仰预置为原生万神殿费用＋首位预言家费用＋100，使导出时尚无万神殿，选万神殿后正常 nextTurn 必生成预言家。
     */
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
            val ai = enemy(game)
            val origin = game.tileMap[center]
            val aiOrigin = game.tileMap[aiCity]
            for (tile in game.tileMap.tileList) {
                // 同时把首都与远方 AI 城市周边推平为草原，保证预言家可放在城市中心（含非己方城市中心场景）。
                if (tile.aerialDistanceTo(origin) <= 4 || tile.aerialDistanceTo(aiOrigin) <= 1) {
                    tile.baseTerrain = Constants.grassland
                    tile.setTerrainFeatures(listOf())
                    tile.tileResource = null
                    tile.setTerrainTransients()
                }
            }
            val city = player.addCity(center)
            for (tile in game.tileMap.tileList) {
                if (tile.aerialDistanceTo(origin) in 1..3 && tile.getCity() == null) {
                    city.tiles.add(tile.position)
                    tile.setOwningCity(city)
                }
            }
            city.population.setPopulation(3)
            city.cityConstructions.addToQueue(PerpetualConstruction.perpetualConstructionsMap[PerpetualConstruction.Idle.name]!!)
            // 只研究一个不会在测试回合内完成的昂贵科技，避免 nextTurn 触发“请选择科技”待决。
            for (name in listOf("Agriculture", "Pottery", "Animal Husbandry"))
                player.tech.addTechnology(name)
            player.tech.techsToResearch = arrayListOf("Globalization")
            ai.addCity(aiCity).cityConstructions.constructionQueue = arrayListOf("Nothing")
            val manager = player.religionManager
            manager.storedFaith = manager.faithForPantheon() + manager.faithForNextGreatProphet() + 100
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            game.setTransients()
            city.cityStats.update()
        }
        return game
    }

    fun file(name: String = "religion", configure: (GameInfo) -> Unit = {}): File {
        val game = game()
        native(game) { configure(game) }
        return File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    /** 正常推进一回合（clone+setTransients），与网关 nextTurn 相同副本流程；用于让原生自动生成预言家。 */
    fun nextTurn(game: GameInfo): GameInfo = native(game) {
        game.clone().also {
            it.setTransients()
            com.unciv.UncivGame.Current.gameInfo = it
            it.nextTurn()
        }
    }

    fun prophet(game: GameInfo): MapUnit = player(game).units.getCivUnits()
        .first { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion) }

    fun belief(game: GameInfo, name: String): Belief = game.ruleset.beliefs[name]!!

    /** 期望端可用信条（精确复刻原生 Pantheon/ReligiousBeliefs Picker 谓词，不构造 Picker 实例）：
     * 类别匹配槽类型或 Any；未被任何宗教占用（getReligionWithBelief 全局搜索）；OnlyAvailable/Unavailable 经 isAvailable(state)。 */
    fun availableBeliefs(game: GameInfo, type: BeliefType): List<Belief> {
        val civ = player(game)
        return game.ruleset.beliefs.values.filter { it.type == type || type == BeliefType.Any }
            .filter { civ.religionManager.getReligionWithBelief(it) == null }
            .filter { it.isAvailable(civ.state) }
    }

    /**
     * 期望端使用预言家：只取对应类型的可执行原生动作并调用一次，委托原生 foundReligion(unit)／
     * useProphetForEnhancingReligion(unit) 及动作自身的副作用与 consume()。不额外扣行动力或销毁单位。
     */
    fun useProphet(game: GameInfo, unit: MapUnit, found: Boolean): Unit = native(game) {
        val type = if (found) UnitActionType.FoundReligion else UnitActionType.EnhanceReligion
        val action = UnitActions.getUnitActions(unit, type).firstOrNull { it.action != null }
            ?: error("原生没有可执行的 ${type.name} 动作")
        action.action!!()
    }

    /** 期望端完成选择：一次 chooseBeliefs（万神殿／扩展／强化／免费信条）。 */
    fun chooseBeliefs(game: GameInfo, beliefNames: List<String>, useFreeBeliefs: Boolean): Unit = native(game) {
        val manager = religion(game)
        manager.chooseBeliefs(beliefNames.map { belief(game, it) }, useFreeBeliefs)
    }

    /** 期望端最终创立：经无逻辑 Java 桥接调用原生 internal foundReligion(displayName, name)，随后采用全部信条。 */
    fun foundReligion(game: GameInfo, displayName: String, religionId: String, beliefNames: List<String>): Unit = native(game) {
        val manager = religion(game)
        check(manager.religionState == ReligionState.FoundingReligion)
        NativeReligionBridge.foundReligion(manager, displayName, religionId)
        manager.chooseBeliefs(beliefNames.map { belief(game, it) }, manager.usingFreeBeliefs())
    }

    // ---- 状态构造器（全部走原生流程，导出前设定；供各待决场景加载）----
    fun export(game: GameInfo, name: String): File =
        File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }

    fun prophetName(game: GameInfo): String = religion(game).getGreatProphetEquivalent()!!.name

    /**
     * 在指定城市中心放置一位大预言家（用于无万神殿直接创立、非圣城／非己方强化等 nextTurn 无法生成的场景）。
     * 己方城市中心正常落位；非己方城市中心原生 canMoveTo 会拒绝平民进入，故测试专用强制落位，
     * 以构造“非己方城市创立被拒”等归属守卫场景（仅存在于测试 fixture，不进入生产网关）。
     * 强制落位可经导出／重载保留：Tile.setUnitTransients 从城市中心格的 civilianUnit 槽恢复 currentTile，不校验 canMoveTo。
     */
    fun placeProphet(game: GameInfo, coord: HexCoord = center): MapUnit = native(game) {
        val unit = game.tileMap.placeUnitNearTile(coord, prophetName(game), player(game))!!
        unit.religion = religion(game).religion?.name
        val tile = game.tileMap[coord]
        if (unit.currentTile.position != coord) {
            unit.removeFromTile()
            tile.civilianUnit = unit
            unit.currentTile = tile
        }
        unit
    }

    fun firstPantheon(game: GameInfo): String = availableBeliefs(game, BeliefType.Pantheon).first().name

    fun choosePantheon(game: GameInfo) = chooseBeliefs(game, listOf(firstPantheon(game)), religion(game).usingFreeBeliefs())

    /** 按 Counter 遍历顺序为每个槽挑一个互不重复的可用信条，模拟用户逐项选择（不使用网关资格函数）。 */
    fun pickBeliefs(game: GameInfo, counter: Counter<BeliefType>): List<String> {
        val used = HashSet<String>()
        val result = ArrayList<String>()
        for ((type, count) in counter) repeat(count) {
            val pick = availableBeliefs(game, type).first { it.name !in used }
            used.add(pick.name)
            result.add(pick.name)
        }
        return result
    }

    fun firstSymbol(game: GameInfo): String = game.ruleset.religions.first { !game.religions.containsKey(it) }

    /** 万神殿已建、正常回合生成预言家（停在首都中心）的场景。 */
    fun pantheonProphetGame(): GameInfo {
        var game = game()
        native(game) { choosePantheon(game) }
        return nextTurn(game)
    }

    /** 仅建万神殿（state Pantheon，无预言家）并授予一个免费 Pantheon 额度：用于扩展万神殿场景。 */
    fun pantheonGame(): GameInfo = game().also { g -> native(g) {
        choosePantheon(g)
        religion(g).freeBeliefs.add(BeliefType.Pantheon.name, 1)
    } }

    /** 处于 FoundingReligion 待决：withPantheon 先建万神殿再正常回合生成预言家；否则直接放置预言家从 None 创立。 */
    fun foundingGame(withPantheon: Boolean): GameInfo {
        val game = if (withPantheon) pantheonProphetGame() else game()
        native(game) {
            if (!withPantheon) placeProphet(game)
            useProphet(game, prophet(game), true)
        }
        return game
    }

    /** 已完成主宗教（state Religion，首都为圣城）。 */
    fun foundedGame(): GameInfo {
        val game = foundingGame(true)
        native(game) {
            val manager = religion(game)
            val picks = pickBeliefs(game, manager.getBeliefsToChooseAtFounding())
            val symbol = firstSymbol(game)
            foundReligion(game, symbol, symbol, picks)
        }
        return game
    }

    /** 处于 EnhancingReligion 待决：完成主宗教后补足信仰、正常回合生成第二位预言家并在首都中心强化。 */
    fun enhancingGame(): GameInfo {
        var game = foundedGame()
        native(game) { religion(game).storedFaith = religion(game).faithForNextGreatProphet() + 100 }
        game = nextTurn(game)
        native(game) { useProphet(game, prophet(game), false) }
        return game
    }

    /** 处于 EnhancingReligion 待决，但预言家在非圣城（远方 AI 城市中心）使用：验证强化不限定圣城／己方城市。 */
    fun enhancingGameAtNonHolyCity(): GameInfo {
        val game = foundedGame()
        native(game) {
            placeProphet(game, aiCity)
            useProphet(game, prophet(game), false)
        }
        return game
    }

    /** 处于 FoundingReligion 待决，且指定符号已被他方宗教占用：返回场景与该已占用符号。
     * 占位宗教须实际含一个 Founder 信条，符号才算被占用、该信条才从己方候选中剔除（getAllBeliefsOrdered 非空）。 */
    fun foundingGameWithTakenSymbol(): Pair<GameInfo, String> {
        val game = foundingGame(true)
        val taken = native(game) {
            val symbol = firstSymbol(game)
            val founder = availableBeliefs(game, BeliefType.Founder).first().name
            val placeholder = com.unciv.models.Religion(symbol, game, enemy(game))
            placeholder.addBelief(founder)
            game.religions[symbol] = placeholder
            game.setTransients()
            symbol
        }
        return game to taken
    }
}
