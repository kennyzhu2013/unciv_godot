package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.stats.Stat
import com.unciv.view.CityView
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 期望端独立加载原存档，直接使用原 UI 的 View 入口，不调用经济 DTO 或准备函数。 */
class CityEconomyCommandsTest {
    private fun scenario(name: String, configure: (GameInfo) -> Unit = {}): Pair<GameSession, GameInfo> {
        val file = EconomyFixtures.file("econ-$name", configure)
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }
    private fun id(session: GameSession) = EconomyFixtures.capital(session.game!!).id
    private fun economy(session: GameSession): JsonObject {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val result = run(session, "cityOptions", "cityId" to id(session))["data"]!!.jsonObject
        assertSame(game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
        return result["economy"]!!.jsonObject
    }
    private fun reject(session: GameSession, code: String, action: String, vararg args: Pair<String, Any?>) =
        rejectRequest(session, code, request(session, action, "cityId" to id(session), *args))

    private fun rejectRequest(session: GameSession, code: String, body: JsonObject) {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val result = session.handle(body)
        assertEquals(result.toString(), code, result["error"]?.jsonObject?.text("code"))
        assertSame("可预判拒绝不得替换当前局", game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
    }
    private fun apply(session: GameSession, expected: GameInfo, action: String,
                      vararg args: Pair<String, Any?>, operation: (CityView, GameView) -> Unit) {
        economy(session)
        native(expected) {
            val view = GameView(expected, expected.currentPlayerCiv)
            val city = view.civView.cities().first { it.id == id(session) }
            operation(city, view)
            city.updateCityStats()
        }
        val before = session.revision
        val game = session.game
        run(session, action, "cityId" to id(session), *args)
        assertSame(game, session.game)
        assertEquals(before + 1, session.revision)
        assertGameplayEquals(action, expected, session.game!!)
        assertEquals(EconomyFixtures.summary(expected), EconomyFixtures.summary(session.game!!))
        assertEquals(expected.currentPlayerCiv.getCivResourcesByName(), session.game!!.currentPlayerCiv.getCivResourcesByName())
    }
    private fun purchase(session: GameSession, expected: GameInfo, name: String, index: Int = -1) {
        apply(session, expected, "cityPurchase", "name" to name, "stat" to "Gold", "queueIndex" to index) { cv, _ ->
            val construction = expected.ruleset.buildings[name] ?: expected.ruleset.units[name]!!
            assertTrue(cv.constructions.purchaseConstruction(construction, index, Stat.Gold, null))
        }
    }
    private fun buy(session: GameSession, expected: GameInfo, at: HexCoord = EconomyFixtures.target) {
        apply(session, expected, "cityBuyTile", "x" to at.x, "y" to at.y) { cv, view ->
            assertTrue(cv.tryBuyTile(view.tileMapView.getTile(at)!!))
        }
    }
    private fun sell(session: GameSession, expected: GameInfo, name: String = "Market") {
        apply(session, expected, "citySellBuilding", "name" to name) { cv, _ ->
            assertTrue(cv.trySellBuilding(expected.ruleset.buildings[name]!!))
        }
    }

    @Test fun emitIndependentNativeSmokeExpectations() = EconomyFixtures.emit()

    @Test fun queryIsReadOnlyAndQuotesMatchNative() {
        val (session, expected) = scenario("quotes")
        val data = economy(session)
        assertEquals(expected.currentPlayerCiv.gold, data.integer("gold"))
        native(expected) {
            val city = EconomyFixtures.capital(expected)
            val view = GameView(expected, expected.currentPlayerCiv)
            val cv = view.civView.cities().first { it.id == city.id }
            val tiles = data["buyTiles"]!!.jsonArray.map { it.jsonObject }
            val visible = city.tilesInRange.filter { view.getTile(it).isVisible() }.map { it.position }.toSet()
            assertEquals(visible, tiles.map { HexCoord(it.integer("x"), it.integer("y")) }.toSet())
            for (tile in tiles) {
                val tv = view.tileMapView.getTile(HexCoord(tile.integer("x"), tile.integer("y")))!!
                val cost = if (cv.canBuyTile(tv)) cv.getGoldCostOfTile(tv) else null
                assertEquals(cost, tile["cost"]!!.jsonPrimitive.intOrNull)
                assertEquals(cost != null && cv.viewingCiv().hasStatToBuy(Stat.Gold, cost), tile["enabled"]!!.jsonPrimitive.boolean)
            }
            for (item in data["purchaseOptions"]!!.jsonArray.map { it.jsonObject }) {
                val c = expected.ruleset.units[item.text("name")] ?: expected.ruleset.buildings[item.text("name")]!!
                if (!item["supported"]!!.jsonPrimitive.boolean) continue
                val price = if (c.canBePurchasedWithStat(city, Stat.Gold)) c.getStatBuyCost(city, Stat.Gold) else null
                assertEquals(price, item["goldCost"]!!.jsonPrimitive.intOrNull)
            }
            assertEquals(city.cityConstructions.constructionQueue, data["queuePurchases"]!!.jsonArray.map { it.jsonObject.text("name") })
            val buildings = data["buildings"]!!.jsonArray.map { it.jsonObject }
            assertEquals(city.cityConstructions.builtBuildings.sorted(), buildings.map { it.text("name") })
            for (b in buildings) assertEquals(if (expected.ruleset.buildings[b.text("name")]!!.isSellable())
                city.getGoldForSellingBuilding(b.text("name")) else null, b["sellGold"]!!.jsonPrimitive.intOrNull)
        }
    }

    @Test fun completeFlowAndOriginalSaveReloadMatchNative() {
        val (session, expected) = scenario("flow")
        buy(session, expected)
        purchase(session, expected, "Monument")
        purchase(session, expected, "Warrior", 2)
        sell(session, expected)
        reject(session, "CANNOT_SELL_BUILDING", "citySellBuilding", "name" to "Workshop")
        val saved = run(session, "save", "name" to "economy-roundtrip").text("savedPath")
        run(session, "load", "path" to saved)
        val reloaded = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(expected, true))
        assertGameplayEquals("经济原格式重载", reloaded, session.game!!)
        assertEquals(EconomyFixtures.summary(reloaded), EconomyFixtures.summary(session.game!!))
        reject(session, "CANNOT_SELL_BUILDING", "citySellBuilding", "name" to "Workshop")
        val next = EconomyFixtures.nextTurn(reloaded)
        run(session, "nextTurn")
        assertGameplayEquals("经济回合恢复", next, session.game!!)
        assertFalse(economy(session)["hasSoldBuildingThisTurn"]!!.jsonPrimitive.boolean)
    }

    @Test fun consecutiveTilesAreRequoted() {
        val (session, expected) = scenario("tiles")
        buy(session, expected)
        buy(session, expected, EconomyFixtures.secondTarget)
    }

    @Test fun exactAndInsufficientTileGold() {
        for (extra in listOf(-1, 0)) {
            val (session, expected) = scenario("tile-money-$extra") { g ->
                val cost = EconomyFixtures.capital(g).expansion.getGoldCostOfTile(g.tileMap[EconomyFixtures.target])
                g.currentPlayerCiv.addGold(cost + extra - g.currentPlayerCiv.gold)
            }
            if (extra == 0) buy(session, expected)
            else reject(session, "CANNOT_BUY_TILE", "cityBuyTile", "x" to 2, "y" to 0)
        }
    }

    @Test fun tileOwnershipRangeAndAdjacencyRejectWithoutMutation() {
        val (session, _) = scenario("bad-tiles")
        for (at in listOf(HexCoord(0, 0), HexCoord(3, 0), HexCoord(4, 0), HexCoord(999, 999)))
            reject(session, "CANNOT_BUY_TILE", "cityBuyTile", "x" to at.x, "y" to at.y)
    }

    @Test fun hiddenTilesHaveNeitherQuoteNorOwnershipOracle() {
        val (session, _) = scenario("hidden")
        val game = session.game!!
        val tile = game.tileMap[EconomyFixtures.target]
        game.currentPlayerCiv.viewableTiles = game.currentPlayerCiv.viewableTiles - tile
        assertFalse(economy(session)["buyTiles"]!!.jsonArray.any { it.jsonObject.integer("x") == 2 && it.jsonObject.integer("y") == 0 })
        reject(session, "CANNOT_BUY_TILE", "cityBuyTile", "x" to 2, "y" to 0)
    }

    @Test fun unexploredAndHiddenOwnedTilesShareGenericRejection() {
        val (session, _) = scenario("visibility-oracle")
        val game = session.game!!
        val player = game.currentPlayerCiv
        val tile = game.tileMap[EconomyFixtures.target]
        game.currentPlayerCiv.viewableTiles = player.viewableTiles - tile
        fun message(): String {
            economy(session)
            val request = request(session, "cityBuyTile", "cityId" to id(session), "x" to 2, "y" to 0)
            rejectRequest(session, "CANNOT_BUY_TILE", request)
            return session.handle(request)["error"]!!.jsonObject.text("message")
        }
        val hidden = message()
        native(game) { EconomyFixtures.capital(game).expansion.takeOwnership(tile) }
        player.viewableTiles = player.viewableTiles - tile
        assertEquals(hidden, message())
        tile.setExplored(player, false)
        assertFalse(economy(session)["buyTiles"]!!.jsonArray.any { it.jsonObject.integer("x") == 2 && it.jsonObject.integer("y") == 0 })
        assertEquals(hidden, message())
    }

    @Test fun razingPurchaseAndBuildingTriggerFollowNative() {
        val (session, expected) = special("razing-trigger") { g ->
            EconomyFixtures.capital(g).isBeingRazed = true
            testBuilding(g, "Free [Worker] appears")
        }
        val before = session.game!!.currentPlayerCiv.units.getCivUnits().count()
        purchase(session, expected, "Economy Test Building")
        assertEquals(before + 1, session.game!!.currentPlayerCiv.units.getCivUnits().count())
        assertTrue(session.game!!.currentPlayerCiv.units.getCivUnits().any { it.name == "Worker" })
    }

    @Test fun puppetResistanceAndRazingTilePermissions() {
        for (state in listOf("puppet", "resistance", "razing")) {
            val (session, _) = scenario(state) { g ->
                val c = EconomyFixtures.capital(g)
                when (state) {
                    "puppet" -> c.isPuppet = true
                    "resistance" -> c.setFlag(CityFlags.Resistance, 2)
                    else -> c.isBeingRazed = true
                }
            }
            reject(session, "CANNOT_BUY_TILE", "cityBuyTile", "x" to 2, "y" to 0)
        }
    }

    @Test fun candidatePurchaseKeepsDuplicateUnitIndicesAndFullQueue() {
        val (session, expected) = scenario("full") { g ->
            EconomyFixtures.capital(g).cityConstructions.constructionQueue = ArrayList(List(10) { "Warrior" })
        }
        purchase(session, expected, "Warrior")
        assertEquals(10, EconomyFixtures.capital(session.game!!).cityConstructions.constructionQueue.size)
    }

    @Test fun indexedPurchasesPreserveWorkAndUseExactQueuePosition() {
        for (index in listOf(0, 1, 2)) {
            val (session, expected) = scenario("queue-$index") { g ->
                EconomyFixtures.capital(g).cityConstructions.inProgressConstructions["Warrior"] = 7
            }
            purchase(session, expected, if (index == 1) "Worker" else "Warrior", index)
        }
    }

    @Test fun militaryCivilianCanCoexistButSecondMilitaryIsRejected() {
        val (session, expected) = scenario("slots")
        purchase(session, expected, "Warrior")
        reject(session, "CANNOT_PURCHASE", "cityPurchase", "name" to "Warrior", "stat" to "Gold", "queueIndex" to -1)
        purchase(session, expected, "Worker")
        val units = session.game!!.currentPlayerCiv.units.getCivUnits().filter { it.currentTile.isCityCenter() }.toList()
        assertEquals(2, units.size)
        assertTrue(units.all { it.currentMovement == 0f })
    }

    @Test fun strictArgumentsRejectBeforeBackup() {
        val (session, _) = scenario("arguments")
        val base = request(session, "cityPurchase", "cityId" to id(session), "name" to "Warrior", "stat" to "Gold", "queueIndex" to -1)
        for (key in listOf("cityId", "name", "stat", "queueIndex")) {
            rejectRequest(session, "INVALID_ARGUMENT", JsonObject(base - key))
            rejectRequest(session, "INVALID_ARGUMENT", JsonObject(base + (key to JsonArray(emptyList()))))
        }
        for (index in listOf(value(0.5), value("2"), value(-2), value(99), value(1)))
            rejectRequest(session, "INVALID_ARGUMENT", JsonObject(base + ("queueIndex" to index)))
        reject(session, "INVALID_ARGUMENT", "cityPurchase", "name" to "NoSuchProject", "stat" to "Gold", "queueIndex" to -1)
        reject(session, "UNSUPPORTED", "cityPurchase", "name" to "Warrior", "stat" to "Faith", "queueIndex" to -1)
        reject(session, "UNSUPPORTED", "cityPurchase", "name" to "Nothing", "stat" to "Gold", "queueIndex" to 3)
    }

    @Test fun unsupportedUnitsAreRejectedBeforeRuleEligibility() {
        val (session, _) = scenario("unsupported")
        for (name in listOf("Trireme", "Fighter", "Atomic Bomb", "Great Scientist", "Missionary")) {
            if (!session.game!!.ruleset.units.containsKey(name)) continue
            reject(session, "UNSUPPORTED", "cityPurchase", "name" to name, "stat" to "Gold", "queueIndex" to -1)
        }
    }

    @Test fun saleReleasesSpecialistsAndOtherCityHasIndependentAllowance() {
        val (session, expected) = scenario("sale") { g ->
            g.currentPlayerCiv.cities.first { it.location == DevelopmentFixtures.secondCity }.cityConstructions.addBuilding(g.ruleset.buildings["Market"]!!)
        }
        sell(session, expected)
        assertEquals(1, EconomyFixtures.capital(session.game!!).population.getNewSpecialists()["Merchant"])
        val second = session.game!!.currentPlayerCiv.cities.first { it.location == DevelopmentFixtures.secondCity }.id
        native(expected) {
            val cv = GameView(expected, expected.currentPlayerCiv).civView.cities().first { it.id == second }
            assertTrue(cv.trySellBuilding(expected.ruleset.buildings["Market"]!!)); cv.updateCityStats()
        }
        run(session, "citySellBuilding", "cityId" to second, "name" to "Market")
        assertGameplayEquals("另一城市独立出售", expected, session.game!!)
    }

    @Test fun razingAndResistanceDoNotAddExtraSaleRestrictions() {
        for (resistance in listOf(false, true)) {
            val (session, expected) = scenario("sale-state-$resistance") { g ->
                if (resistance) EconomyFixtures.capital(g).setFlag(CityFlags.Resistance, 2)
                else EconomyFixtures.capital(g).isBeingRazed = true
            }
            sell(session, expected)
        }
    }

    @Test fun godModeUsesNativePerActionSemantics() {
        val (session, expected) = scenario("god") { it.gameParameters.godMode = true }
        buy(session, expected)
        purchase(session, expected, "Warrior")
        sell(session, expected)
        sell(session, expected, "Workshop")
    }

    /** 记录原生最后一类槽位消失时的既有分配保留行为，不在网关修正规则。 */
    @Test fun sellingLastSpecialistSlotPreservesNativeBehavior() {
        val (session, expected) = scenario("last-slot") { g ->
            val c = EconomyFixtures.capital(g)
            c.cityConstructions.removeBuilding(g.ruleset.buildings["Bank"]!!)
            c.population.specialistAllocations["Merchant"] = 1
        }
        sell(session, expected)
        assertEquals(0, EconomyFixtures.capital(session.game!!).population.getMaxSpecialists()["Merchant"])
        assertEquals(1, EconomyFixtures.capital(session.game!!).population.getNewSpecialists()["Merchant"])
    }

    /** 两端各自克隆规则容器，只添加新对象；不修改缓存内的基础规则对象或开放 Mod 入口。 */
    private fun special(name: String, setup: (GameInfo) -> Unit): Pair<GameSession, GameInfo> {
        val pair = scenario(name)
        for (g in listOf(pair.first.game!!, pair.second)) native(g) {
            g.ruleset = g.ruleset.clone()
            setup(g)
            g.currentPlayerCiv.cities.forEach { it.cityStats.update() }
        }
        return pair
    }
    private fun bonus(g: GameInfo, vararg uniques: String) {
        val b = Building().apply {
            name = "Economy Test Bonus"; cost = 50; ruleset = g.ruleset
            this.uniques.addAll(uniques)
        }
        g.ruleset.buildings[b.name] = b
        EconomyFixtures.capital(g).cityConstructions.addBuilding(b)
    }
    private fun testUnit(g: GameInfo, vararg uniques: String): BaseUnit = BaseUnit().apply {
        name = "Economy Test Unit"; cost = 40; movement = 2; strength = 8
        unitType = g.ruleset.units["Warrior"]!!.unitType
        this.uniques.addAll(uniques)
        g.ruleset.units[name] = this
        setRuleset(g.ruleset)
    }
    private fun testBuilding(g: GameInfo, vararg uniques: String): Building = Building().apply {
        name = "Economy Test Building"; cost = 60; ruleset = g.ruleset
        this.uniques.addAll(uniques)
        g.ruleset.buildings[name] = this
    }

    @Test fun exactAndInsufficientPurchaseMoney() {
        for (name in listOf("Warrior", "Monument")) for (extra in listOf(-1, 0)) {
            val (session, expected) = scenario("purchase-money-$name-$extra") { g ->
                val construction = g.ruleset.units[name] ?: g.ruleset.buildings[name]!!
                val cost = construction.getStatBuyCost(EconomyFixtures.capital(g), Stat.Gold)!!
                g.currentPlayerCiv.addGold(cost + extra - g.currentPlayerCiv.gold)
            }
            if (extra == 0) {
                purchase(session, expected, name)
                assertEquals(0, session.game!!.currentPlayerCiv.gold)
            } else reject(session, "CANNOT_PURCHASE", "cityPurchase", "name" to name, "stat" to "Gold", "queueIndex" to -1)
        }
    }

    @Test fun tileSpeedAndDiscountMatchNative() {
        for (speed in listOf("Quick", "Epic", "Marathon")) {
            val (session, expected) = scenario("tile-speed-$speed") { it.gameParameters.speed = speed }
            buy(session, expected)
        }
        val (session, expected) = special("tile-discount") { bonus(it, "[-50]% Gold cost of acquiring tiles [in all cities]") }
        buy(session, expected)
        buy(session, expected, EconomyFixtures.secondTarget)
    }

    @Test fun onlyPurchasableZeroPriceAndImmediateMovement() {
        val (session, expected) = special("zero-only-buy") { g ->
            testUnit(g, "Unbuildable", "Can be purchased for [0] [Gold] [in all cities]", "Can move immediately once bought")
            g.currentPlayerCiv.addGold(-g.currentPlayerCiv.gold)
        }
        val unit = expected.ruleset.units["Economy Test Unit"]!!
        assertFalse(unit.isBuildable(EconomyFixtures.capital(expected).cityConstructions))
        val option = economy(session)["purchaseOptions"]!!.jsonArray.first { it.jsonObject.text("name") == unit.name }.jsonObject
        assertEquals(0, option.integer("goldCost")); assertTrue(option["enabled"]!!.jsonPrimitive.boolean)
        purchase(session, expected, unit.name)
        assertEquals(2f, session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == unit.name }.currentMovement)
    }

