package com.unciv.logic.map.mapunit

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the GUI-free construction entry point shared by
 * [com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen] and the Godot gateway,
 * plus the read-only maintenance helper in
 * [com.unciv.logic.map.tile.TileImprovementFunctions.getMaintenance].
 */
@RunWith(BaseTestRunner::class)
class WorkerImprovementActionsTest {

    private val testGame = TestGame()
    private lateinit var civInfo: Civilization
    private lateinit var city: City
    private lateinit var worker: MapUnit
    private lateinit var tile: Tile
    private lateinit var farm: TileImprovement

    @Before
    fun initTheWorld() {
        testGame.makeHexagonalMap(3)
        civInfo = testGame.addCiv()
        for (tech in testGame.ruleset.technologies.values)
            civInfo.tech.addTechnology(tech.name)

        testGame.setTileTerrain(testGame.tileMap[0, 0].position, Constants.grassland)
        testGame.setTileTerrain(testGame.tileMap[1, 1].position, Constants.grassland)
        city = testGame.addCity(civInfo, testGame.tileMap[0, 0])

        // The city owns all tiles within distance 1, no addTileToCity needed for [1,1]
        tile = testGame.tileMap[1, 1]
        worker = testGame.addUnit("Worker", civInfo, tile)
        farm = testGame.ruleset.tileImprovements["Farm"]!!
    }

    @Test
    fun `null improvement submits nothing`() {
        var callbacks = 0
        assertFalse(WorkerImprovementActions.accept(tile, worker, null) { callbacks++ })
        assertEquals(0, callbacks)
        assertNull(tile.improvementInProgress)
    }

    @Test
    fun `accepting starts work and wakes the worker`() {
        worker.action = UnitActionType.Sleep.value

        var callbacks = 0
        assertTrue(WorkerImprovementActions.accept(tile, worker, farm) { callbacks++ })

        assertEquals(1, callbacks)
        assertEquals("Farm", tile.improvementInProgress)
        assertTrue("Work should take at least one turn", tile.turnsToImprovement > 0)
        assertNull("Accepting should wake a sleeping worker", worker.action)
    }

    @Test
    fun `re-accepting the same improvement does not restart work or consume stockpile again`() {
        val resource = testGame.createResource(UniqueType.Stockpiled.text)
        civInfo.gainStockpiledResource(resource, 2)
        // Register a *fresh* Farm carrying the stockpile cost, so its lazy uniqueMap is built with it
        farm = replaceImprovement("Farm", UniqueType.CostsResources.text.fillPlaceholders("1", resource.name))

        var callbacks = 0
        assertTrue(WorkerImprovementActions.accept(tile, worker, farm) { callbacks++ })
        assertEquals("Stockpile is paid when work begins", 1, civInfo.getResourceAmount(resource))
        assertEquals(1, callbacks)

        tile.doWorkerTurn(worker) // spend one turn of work
        val turnsLeft = tile.turnsToImprovement
        assertTrue(turnsLeft > 0)

        worker.action = UnitActionType.Sleep.value
        assertTrue(WorkerImprovementActions.accept(tile, worker, farm) { callbacks++ })

        assertEquals("Re-accepting must not charge the stockpile twice", 1, civInfo.getResourceAmount(resource))
        assertEquals("Re-accepting must not reset the remaining work", turnsLeft, tile.turnsToImprovement)
        assertEquals("Farm", tile.improvementInProgress)
        assertEquals(2, callbacks)
        assertNull("Re-accepting still wakes the worker", worker.action)
    }

    @Test
    fun `second improvement is queued after the first one`() {
        val mine = testGame.ruleset.tileImprovements["Mine"]!!

        worker.action = UnitActionType.Sleep.value
        assertTrue(WorkerImprovementActions.accept(tile, worker, farm, mine))
        assertNull(worker.action)
        assertEquals("Farm", tile.improvementInProgress)

        var guard = 0
        while (tile.improvementInProgress == "Farm" && guard++ < 50)
            tile.doWorkerTurn(worker)

        assertEquals("Farm", tile.improvement)
        assertEquals("The queued improvement should start next", "Mine", tile.improvementInProgress)
    }

