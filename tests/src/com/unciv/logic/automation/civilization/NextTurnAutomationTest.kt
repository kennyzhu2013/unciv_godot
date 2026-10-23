package com.unciv.logic.automation.civilization

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm.AStarPathfinding
import com.unciv.testing.TestGame
import com.unciv.testing.TestRunnerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.runners.Parameterized.UseParametersRunnerFactory

@RunWith(Parameterized::class)
@UseParametersRunnerFactory(TestRunnerFactory::class)
class NextTurnAutomationTest(private val algorithm: PathfindingAlgorithm) {
    companion object {
        @Suppress("unused")
        @Parameters
        @JvmStatic
        fun parameters() = TestRunnerFactory.Parameters.pathfinding
    }

    private lateinit var civInfo: Civilization

    val testGame = TestGame()

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = (algorithm == AStarPathfinding)
        testGame.makeHexagonalMap(7)
        civInfo = testGame.addCiv()
        val capital = testGame.addCity(civInfo, testGame.tileMap[0,0])
        assertTrue(capital.isCapital())
    }

    private val purchasePositions = listOf(HexCoord(-2, -2), HexCoord(-2, -1), HexCoord(2, 0))

    /** 只保留三个同价高收益候选；使用标准文明，确保原格式重载不依赖临时规则。 */
    private fun goldPurchaseFixture(): GameInfo {
        val fixture = TestGame()
        UncivGame.Current.settings.useAStarPathfinding = (algorithm == AStarPathfinding)
        fixture.makeHexagonalMap(3)
        val civ = fixture.addCiv(fixture.ruleset.nations["Rome"]!!, isPlayer = true)
        val city = fixture.addCity(civ, fixture.getTile(0, 0))
        city.cityConstructions.constructionQueue.clear()
        city.cityConstructions.constructionQueue.add("Nothing")
        for (position in purchasePositions) fixture.getTile(position).setTileResource("Wheat")
        fixture.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
        fixture.gameInfo.currentPlayer = civ.civID
        fixture.gameInfo.turns = 40
        return UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(fixture.gameInfo, false)).also {
            UncivGame.Current.gameInfo = it
        }
    }

    @Test
    fun `gold tile purchases are stable across reloads and turn clones`() {
        val initial = goldPurchaseFixture()
        val civ = initial.currentPlayerCiv
        val city = civ.cities.single()
        val costs = purchasePositions.map { city.expansion.getGoldCostOfTile(initial.tileMap[it]) }
        assertEquals("候选地块同价", 1, costs.toSet().size)
        civ.addGold(costs.first() - civ.gold)
        val raw = UncivFiles.gameInfoToString(initial, false)
        repeat(32) { replay ->
            val loaded = UncivFiles.gameInfoFromString(raw)
            val game = if (replay % 2 == 0) loaded else loaded.clone().also { it.setTransients() }
            UncivGame.Current.gameInfo = game
            val buyer = game.currentPlayerCiv
            val capital = buyer.cities.single()
            val before = capital.tiles.toSet()
            for (position in purchasePositions) {
                val tile = game.tileMap[position]
                assertTrue("候选必须可见且未拥有", tile.isVisible(buyer) && tile.getOwner() == null)
                val stats = tile.stats.getTileStats(buyer)
                assertTrue("候选必须满足原生收益门槛", stats.food + stats.production >= 3)
            }
            UseGoldAutomation.useGold(buyer)
            assertEquals("第 $replay 次重放按 x、y 坐标稳定破同分",
                setOf(purchasePositions.first()), capital.tiles - before)
            assertEquals("只够购买一块地", 0, buyer.gold)
        }
    }

    @Test
    fun `gold tile purchases keep separate equal priority coordinates`() {
        val game = goldPurchaseFixture()
        val civ = game.currentPlayerCiv
        val city = civ.cities.single()
        val before = city.tiles.toSet()
        civ.addGold(500 - civ.gold)
        UseGoldAutomation.useGold(civ)
        assertEquals("同资源且 x 相同的不同地块不能在排序映射中合并",
            purchasePositions.toSet(), city.tiles - before)
        assertTrue(civ.gold in 0 until 500)
    }

    @Test
    fun `gold tile purchases preserve resource priority before coordinates`() {
        val game = goldPurchaseFixture()
        val civ = game.currentPlayerCiv
        val city = civ.cities.single()
        val luxury = game.tileMap[purchasePositions.last()]
        luxury.setTileResource("Gems")
        civ.cache.updateSightAndResources()
        val before = city.tiles.toSet()
        civ.addGold(city.expansion.getGoldCostOfTile(luxury) - civ.gold)
        UseGoldAutomation.useGold(civ)
        assertEquals("资源优先级仍高于坐标顺序", setOf(luxury.position), city.tiles - before)
        assertEquals(0, civ.gold)
    }

    @Test
    fun `automateSettlerEscorting replaces low hp escort`() {
        val settler = testGame.addUnit("Settler", civInfo, testGame.tileMap[0,2])
        val highHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,1])
        val lowHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,2])
        lowHpWarrior.takeDamage(90)
        lowHpWarrior.startEscorting()
        assertTrue(settler.isEscorting())

        // Act
        NextTurnAutomation.automateSettlerEscorting(civInfo)
        assertEquals("settler should not have moved, else test is invalid", testGame.tileMap[0,2], settler.currentTile)

        // Assert
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,2], highHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,1], lowHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", highHpWarrior, settler.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", settler, highHpWarrior.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", null, lowHpWarrior.getOtherEscortUnit())
    }

    @Test
    fun `automateSettlerEscorting replaces low hp escort even with no movement`() {
        // AStar fails this because UnitMovement#canUnitSwapToReachableTile
        // calls escortedUnit.movement.canMoveTo(includeOtherEscortUnit = false) 
        // and escortedUnit.movement.canUnitSwapToReachableTile(checkEscorted = false)
        // but AStar assumes includeOtherEscortUnit always true.
        val settler1 = testGame.addUnit("Settler", civInfo, testGame.tileMap[0,2])
        val settler2 = testGame.addUnit("Settler", civInfo, testGame.tileMap[0,1])
        val highHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,1])
        val lowHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,2])
        lowHpWarrior.takeDamage(90)
        lowHpWarrior.startEscorting()
        settler2.currentMovement = 0f
        assertEquals(lowHpWarrior, settler1.getOtherEscortUnit())
        assertEquals(highHpWarrior, settler2.getOtherEscortUnit())

        // Act
        NextTurnAutomation.automateSettlerEscorting(civInfo)
        assertEquals("settlers should not have moved, else test is invalid", testGame.tileMap[0,2], settler1.currentTile)
        assertEquals("settlers should not have moved, else test is invalid", testGame.tileMap[0,1], settler2.currentTile)

        // Assert
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,2], highHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,1], lowHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", highHpWarrior, settler1.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", settler1, highHpWarrior.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", lowHpWarrior, settler2.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", settler2, lowHpWarrior.getOtherEscortUnit())
    }
}