    @Test fun purchaseDiscountAndIncreasingCounterFollowNative() {
        val (session, expected) = special("price-count") { g ->
            bonus(g, "[Gold] cost of purchasing items in cities [-25]%",
                "May buy [Warrior] units for [100] [Gold] [in all cities] at an increasing price ([20])")
        }
        purchase(session, expected, "Warrior")
        assertEquals(1, session.game!!.currentPlayerCiv.civConstructions.boughtItemsWithIncreasingPrice["Warrior"])
        for (g in listOf(session.game!!, expected)) native(g) {
            g.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" && it.currentTile.isCityCenter() }.destroy()
        }
        purchase(session, expected, "Warrior")
        assertEquals(2, session.game!!.currentPlayerCiv.civConstructions.boughtItemsWithIncreasingPrice["Warrior"])
    }

    @Test fun puppetPurchaseUniqueDoesNotEnableOtherEditing() {
        val (plain, _) = scenario("puppet-no-buy") { EconomyFixtures.capital(it).isPuppet = true }
        reject(plain, "CANNOT_PURCHASE", "cityPurchase", "name" to "Monument", "stat" to "Gold", "queueIndex" to -1)
        val (session, expected) = special("puppet-buy") { g ->
            EconomyFixtures.capital(g).isPuppet = true
            bonus(g, "May buy items in puppet cities")
        }
        purchase(session, expected, "Monument")
        reject(session, "CANNOT_SELL_BUILDING", "citySellBuilding", "name" to "Market")
        assertFalse(run(session, "cityOptions", "cityId" to id(session))["data"]!!.jsonObject["editable"]!!.jsonPrimitive.boolean)
    }