    @Test
    fun `cancel only stops work without waking the worker or calling back`() {
        assertTrue(WorkerImprovementActions.accept(tile, worker, farm))
        worker.action = UnitActionType.Sleep.value

        // "Cancel improvement order" is not part of the base ruleset, the client synthesizes it
        val cancel = testGame.createTileImprovement().apply {
            name = Constants.cancelImprovementOrder
            turnsToBuild = -1
        }

        var callbacks = 0
        assertTrue(WorkerImprovementActions.accept(tile, worker, cancel) { callbacks++ })

        assertEquals("Cancelling must not trigger the accept callback", 0, callbacks)
        assertNull(tile.improvementInProgress)
        assertNull(tile.improvement)
        assertEquals("Cancelling leaves the worker sleeping", UnitActionType.Sleep.value, worker.action)
    }

    @Test
    fun `cancelling is a no-op when nothing is in progress`() {
        val cancel = testGame.createTileImprovement().apply {
            name = Constants.cancelImprovementOrder
            turnsToBuild = -1
        }
        assertTrue(WorkerImprovementActions.accept(tile, worker, cancel))
        assertNull(tile.improvementInProgress)
    }

    @Test
    fun `tiles reserved by CreatesOneImprovement reject worker orders`() {
        tile.improvementFunctions.markForCreatesOneImprovement("Farm")
        assertTrue(tile.isMarkedForCreatesOneImprovement("Farm"))

        val cancel = testGame.createTileImprovement().apply {
            name = Constants.cancelImprovementOrder
            turnsToBuild = -1
        }

        var callbacks = 0
        assertFalse(WorkerImprovementActions.accept(tile, worker, farm) { callbacks++ })
        assertFalse(WorkerImprovementActions.accept(tile, worker, cancel) { callbacks++ })

        assertEquals(0, callbacks)
        assertTrue("The reservation must survive rejected orders", tile.isMarkedForCreatesOneImprovement("Farm"))
    }

    @Test
    fun `maintenance matches uniques territory and exemptions`() {
        val improvement = testGame.createTileImprovement(
            UniqueType.ImprovementAllMaintenance.text.fillPlaceholders("1", "Gold"),
            UniqueType.ImprovementMaintenance.text.fillPlaceholders("2", "Gold")
        )

        // Owned tile: both uniques apply, reported as negative stats
        assertEquals(-3f, tile.improvementFunctions.getMaintenance(improvement, civInfo).gold, 0f)
        // Read-only: repeated calls and prior queries change nothing
        assertEquals(-3f, tile.improvementFunctions.getMaintenance(improvement, civInfo).gold, 0f)
        assertNull(tile.improvementInProgress)
        assertNull(tile.improvement)

        // Unowned tile: only the "everywhere" unique applies
        val unowned = testGame.getTile(2, 2)
        testGame.setTileTerrain(unowned.position, Constants.grassland)
        assertNull(unowned.getOwner())
        assertEquals(-1f, unowned.improvementFunctions.getMaintenance(improvement, civInfo).gold, 0f)

        // Tile filter exemption zeroes maintenance entirely
        val exemptCiv = testGame.addCiv(
            UniqueType.NoImprovementMaintenanceInSpecificTiles.text.fillPlaceholders(Constants.grassland)
        )
        assertEquals(0f, tile.improvementFunctions.getMaintenance(improvement, exemptCiv).gold, 0f)

        // Road maintenance modifier scales the result. This civ does not own the tile,
        // so only the "everywhere" unique (-1) applies before the -50% modifier.
        val discountedCiv = testGame.addCiv(
            UniqueType.RoadMaintenance.text.fillPlaceholders("-50")
        )
        assertEquals("Road maintenance modifier is applied", -0.5f, tile.improvementFunctions.getMaintenance(improvement, discountedCiv).gold, 0f)
    }

    /** Re-registers [name] as a fresh [TileImprovement] carrying [extraUnique], preserving buildability. */
    private fun replaceImprovement(name: String, extraUnique: String): TileImprovement {
        val original = testGame.ruleset.tileImprovements[name]!!
        val replacement = TileImprovement()
        replacement.name = original.name
        replacement.terrainsCanBeBuiltOn = original.terrainsCanBeBuiltOn
        replacement.turnsToBuild = original.turnsToBuild
        replacement.techRequired = original.techRequired
        replacement.uniques.addAll(original.uniques)
        replacement.uniques.add(extraUnique)
        testGame.ruleset.tileImprovements[name] = replacement
        return replacement
    }
}
