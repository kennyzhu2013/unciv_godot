package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.logic.files.UncivFiles
import java.io.File

/**
 * 只在测试中生成原格式发展场景（和平 AI、施工单位、可改善地块、受损设施、人口与专家槽位、可推进的生产），
 * 不向生产网关加入作弊命令。写盘后由网关与期望端各自独立加载，确保差分不共享内存状态。
 */
internal object DevelopmentFixtures {
    val root get() = File(System.getProperty("unciv.root"))

    /** 固定地块布局：全部在城市工作范围（距离 ≤3）内且由己方城市拥有。 */
    val center = HexCoord(0, 0)
    val farm = HexCoord(2, 0)       // 草原，普通改良（Farm）目标
    val resource = HexCoord(0, 2)   // 草原 + 小麦，资源改良目标
    val forest = HexCoord(2, -1)    // 草原 + 森林，清林／林场目标
    val road = HexCoord(-1, 2)      // 草原，道路／铁路目标
    val repair = HexCoord(1, 1)     // 草原 + 受损农场与道路，修复目标
    // 展示权限边界专用坐标（仅在对应测试场景中使用，不改变主发展场景）。
    val enemyWork = HexCoord(4, 0)  // 距离 4：草原、无归属，敌方在建改良目标
    val enemyWatch = HexCoord(3, 0) // 己方战士所在，使 (4,0) 对己方可见；UI 场景中兼作孤立道路格
    val contested = HexCoord(0, 3)  // 己方城内地块，测试中划归敌方城市并标记工作＋锁定
    // UI 展示场景（development-ui.json）专用坐标与常量，不改变主发展场景。
    val secondCity = HexCoord(-4, 4)  // 第二座己方城市：两城切换展示
    val stacked = HexCoord(2, 1)      // 同格堆叠己方军事＋民事单位：堆叠单位选择展示（Unciv 不允许两支军事单位同格）
    val longCityName = "超长城市名称展示测试：验证标签换行省略与面板宽度约束"
    // 中心六邻全部接路（与 HexMath 权威邻接集一致）：前三格 Road、后三格 Railroad。
    val centerRoadNeighbors = listOf(HexCoord(1, 0), HexCoord(-1, 0), HexCoord(0, 1))
    val centerRailNeighbors = listOf(HexCoord(0, -1), HexCoord(1, 1), HexCoord(-1, -1))

    fun <T> native(game: GameInfo, operation: () -> T): T = BattleFixtures.native(game, operation)

    /** 受控草原地图 + 己方城市（含专家槽位与全科技）+ 远方和平 AI；不预先放置任何单位。 */
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
            val ai = game.civilizations.single { it.civName == "Greece" }

            val origin = game.tileMap[center]
            for (tile in game.tileMap.tileList) {
                if (tile.aerialDistanceTo(origin) <= 4) {
                    tile.baseTerrain = Constants.grassland
                    tile.setTerrainFeatures(listOf())
                    tile.tileResource = null
                    tile.setTerrainTransients()
                }
            }

            // 场景地块：森林、资源、受损设施。
            game.tileMap[forest].addTerrainFeature("Forest")
            game.tileMap[resource].setTileResource(game.ruleset.tileResources["Wheat"]!!)
            val damaged: Tile = game.tileMap[repair].apply {
                setImprovement("Farm")
                setRoadStatus(RoadStatus.Road, player)
                improvementIsPillaged = true
                roadIsPillaged = true
            }
            game.tileMap[forest].setTerrainTransients()
            game.tileMap[resource].setTerrainTransients()
            damaged.setTerrainTransients()

            val city = player.addCity(center)
            for (tile in game.tileMap.tileList) {
                if (tile.aerialDistanceTo(origin) in 1..3 && tile.getCity() == null) {
                    city.tiles.add(tile.position)
                    tile.setOwningCity(city)
                }
            }
            // 提供专家槽位（商人／工程师），供专家分配差分使用。
            city.cityConstructions.addBuilding(game.ruleset.buildings["Market"]!!)
            city.cityConstructions.addBuilding(game.ruleset.buildings["Workshop"]!!)
            city.population.setPopulation(5)
            // 基础队列为 Nothing，避免 nextTurn 触发“需要选择生产”决策；队列差分会自行重设。
            city.cityConstructions.addToQueue(PerpetualConstruction.perpetualConstructionsMap[PerpetualConstruction.Idle.name]!!)