    @Test fun purchaseResistanceTechResourcesAndAlreadyBuiltReject() {
        for (state in listOf("resistance", "technology", "resource", "built", "unpurchasable")) {
            val (session, _) = special("purchase-$state") { g ->
                val b = testBuilding(g)
                when (state) {
                    "resistance" -> EconomyFixtures.capital(g).setFlag(CityFlags.Resistance, 2)
                    "technology" -> b.requiredTech = "Future Tech"
                    "resource" -> b.requiredResource = "Uranium"
                    "built" -> EconomyFixtures.capital(g).cityConstructions.addBuilding(b)
                    else -> b.uniques.add("Cannot be purchased")
                }
            }
            reject(session, "CANNOT_PURCHASE", "cityPurchase", "name" to "Economy Test Building", "stat" to "Gold", "queueIndex" to -1)
        }
    }

    @Test fun freeWonderAndUnsellableBuildingsReject() {
        val (free, _) = scenario("free-sale") { g ->
            val c = EconomyFixtures.capital(g)
            c.cityConstructions.freeBuildingsProvidedFromThisCity[c.id] = hashSetOf("Market")
        }
        reject(free, "CANNOT_SELL_BUILDING", "citySellBuilding", "name" to "Market")
        assertTrue(economy(free)["buildings"]!!.jsonArray.first { it.jsonObject.text("name") == "Market" }.jsonObject["isFree"]!!.jsonPrimitive.boolean)
        for (wonder in listOf(false, true)) {
            val (session, _) = special("unsellable-$wonder") { g ->
                val b = testBuilding(g, "Unsellable").apply { isWonder = wonder }
                EconomyFixtures.capital(g).cityConstructions.addBuilding(b)
            }
            reject(session, "CANNOT_SELL_BUILDING", "citySellBuilding", "name" to "Economy Test Building")
            val b = economy(session)["buildings"]!!.jsonArray.first { it.jsonObject.text("name") == "Economy Test Building" }.jsonObject
            assertEquals(JsonNull, b["sellGold"])
        }
    }

