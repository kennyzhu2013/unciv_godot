package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
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
                      vararg args: Pair<String, Any?>, cityId: String = id(session),
                      operation: (CityView, GameView) -> Unit) {
        economy(session)
        native(expected) {
            val view = GameView(expected, expected.currentPlayerCiv)
            val city = view.civView.cities().first { it.id == cityId }
            operation(city, view)
            city.updateCityStats()
        }
        val before = session.revision
        val game = session.game
        run(session, action, "cityId" to cityId, *args)
        assertSame(game, session.game)
        assertEquals(before + 1, session.revision)
        assertGameplayEquals(action, expected, session.game!!)
        assertEquals(EconomyFixtures.summary(expected), EconomyFixtures.summary(session.game!!))
        assertEquals(expected.currentPlayerCiv.getCivResourcesByName(), session.game!!.currentPlayerCiv.getCivResourcesByName())
    }
    private fun purchase(session: GameSession, expected: GameInfo, name: String, index: Int = -1,
                         stat: Stat = Stat.Gold, cityId: String = id(session)) {
        apply(session, expected, "cityPurchase", "name" to name, "stat" to stat.name,
            "queueIndex" to index, cityId = cityId) { cv, _ ->
            val construction = expected.ruleset.buildings[name] ?: expected.ruleset.units[name]!!
            assertTrue(cv.constructions.purchaseConstruction(construction, index, stat, null))
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
        assertEquals(expected.currentPlayerCiv.religionManager.storedFaith, data.integer("faith"))
        native(expected) {
            val city = EconomyFixtures.capital(expected)
            val view = GameView(expected, expected.currentPlayerCiv)
            val cv = view.civView.cities().first { it.id == city.id }
            val statNames = data["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
                .first { it["supported"]!!.jsonPrimitive.boolean }["quotes"]!!.jsonArray
                .map { it.jsonObject.text("stat") }
            // 前端不应自行换算：每种货币都由内核生成一条报价，顺序固定。
            assertEquals(listOf("Gold", "Faith"), statNames)
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
                if (!item["supported"]!!.jsonPrimitive.boolean) {
                    // 未接入类别仍逐币种给出拒绝原因，不让前端因缺字段自行解释。
                    assertTrue(item["quotes"]!!.jsonArray.map { it.jsonObject }
                        .all { it["cost"] == JsonNull && !it["enabled"]!!.jsonPrimitive.boolean && it.text("reason").isNotEmpty() })
                    continue
                }
                val c = (expected.ruleset.units[item.text("name")] ?: expected.ruleset.buildings[item.text("name")]!!)
                    as INonPerpetualConstruction
                for (quote in item["quotes"]!!.jsonArray.map { it.jsonObject }) {
                    val stat = Stat.valueOf(quote.text("stat"))
                    // 报价与可购买性完全对齐原生 API：不用金币公式推算信仰价格。
                    val price = if (c.canBePurchasedWithStat(city, stat)) c.getStatBuyCost(city, stat) else null
                    assertEquals(price, quote["cost"]!!.jsonPrimitive.intOrNull)
                    assertEquals(price != null && city.cityConstructions.isConstructionPurchaseAllowed(c, stat, price),
                        quote["enabled"]!!.jsonPrimitive.boolean)
                }
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
        // 信仰本期已接入：仅有未开放币种仍是 UNSUPPORTED，不能购买的币种由原生规则拒绝。
        reject(session, "CANNOT_PURCHASE", "cityPurchase", "name" to "Warrior", "stat" to "Faith", "queueIndex" to -1)
        reject(session, "UNSUPPORTED", "cityPurchase", "name" to "Warrior", "stat" to "Science", "queueIndex" to -1)
        reject(session, "INVALID_ARGUMENT", "cityPurchase", "name" to "Warrior", "stat" to "NotAStat", "queueIndex" to -1)
        reject(session, "UNSUPPORTED", "cityPurchase", "name" to "Nothing", "stat" to "Gold", "queueIndex" to 3)
    }

    /**
     * 核武购买：科技、曼哈顿计划与铀到位后完全走原生 purchaseConstruction，
     * 「曼哈顿计划前置」「铀消耗」与放置能力都不由网关推算。
     */
    @Test fun nuclearPurchaseMatchesNative() {
        val (session, expected) = scenario("nuke-buy") { g ->
            NavalFixtures.research(g, "Nuclear Fission")
            DiplomacyFixtures.resource(g, g.currentPlayerCiv, "Uranium", 2)
            EconomyFixtures.capital(g).cityConstructions.addBuilding(g.ruleset.buildings["Manhattan Project"]!!)
            // 先接触希腊：购买后的原生资源刷新会重算可见地块，两端若从不同 HashSet 顺序首次接触，
            // 会生成位置不确定的同一条见面通知。
            g.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(BattleFixtures.enemy(g))
            g.currentPlayerCiv.popupAlerts.clear()
            g.currentPlayerCiv.notifications.clear()
        }
        val atomicBomb = economy(session)["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
            .first { it.text("name") == "Atomic Bomb" }
        assertTrue("核武购买已接入", atomicBomb["supported"]!!.jsonPrimitive.boolean)
        val gold = atomicBomb["quotes"]!!.jsonArray.map { it.jsonObject }.first { it.text("stat") == "Gold" }
        assertTrue(gold.toString(), gold["enabled"]!!.jsonPrimitive.boolean)
        val cost = native(expected) {
            expected.ruleset.units["Atomic Bomb"]!!.getStatBuyCost(EconomyFixtures.capital(expected), Stat.Gold)
        }
        assertEquals(cost, gold["cost"]!!.jsonPrimitive.intOrNull)
        purchase(session, expected, "Atomic Bomb")
        val bought = unit(session.game!!, "Atomic Bomb")
        assertTrue("核武停在城市中心", bought.currentTile.isCityCenter())
        assertEquals(0f, bought.currentMovement)
    }

    /** 空军购买：科技与 Oil 到位后完全走原生 purchaseConstruction 放置，报价也不由网关推算。 */
    @Test fun airUnitPurchaseMatchesNative() {
        val (session, expected) = scenario("air-buy") { g ->
            NavalFixtures.research(g, "Radar")
            DiplomacyFixtures.resource(g, g.currentPlayerCiv, "Oil", 2)
            // 先接触希腊：购买后的原生资源刷新会重算可见地块，若此时首次接触，两端会从
            // HashSet 迭代出的不同希腊地块生成同一条见面通知，位置不确定。
            g.currentPlayerCiv.diplomacyFunctions.makeCivilizationsMeet(BattleFixtures.enemy(g))
            g.currentPlayerCiv.popupAlerts.clear()
            g.currentPlayerCiv.notifications.clear()
        }
        val fighter = economy(session)["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
            .first { it.text("name") == "Fighter" }
        assertTrue("空军购买已接入", fighter["supported"]!!.jsonPrimitive.boolean)
        val gold = fighter["quotes"]!!.jsonArray.map { it.jsonObject }.first { it.text("stat") == "Gold" }
        assertTrue(gold.toString(), gold["enabled"]!!.jsonPrimitive.boolean)
        val cost = native(expected) {
            expected.ruleset.units["Fighter"]!!.getStatBuyCost(EconomyFixtures.capital(expected), Stat.Gold)
        }
        assertEquals(cost, gold["cost"]!!.jsonPrimitive.intOrNull)
        purchase(session, expected, "Fighter")
        val bought = unit(session.game!!, "Fighter")
        assertTrue("空军停在城市中心", bought.currentTile.isCityCenter())
        assertEquals(0f, bought.currentMovement)
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
    /**
     * 信仰场景：已完成主宗教的首都 + 5000 信仰。邻城辖地（4,0）供传教士跨境施法：
     * 使命单位自带“可无开放边界进入外国领土”的原生 unique，施法目标仍是原生 `tile.owningCity`。
     */
    private fun faithScenario(name: String, configure: (GameInfo) -> Unit = {}): Pair<GameSession, GameInfo> {
        val game = ReligionFixtures.foundedGame()
        native(game) {
            val player = game.currentPlayerCiv
            ReligionFixtures.enemy(game).cities.first { it.location == ReligionFixtures.aiCity }
                .expansion.takeOwnership(game.tileMap[HexCoord(4, 0)])
            player.religionManager.storedFaith = 5000
            player.addGold(2000 - player.gold)
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            game.setTransients()
            player.cities.forEach { it.cityStats.update() }
            configure(game)
        }
        val file = ReligionFixtures.export(game, name)
        return load(file) to UncivFiles.gameInfoFromString(file.readText())
    }
    /** 两端各自克隆规则容器后添加合成项目；不修改缓存内的基础规则对象。 */
    private fun faithSpecial(name: String, setup: (GameInfo) -> Unit): Pair<GameSession, GameInfo> {
        val pair = faithScenario(name)
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
        val gold = option["quotes"]!!.jsonArray.map { it.jsonObject }.first { it.text("stat") == "Gold" }
        assertEquals(0, gold["cost"]!!.jsonPrimitive.intOrNull)
        assertTrue(gold["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(option["quotes"]!!.jsonArray.map { it.jsonObject }.first { it.text("stat") == "Faith" }["enabled"]!!.jsonPrimitive.boolean)
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

    /**
     * 信仰购买传教士：价格、扣款、宗教归属、行动力罚没全部由原生 API 决定；
     * 购买后经正常回合恢复行动力，再经网关能力入口执行同一次原生传教闭包。
     */
    @Test fun faithPurchaseOfMissionaryInstallsNativeReligionAndSpreads() {
        var (session, expected) = faithScenario("faith-missionary")
        val capital = EconomyFixtures.capital(session.game!!)
        val before = capital.civ.religionManager.storedFaith
        val cost = session.game!!.ruleset.units["Missionary"]!!.getStatBuyCost(capital, Stat.Faith)!!
        assertTrue("信仰余额应足以购买传教士", cost in 1 until before)
        purchase(session, expected, "Missionary", stat = Stat.Faith)
        assertEquals(before - cost, session.game!!.currentPlayerCiv.religionManager.storedFaith)
        val actual = session.game!!.currentPlayerCiv.units.getCivUnits().single { it.name == "Missionary" }
        val nativeUnit = expected.currentPlayerCiv.units.getCivUnits().single { it.name == "Missionary" }
        assertEquals(expected.currentPlayerCiv.religionManager.religion!!.name, actual.religion)
        assertEquals(nativeUnit.religion, actual.religion)
        // 原生 construct(boughtWith) 罚没行动力；传播次数来自 CanSpreadReligion 修饰符。
        assertEquals(0f, actual.currentMovement)
        assertEquals(nativeUnit.currentMovement, actual.currentMovement)
        expected = NavalFixtures.apply(expected, "nextTurn")
        run(session, "nextTurn")
        assertGameplayEquals("购买传教士后回合", expected, session.game!!)
        // 进入 AI 城辖地（非城中心）：使命单位的原生 unique 允许无开放边界进入，目标由 owningCity 决定。
        val target = HexCoord(4, 0)
        expected = NavalFixtures.apply(expected, "move", dto("unitId" to actual.id, "x" to target.x, "y" to target.y))
        run(session, "move", "unitId" to actual.id, "x" to target.x, "y" to target.y)
        assertGameplayEquals("传教士进入邻城辖地", expected, session.game!!)
        // 长途移动耗尽行动力：再过一个正常回合，让原生恢复行动力后再执行传教。
        expected = NavalFixtures.apply(expected, "nextTurn")
        run(session, "nextTurn")
        assertGameplayEquals("传教前回合", expected, session.game!!)
        val options = run(session, "unitAbilityOptions", "unitId" to actual.id)["data"]!!.jsonObject
        val spread = options["candidates"]!!.jsonArray.map { it.jsonObject }.first { it.text("type") == "spreadReligion" }
        assertTrue("传教能力应可用：${spread.text("reason")}", spread["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(2, spread.integer("usesLeft"))
        native(expected) {
            UnitActions.getUnitActions(NavalFixtures.unit(expected, actual.id), UnitActionType.SpreadReligion)
                .first { it.action != null }.action!!.invoke()
        }
        run(session, "unitAbility", "unitId" to actual.id,
            "candidateId" to spread.text("id"), "token" to options.text("token"))
        assertGameplayEquals("传教结果应与原生一致", expected, session.game!!)
        val after = run(session, "unitAbilityOptions", "unitId" to actual.id)["data"]!!.jsonObject
        assertEquals(1, after["candidates"]!!.jsonArray.map { it.jsonObject }
            .first { it.text("type") == "spreadReligion" }.integer("usesLeft"))
    }

    /** 信仰建筑与合成伟人：只按原生的 CanBePurchasedWithStat／价格函数解释，不用金币公式推算。 */
    @Test fun faithBuildingAndGreatPersonPurchaseMatchNative() {
        val (session, expected) = faithSpecial("faith-general") { g ->
            testBuilding(g, "Unbuildable", "Can be purchased with [Faith] [in all cities]")
            testUnit(g, "Great Person", "Can be purchased with [Faith] [in all cities]")
        }
        val building = economy(session)["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
            .first { it.text("name") == "Economy Test Building" }
        assertTrue(building["quotes"]!!.jsonArray.map { it.jsonObject }.first { it.text("stat") == "Faith" }["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(building["quotes"]!!.jsonArray.map { it.jsonObject }.first { it.text("stat") == "Gold" }["enabled"]!!.jsonPrimitive.boolean)
        purchase(session, expected, "Economy Test Building", stat = Stat.Faith)
        assertTrue("Economy Test Building" in EconomyFixtures.capital(session.game!!).cityConstructions.builtBuildings)
        reject(session, "CANNOT_PURCHASE", "cityPurchase", "name" to "Economy Test Building", "stat" to "Gold", "queueIndex" to -1)
        purchase(session, expected, "Economy Test Unit", stat = Stat.Faith)
        assertEquals(1, session.game!!.currentPlayerCiv.units.getCivUnits().count { it.name == "Economy Test Unit" })
    }

    /** 信仰按队列索引购买：与金币共用原生命令，只替换币种；同名重复项按索引消耗对应位置。 */
    @Test fun faithQueuePurchaseUsesExactDuplicateIndex() {
        val (session, expected) = faithSpecial("faith-queue") { g ->
            testUnit(g, "Can be purchased with [Faith] [in all cities]")
            EconomyFixtures.capital(g).cityConstructions.constructionQueue =
                arrayListOf("Warrior", "Economy Test Unit", "Economy Test Unit", "Nothing")
        }
        val queue = EconomyFixtures.capital(session.game!!).cityConstructions.constructionQueue.toList()
        purchase(session, expected, "Economy Test Unit", index = 2, stat = Stat.Faith)
        val after = EconomyFixtures.capital(session.game!!).cityConstructions.constructionQueue.toList()
        assertEquals(queue.take(2), after.take(2))
        assertEquals(queue[3], after[2])
        assertEquals(1, session.game!!.currentPlayerCiv.units.getCivUnits().count { it.name == "Economy Test Unit" })
    }

    /** 连续信仰购买涨价与傀儡例外：计数与可买性均由原生 unique 解释，网关不维护自己的价格表。 */
    @Test fun faithIncreasingPriceAndPuppetExceptionFollowNative() {
        val (session, expected) = faithSpecial("faith-increasing") { g ->
            testUnit(g, "Can be purchased with [Faith] [in all cities]")
            bonus(g, "May buy [Economy Test Unit] units for [100] [Faith] [in all cities] at an increasing price ([20])")
        }
        purchase(session, expected, "Economy Test Unit", stat = Stat.Faith)
        assertEquals(1, session.game!!.currentPlayerCiv.civConstructions.boughtItemsWithIncreasingPrice["Economy Test Unit"])
        for (g in listOf(session.game!!, expected)) native(g) {
            g.currentPlayerCiv.units.getCivUnits().first { it.name == "Economy Test Unit" && it.currentTile.isCityCenter() }.destroy()
        }
        purchase(session, expected, "Economy Test Unit", stat = Stat.Faith)
        assertEquals(2, session.game!!.currentPlayerCiv.civConstructions.boughtItemsWithIncreasingPrice["Economy Test Unit"])
        val (puppet, puppetExpected) = faithSpecial("faith-puppet") { g ->
            EconomyFixtures.capital(g).isPuppet = true
            testBuilding(g, "Can be purchased with [Faith] [in all cities]")
            bonus(g, "May buy items in puppet cities")
        }
        purchase(puppet, puppetExpected, "Economy Test Building", stat = Stat.Faith)
    }

    /** 海军购买不再按类别禁购：内陆城由原生 canPlaceNewUnit 拒绝，沿海城购买后能原生移动。 */
    @Test fun navalPurchaseFollowsNativeCoastalPlacement() {
        val (inland, _) = scenario("navy-inland")
        reject(inland, "CANNOT_PURCHASE", "cityPurchase", "name" to "Trireme", "stat" to "Gold", "queueIndex" to -1)
        var (session, expected) = scenario("navy-coastal") { g ->
            NavalFixtures.research(g, "Sailing")
            for (at in listOf(HexCoord(0, -1), HexCoord(1, -1))) {
                g.tileMap[at].baseTerrain = "Coast"
                g.tileMap[at].setTerrainTransients()
            }
            // 研究前置科技会留下“科技完成”待决弹窗，导出前清空以保证 nextTurn 可达。
            g.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
            g.setTransients()
            g.currentPlayerCiv.cities.forEach { it.cityStats.update() }
        }
        assertTrue("首都应与海岸相邻", EconomyFixtures.capital(session.game!!).isCoastal())
        purchase(session, expected, "Trireme")
        val ship = session.game!!.currentPlayerCiv.units.getCivUnits().single { it.name == "Trireme" }
        // 海军在沿岸城市中与原生一致地停在城市中心，购买后不能立即移动。
        assertTrue("海军应停在沿海城中心", ship.currentTile.isCityCenter())
        assertEquals(0f, ship.currentMovement)
        expected = NavalFixtures.apply(expected, "nextTurn")
        run(session, "nextTurn")
        assertGameplayEquals("购买海军后回合", expected, session.game!!)
        val to = listOf(HexCoord(0, -1), HexCoord(1, -1)).first { it != ship.currentTile.position }
        expected = NavalFixtures.apply(expected, "move", dto("unitId" to ship.id, "x" to to.x, "y" to to.y))
        run(session, "move", "unitId" to ship.id, "x" to to.x, "y" to to.y)
        assertGameplayEquals("购买的海军应可原生移动", expected, session.game!!)
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