            // 只研究场景所需科技；让玩家始终在研究一个昂贵科技，避免 nextTurn 触发“请选择科技”决策，
            // 且不会在测试回合数内完成（不给全科技，否则 Future Tech 会永久打开科技选择）。
            // Guilds 解锁“生产转黄金”，Education 解锁“生产转科研”，供持续生产队列差分使用。
            for (name in listOf("Agriculture", "Mining", "The Wheel", "Guilds", "Education", "Railroads"))
                player.tech.addTechnology(name)
            player.tech.techsToResearch = arrayListOf("Globalization")
            ai.addCity(HexCoord(6, 0))

            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            game.setTransients()
            city.cityStats.update()
        }
        return game
    }

    fun file(name: String = "development", configure: (GameInfo) -> Unit = {}): File {
        val game = game()
        native(game) { configure(game) }
        return File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    /**
     * UI 展示专用场景（development-ui.json）：在主发展场景上叠加两城切换、堆叠单位、六方向道路／铁路、
     * 孤立道路、超长城市名、可见敌方施工与“差一锤完工”的生产队列，供 Godot smoke 的展示与流程断言消费。
     */
    fun uiDisplayGame(): GameInfo {
        val game = game()
        native(game) {
            val player = game.currentPlayerCiv
            val capital = player.cities.single { it.location == center }
            capital.name = longCityName
            // 第二座己方城市：队列放 Nothing，避免 nextTurn 触发“需要选择生产”决策。
            val second = player.addCity(secondCity)
            second.cityConstructions.addToQueue(PerpetualConstruction.perpetualConstructionsMap[PerpetualConstruction.Idle.name]!!)
            // 中心六邻全部接路：Road×3 ＋ Railroad×3；repair 格（(1,1)，含受损农场）升级为未受损铁路。
            for (at in centerRoadNeighbors) game.tileMap[at].setRoadStatus(RoadStatus.Road, player)
            for (at in centerRailNeighbors) game.tileMap[at].setRoadStatus(RoadStatus.Railroad, player)
            game.tileMap[repair].roadIsPillaged = false
            // 孤立道路：enemyWatch (3,0) 有路且六邻均无路（哨位战士同格）。
            game.tileMap[enemyWatch].setRoadStatus(RoadStatus.Road, player)
            (centerRoadNeighbors + centerRailNeighbors + listOf(enemyWatch))
                .forEach { game.tileMap[it].setTerrainTransients() }
            // 同格堆叠己方军事＋民事单位：Unciv 规则不允许两支军事单位同格，军事＋民事单位可堆叠，
            // 足以驱动前端“堆叠单位选择器”。先放军事（Warrior）再放民事（Worker），后者可进入己方军事所在格。
            BattleFixtures.add(game, "Warrior", stacked.x, stacked.y)
            BattleFixtures.add(game, "Worker", stacked.x, stacked.y)
            // 可见敌方施工：AI 工人在 (4,0) 在建农场。
            enemyConstruction(game)
            // 首都队列覆盖为单项 Warrior，进度 = 调整后成本 - 1：下一回合完工，供“队列推进到生成单位”断言。
            val warrior = game.ruleset.units["Warrior"]!!
            capital.cityConstructions.constructionQueue.clear()
            capital.cityConstructions.addToQueue(warrior)
            capital.cityConstructions.inProgressConstructions[warrior.name] = warrior.getProductionCost(player, capital) - 1
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            game.setTransients()
            capital.cityStats.update()
            second.cityStats.update()
        }
        return game
    }

    /** 写盘 UI 展示场景存档；与 [file] 相同为纯原生存档格式，由网关 load 命令直接加载。 */
    fun uiFile(name: String = "development-ui"): File {
        val game = uiDisplayGame()
        return File(root, "godot/.local/tests/$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    /** 在场景地块上放置一个满行动力的己方工人；每次调用前该地块应无其他单位。 */
    fun worker(game: GameInfo, at: HexCoord) = BattleFixtures.add(game, "Worker", at.x, at.y)
    fun city(game: GameInfo) = game.currentPlayerCiv.cities.single()
    fun tile(game: GameInfo, at: HexCoord): Tile = game.tileMap[at]

    /**
     * 在 [enemyWork] 制造一块对己方可见、但归属敌方的在建改良：
     * 直接排入工程队列并放置敌方工人，再在相邻的 [enemyWatch] 放置己方战士提供可见性。
     * 用于验证快照只对己方地块或己方施工单位暴露 improvementInProgress／turnsToImprovement。
     */
    fun enemyConstruction(game: GameInfo) {
        val ai = game.civilizations.single { it.civName == "Greece" }
        game.tileMap[enemyWork].queueImprovement("Farm", 5)
        BattleFixtures.add(game, "Worker", enemyWork.x, enemyWork.y, ai)
        BattleFixtures.add(game, "Warrior", enemyWatch.x, enemyWatch.y)
    }

    /**
     * 把己方城内的 [contested] 地块划归敌方城市，并标记为被该敌方城市工作且锁定。
     * 该地块仍在己方城市工作范围内，用于验证 citizenTiles 对非己方地块不泄漏 worked／locked。
     */
    fun foreignLockedTileInRange(game: GameInfo) {
        val ai = game.civilizations.single { it.civName == "Greece" }
        val aiCity = ai.cities.single()
        val myCity = game.currentPlayerCiv.cities.single()
        myCity.tiles.remove(contested)
        myCity.workedTiles.remove(contested)
        myCity.lockedTiles.remove(contested)
        game.tileMap[contested].setOwningCity(aiCity)
        aiCity.tiles.add(contested)
        aiCity.workedTiles.add(contested)
        aiCity.lockedTiles.add(contested)
    }
}