    @Test fun improvementBuildingIsUnsupportedEvenWithQueueMarker() {
        val (session, _) = special("specific-improvement") { g ->
            testBuilding(g, "Creates a [Academy] improvement on a specific tile")
            val c = EconomyFixtures.capital(g)
            c.cityConstructions.constructionQueue.add("Economy Test Building")
            assertTrue(c.cityConstructions.tryPlaceCreateOneImprovementMarker(g.ruleset.tileImprovements["Academy"]!!, g.tileMap[HexCoord(1, 0)]))
        }
        reject(session, "UNSUPPORTED", "cityPurchase", "name" to "Economy Test Building", "stat" to "Gold", "queueIndex" to 4)
    }

    @Test fun stockpileCostIsNotPaidTwiceForInvestedConstruction() {
        for (invested in listOf(false, true)) {
            val (session, expected) = special("stockpile-$invested") { g ->
                val resource = TileResource().apply { name = "Economy Stock"; uniques.add("Stockpiled") }
                g.ruleset.tileResources[resource.name] = resource
                val unit = testUnit(g, "Costs [7] [Economy Stock]")
                g.currentPlayerCiv.resourceStockpiles[resource.name] = if (invested) 13 else 20
                if (invested) EconomyFixtures.capital(g).cityConstructions.inProgressConstructions[unit.name] = 5
                g.currentPlayerCiv.cache.updateCivResources()
            }
            purchase(session, expected, "Economy Test Unit")
            assertEquals(13, session.game!!.currentPlayerCiv.resourceStockpiles["Economy Stock"])
        }
    }

    @Test fun purchaseExperienceAndPromotionsAreNative() {
        val (session, expected) = special("unit-bonuses") { g ->
            bonus(g, "New [Military] units start with [15] XP [in all cities]",
                "All newly-trained [Military] units [in all cities] receive the [Drill I] promotion")
        }
        purchase(session, expected, "Warrior")
        val actual = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" }
        val nativeUnit = expected.currentPlayerCiv.units.getCivUnits().first { it.name == "Warrior" }
        assertEquals(15, actual.promotions.XP)
        assertEquals(nativeUnit.promotions.promotions, actual.promotions.promotions)
        assertTrue("Drill I" in actual.promotions.promotions)
    }

    @Test fun resourceBuildingPurchaseAndSaleRestoreSupply() {
        val (session, expected) = special("resource-building") { g ->
            bonus(g, "Provides [3] [Iron]")
            testBuilding(g).requiredResource = "Iron"
        }
        val before = session.game!!.currentPlayerCiv.getCivResourcesByName()["Iron"]!!
        purchase(session, expected, "Economy Test Building")
        assertEquals(before - 1, session.game!!.currentPlayerCiv.getCivResourcesByName()["Iron"])
        sell(session, expected, "Economy Test Building")
        assertEquals(before, session.game!!.currentPlayerCiv.getCivResourcesByName()["Iron"])
    }

    @Test fun sellingSpecialBuildingRemovesItsImprovementNatively() {
        val (session, expected) = special("sell-improvement") { g ->
            val b = testBuilding(g, "Creates a [Academy] improvement on a specific tile")
            val c = EconomyFixtures.capital(g)
            assertTrue(c.cityConstructions.tryPlaceCreateOneImprovementMarker(g.ruleset.tileImprovements["Academy"]!!, g.tileMap[HexCoord(1, 0)]))
            c.cityConstructions.addBuilding(b)
        }
        assertEquals("Academy", session.game!!.tileMap[HexCoord(1, 0)].improvement)
        sell(session, expected, "Economy Test Building")
        assertNull(session.game!!.tileMap[HexCoord(1, 0)].improvement)
    }

    @Test fun otherCityAdjacencyDoesNotPermitBuyingAndTileSideEffectsMatch() {
        val (session, _) = scenario("other-adjacency") { g ->
            val other = g.currentPlayerCiv.cities.first { it.location == DevelopmentFixtures.secondCity }
            val target = g.tileMap[EconomyFixtures.target]
            for (tile in target.neighbors.filter { it.getCity() == EconomyFixtures.capital(g) }.toList())
                other.expansion.takeOwnership(tile)
        }
        reject(session, "CANNOT_BUY_TILE", "cityBuyTile", "x" to 2, "y" to 0)
        val (effects, expected) = scenario("tile-side-effects") { g ->
            val tile = g.tileMap[EconomyFixtures.target]
            tile.setTileResource("Wheat")
            tile.setImprovement("Farm")
            BattleFixtures.add(g, "Worker", 2, 0).action = "Sleep"
        }
        buy(effects, expected)
    }

    @Test fun executionFailureRollsBackNewUnitAndIdWithoutAdvancingRevision() {
        val (session, _) = scenario("rollback")
        val original = session.game!!
        // 测试专用故障注入：落位之后计算经验时抛错，不修改基础规则或增加生产环境钩子。
        EconomyFixtures.capital(original).cityConstructions.builtBuildingUniqueMap.addUnique(
            Unique("New [Military] units start with [invalid] XP [in all cities]"))
        val text = UncivFiles.gameInfoToString(original, false)
        val revision = session.revision
        val result = session.handle(request(session, "cityPurchase", "cityId" to id(session),
            "name" to "Warrior", "stat" to "Gold", "queueIndex" to -1))
        assertEquals("KERNEL_ERROR", result["error"]!!.jsonObject.text("code"))
        assertNotSame(original, session.game)
        assertEquals(text, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)
    }

    @Test fun strictTileAndSaleArgumentsReject() {
        val (session, _) = scenario("strict-other")
        for ((action, args) in listOf("cityBuyTile" to arrayOf("x" to 2, "y" to 0), "citySellBuilding" to arrayOf("name" to "Market"))) {
            val base = request(session, action, "cityId" to id(session), *args)
            for (key in listOf("cityId") + args.map { it.first }) {
                rejectRequest(session, "INVALID_ARGUMENT", JsonObject(base - key))
                for (bad in listOf(JsonNull, value(false), value(1.5), JsonArray(emptyList())))
                    rejectRequest(session, "INVALID_ARGUMENT", JsonObject(base + (key to bad)))
            }
        }
    }

    @Test fun unbuiltUnknownAndForeignBuildingsCannotBeSold() {
        val (session, _) = scenario("sale-reject")
        reject(session, "CANNOT_SELL_BUILDING", "citySellBuilding", "name" to "Monument")
        reject(session, "INVALID_ARGUMENT", "citySellBuilding", "name" to "Unknown")
        reject(session, "NOT_OWNED", "citySellBuilding", "cityId" to "foreign", "name" to "Market")
    }
}
