package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityFocus
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stats
import com.unciv.view.CityView
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 城市发展差分：期望端直接构造原生 [GameView]／[CityView] 并按原 CityScreen 时序调用 tryXxx，
 * 网关端走 cityCitizen／cityFocus／cityAvoidGrowth／cityResetCitizens／citySpecialists／cityQueue 命令；
 * 两端各自独立加载同一原生存档，逐步比较完整持久化状态。
 * 边界：不覆盖买地、购买生产、出售建筑与 CreatesOneImprovement 目标地块选择。
 */
class CityDevelopmentCommandsTest {
    private class Native(var game: GameInfo)

    /** 生成一个发展场景存档；网关与期望端各自独立加载。 */
    private fun scenario(name: String, configure: (GameInfo) -> Unit = {}): Pair<GameSession, Native> {
        val file = DevelopmentFixtures.file(name, configure)
        return load(file) to Native(UncivFiles.gameInfoFromString(file.readText()))
    }

    private fun cityId(session: GameSession) = session.game!!.currentPlayerCiv.cities.first().id

    private fun cityDto(session: GameSession, id: String = cityId(session)): JsonObject =
        run(session, "cityOptions", "cityId" to id)["data"]!!.jsonObject

    private fun citizenTiles(session: GameSession, id: String = cityId(session)): List<JsonObject> =
        cityDto(session, id)["citizenTiles"]!!.jsonArray.map { it.jsonObject }

    private fun constructions(session: GameSession, id: String = cityId(session)): List<JsonObject> =
        cityDto(session, id)["constructions"]!!.jsonArray.map { it.jsonObject }

    private fun queueNames(session: GameSession, id: String = cityId(session)): List<String> =
        cityDto(session, id)["queue"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun tileWhere(session: GameSession, id: String = cityId(session), predicate: (JsonObject) -> Boolean): Pair<Int, Int> {
        val tile = citizenTiles(session, id).first(predicate)
        return tile.integer("x") to tile.integer("y")
    }

    private fun enabledConstruction(session: GameSession, type: String, id: String = cityId(session)): String =
        constructions(session, id).first { it.text("type") == type && it["enabled"]!!.jsonPrimitive.boolean }.text("name")

    /**
     * 先读一次 cityOptions 断言只读不改存档／不推进 revision；再让期望端复现网关闭包，网关端发同一命令，
     * 最后完整差分。cityId 由 [id] 统一注入，调用方只传其余参数。
     */
    private fun apply(session: GameSession, nat: Native, step: String, action: String, id: String,
                      vararg args: Pair<String, Any?>, expected: (CityView, GameView) -> Unit) {
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        run(session, "cityOptions", "cityId" to id)
        assertEquals("只读查询不得改动存档", before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals("只读查询不得推进 revision", revision, session.revision)
        DevelopmentFixtures.native(nat.game) {
            val view = GameView(nat.game, nat.game.currentPlayerCiv)
            val cv = view.civView.cities().first { it.id == id }
            expected(cv, view)
        }
        run(session, action, "cityId" to id, *args)
        assertGameplayEquals(step, nat.game, session.game!!)
        assertCityStateMatches(session, nat, id, step)
    }

    /**
     * 除完整持久化差分外，再显式比较瞬态城市统计、资源供应与只读 DTO（plan 要求）：
     * 期望端用同一 [CityDevelopmentCommands] 直接对原生存档构造 cityDto，防止“存档相同但界面产出错误”。
     */
    private fun assertCityStateMatches(session: GameSession, nat: Native, id: String, step: String) {
        val expected = DevelopmentFixtures.native(nat.game) {
            val view = GameView(nat.game, nat.game.currentPlayerCiv)
            val cv = view.civView.cities().first { it.id == id }
            dto("city" to CityDevelopmentCommands(nat.game).cityDto(cv),
                "resources" to nat.game.currentPlayerCiv.getCivResourcesByName().toSortedMap().toString())
        }
        val actual = dto("city" to JsonObject(cityDto(session, id) - "economy"),
            "resources" to session.game!!.currentPlayerCiv.getCivResourcesByName().toSortedMap().toString())
        assertEquals("$step 瞬态城市统计／资源供应／DTO 应一致", expected.toString(), actual.toString())
        assertNativeCityValues(session, nat, id, step)
    }

    /**
     * 独立于共用 cityDto 构造器的交叉校验（plan line 14）：直接从原生 [com.unciv.logic.city.City]／
     * [com.unciv.logic.city.CityStats]／[com.unciv.logic.map.tile.TileStatFunctions] 取城市统计、来源明细、
     * 人口／专家、地块产出与队列进度作为期望值，按既有一位小数约定归一化后逐项比对网关 DTO。
     * 由于期望值不再经过 [CityDevelopmentCommands.cityDto]，可捕获两端共用构造器时无法暴露的取数／序列化错误。
     */
    private fun assertNativeCityValues(session: GameSession, nat: Native, id: String, step: String) {
        val actual = cityDto(session, id)
        DevelopmentFixtures.native(nat.game) {
            val city = nat.game.currentPlayerCiv.cities.first { it.id == id }
            val civ = nat.game.currentPlayerCiv
            // 城市统计与来源明细：来自原生 CityStats，不经 cityDto。
            assertEquals("$step 城市统计", expectedStats(city.cityStats.currentCityStats).toString(),
                actual["stats"]!!.jsonObject.toString())
            val breakdown = actual["statsBreakdown"]!!.jsonObject
            val nativeBreakdown = city.cityStats.finalStatList
            assertEquals("$step 统计来源项集合", nativeBreakdown.keys, breakdown.keys)
            for ((key, stats) in nativeBreakdown)
                assertEquals("$step 统计来源 $key", expectedStats(stats).toString(), breakdown[key]!!.jsonObject.toString())
            // 人口／增长。
            assertEquals("$step 人口", city.population.population, actual.integer("population"))
            val growth = actual["growth"]!!.jsonObject
            assertEquals("$step 存粮", city.population.foodStored, growth.integer("foodStored"))
            assertEquals("$step 每回合粮食", city.foodForNextTurn(), growth.integer("foodPerTurn"))
            assertEquals("$step 增长状态", city.isGrowing(), growth["isGrowing"]!!.jsonPrimitive.boolean)
            assertEquals("$step 饥荒状态", city.isStarving(), growth["isStarving"]!!.jsonPrimitive.boolean)
            // 专家：上限／已分配来自 CityPopulationManager，专家产出来自 CityStats。
            val specialists = actual["specialists"]!!.jsonObject
            assertEquals("$step 手动专家", city.manualSpecialists, specialists["manual"]!!.jsonPrimitive.boolean)
            assertEquals("$step 空闲人口", city.population.getFreePopulation(), specialists.integer("freePopulation"))
            for (slot in specialists["slots"]!!.jsonArray.map { it.jsonObject }) {
                val name = slot.text("name")
                assertEquals("$step 专家上限 $name", city.population.getMaxSpecialists()[name], slot.integer("max"))
                assertEquals("$step 已分配专家 $name", city.population.getNewSpecialists()[name], slot.integer("assigned"))
                assertEquals("$step 专家产出 $name", expectedStats(city.cityStats.getStatsOfSpecialist(name)).toString(),
                    slot["stats"]!!.jsonObject.toString())
            }
            // 地块产出：对 DTO 暴露的每块坐标，从原生 TileStatFunctions.getTileStats 独立取期望值。
            for (tile in actual["citizenTiles"]!!.jsonArray.map { it.jsonObject }) {
                val x = tile.integer("x"); val y = tile.integer("y")
                val nativeTile = nat.game.tileMap[HexCoord(x, y)]
                assertEquals("$step 地块产出 $x,$y", expectedStats(nativeTile.stats.getTileStats(city, civ)).toString(),
                    tile["yields"]!!.jsonObject.toString())
            }
            // 队列进度：名称序列与逐项预计工期来自原生 CityConstructions。
            assertEquals("$step 队列", city.cityConstructions.constructionQueue,
                actual["queue"]!!.jsonArray.map { it.jsonPrimitive.content })
            for (entry in actual["queueEntries"]!!.jsonArray.map { it.jsonObject }) {
                val name = entry.text("name"); val index = entry.integer("index")
                // 持续项目（Nothing/生产转黄金等）无工期与投入：DTO 置 null，此处需同样判定。
                val construction = nat.game.ruleset.buildings[name] ?: nat.game.ruleset.units[name]
                    ?: PerpetualConstruction.perpetualConstructionsMap[name]
                val perpetual = construction is PerpetualConstruction
                val firstOfKind = city.cityConstructions.isFirstConstructionOfItsKind(index, name)
                val nativeTurns = if (perpetual) null
                    else city.cityConstructions.turnsToConstruction(name, firstOfKind).takeIf { it in 0..100000 }
                assertEquals("$step 工期 $name", nativeTurns, entry["turns"]?.jsonPrimitive?.intOrNull)
                val nativeWorkDone = if (perpetual || !firstOfKind) null else city.cityConstructions.getWorkDone(name)
                assertEquals("$step 已投入 $name", nativeWorkDone, entry["workDone"]?.jsonPrimitive?.intOrNull)
            }
        }
    }

    /** 复刻 statsDto 的一位小数约定：仅保留非零有限项，作为原生 Stats 的期望 JSON。 */
    private fun expectedStats(stats: Stats): JsonObject {
        val entries = ArrayList<Pair<String, Any?>>()
        for ((key, value) in stats) {
            val rounded = (value * 10).roundToInt() * 0.1f
            if (rounded != 0f && rounded.isFinite()) entries.add(key.name to rounded)
        }
        return dto(*entries.toTypedArray())
    }

    /** 两端各推进一回合并比较完整状态（覆盖增长后的自动人口分配）。 */
    private fun advance(session: GameSession, nat: Native, step: String) {
        DevelopmentFixtures.native(nat.game) {
            val clone = nat.game.clone()
            clone.setTransients()
            UncivGame.Current.gameInfo = clone
            clone.nextTurn()
            nat.game = clone
        }
        run(session, "nextTurn")
        assertGameplayEquals(step, nat.game, session.game!!)
    }

    /** 加入生产队列：与网关闭包一致，仅当加入项成为当前生产时重分配人口。 */
    private fun addQueue(session: GameSession, nat: Native, id: String, name: String, step: String) {
        apply(session, nat, step, "cityQueue", id, "type" to "add", "name" to name) { cv, _ ->
            cv.tryAddToQueue(name)
            val updated = cv.constructions.constructionQueue
            if (updated.isNotEmpty() && updated.first() == name) cv.tryReassignPopulation()
            cv.updateCityStats()
        }
    }

    private fun tv(view: GameView, x: Int, y: Int) = view.tileMapView.getTile(HexCoord(x, y))!!

    private fun rejected(session: GameSession, command: JsonObject, code: String) {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val result = session.handle(command)
        assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(game, false))
        // 非法请求在预校验阶段抛出，早于备份／提交：session.game 必须仍是同一对象。
        // 只比较旧引用的序列化文本会在网关换回备份副本时漏判，故同时校验对象身份。
        assertSame("非法请求不得替换 GameInfo 对象", game, session.game)
    }

    // region 人口／工作地块

    @Test fun citizenWorkUnworkLockUnlockMatchNative() {
        val (session, nat) = scenario("dev-citizen")
        val id = cityId(session)
        // 初始人口已被自动分配，freePopulation 为 0：先撤回一块已工作地块腾出人口。
        val (ux, uy) = tileWhere(session, id) { it["canUnwork"]!!.jsonPrimitive.boolean }
        apply(session, nat, "撤回地块", "cityCitizen", id, "x" to ux, "y" to uy, "type" to "unwork") { cv, view ->
            cv.tryStopWorkingTile(tv(view, ux, uy)); cv.updateCityStats()
        }
        val (wx, wy) = tileWhere(session, id) { it["canWork"]!!.jsonPrimitive.boolean }
        apply(session, nat, "工作地块", "cityCitizen", id, "x" to wx, "y" to wy, "type" to "work") { cv, view ->
            cv.tryWorkTile(tv(view, wx, wy)); cv.updateCityStats()
        }
        apply(session, nat, "锁定地块", "cityCitizen", id, "x" to wx, "y" to wy, "type" to "lock") { cv, view ->
            cv.tryLockTile(tv(view, wx, wy)); cv.updateCityStats()
        }
        apply(session, nat, "解锁地块", "cityCitizen", id, "x" to wx, "y" to wy, "type" to "unlock") { cv, view ->
            cv.tryUnlockTile(tv(view, wx, wy)); cv.updateCityStats()
        }
        apply(session, nat, "撤回新地块", "cityCitizen", id, "x" to wx, "y" to wy, "type" to "unwork") { cv, view ->
            cv.tryStopWorkingTile(tv(view, wx, wy)); cv.updateCityStats()
        }
        assertTrue("撤回后该地块不再工作",
            citizenTiles(session, id).none { it.integer("x") == wx && it.integer("y") == wy && it["worked"]!!.jsonPrimitive.boolean })
    }

    @Test fun resetCitizensUnlocksAndReassignsMatchingNative() {
        val (session, nat) = scenario("dev-reset")
        val id = cityId(session)
        val (lx, ly) = tileWhere(session, id) { it["canLock"]!!.jsonPrimitive.boolean }
        apply(session, nat, "锁定地块", "cityCitizen", id, "x" to lx, "y" to ly, "type" to "lock") { cv, view ->
            cv.tryLockTile(tv(view, lx, ly)); cv.updateCityStats()
        }
        apply(session, nat, "重置人口分配", "cityResetCitizens", id) { cv, _ ->
            cv.tryReassignPopulation(resetLocked = true); cv.updateCityStats()
        }
        assertTrue("重置后不应有锁定地块", citizenTiles(session, id).none { it["locked"]!!.jsonPrimitive.boolean })
    }

    @Test fun tileWorkedBySecondCityIsNotWorkableFromFirst() {
        val second = HexCoord(-4, 0)
        val shared = HexCoord(-2, 0)
        val (session, _) = scenario("dev-shared") { game ->
            val player = game.currentPlayerCiv
            val city1 = player.cities.first { it.location == DevelopmentFixtures.center }
            val city2 = player.addCity(second)
            game.setTransients()
            // 让 city2 只工作共享地块，避免与其自动分配叠加；同时确保 city1 不工作它。
            city2.getWorkedTiles().toList().forEach { city2.stopWorkingTile(it) }
            city1.stopWorkingTile(game.tileMap[shared])
            city2.workTile(game.tileMap[shared])
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
        }
        val id1 = session.game!!.currentPlayerCiv.cities.first { it.location == DevelopmentFixtures.center }.id
        val sharedTile = citizenTiles(session, id1).firstOrNull { it.integer("x") == shared.x && it.integer("y") == shared.y }
        assertNotNull("共享地块应落在城市1工作范围内", sharedTile)
        assertFalse("被他城工作的地块不可再分配", sharedTile!!["canWork"]!!.jsonPrimitive.boolean)
        rejected(session, request(session, "cityCitizen", "cityId" to id1, "x" to shared.x, "y" to shared.y, "type" to "work"), "CANNOT_ASSIGN")
    }

    @Test fun citizenTilesDoesNotLeakForeignWorkedOrLockedState() {
        // 己方工作范围内的一块地划归敌方城市并被其工作＋锁定；citizenTiles 不得回传非己方的 worked／locked。
        val (session, _) = scenario("dev-foreign-locked") { game ->
            DevelopmentFixtures.foreignLockedTileInRange(game)
        }
        val tile = citizenTiles(session).first {
            it.integer("x") == DevelopmentFixtures.contested.x && it.integer("y") == DevelopmentFixtures.contested.y
        }
        assertFalse("非己方地块不得标记为 owned", tile["owned"]!!.jsonPrimitive.boolean)
        assertFalse("非己方地块不得泄漏 worked", tile["worked"]!!.jsonPrimitive.boolean)
        assertFalse("非己方地块不得泄漏 locked", tile["locked"]!!.jsonPrimitive.boolean)
    }

    @Test fun unworkedTileOwnedBySecondCityIsWorkableFromFirst() {
        // 与 tileWorkedBySecondCityIsNotWorkableFromFirst 互补：同文明他城辖区、但未被任何城市工作的地块，
        // 只要落在城市1工作范围内且有产出、城市1有空闲人口，就应可分配。
        val second = HexCoord(-4, 0)
        val shared = HexCoord(-2, 0)
        val (session, nat) = scenario("dev-shared-unworked") { game ->
            val player = game.currentPlayerCiv
            val city1 = player.cities.first { it.location == DevelopmentFixtures.center }
            val city2 = player.addCity(second)
            game.setTransients()
            city2.getWorkedTiles().toList().forEach { city2.stopWorkingTile(it) }
            city1.stopWorkingTile(game.tileMap[shared])
            // 故意不让 city2 工作 shared，留作“他城辖区未工作地块”。
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
        }
        val id1 = session.game!!.currentPlayerCiv.cities.first { it.location == DevelopmentFixtures.center }.id
        val sharedTile = citizenTiles(session, id1).firstOrNull { it.integer("x") == shared.x && it.integer("y") == shared.y }
        assertNotNull("共享地块应落在城市1工作范围内", sharedTile)
        assertTrue("同文明他城拥有该地块", sharedTile!!["owned"]!!.jsonPrimitive.boolean)
        assertFalse("该地块尚未被工作", sharedTile["worked"]!!.jsonPrimitive.boolean)
        // 初始 freePopulation 为 0：先撤回城市1一块已工作地块腾出人口。
        val (ux, uy) = tileWhere(session, id1) { it["canUnwork"]!!.jsonPrimitive.boolean }
        apply(session, nat, "撤回腾出人口", "cityCitizen", id1, "x" to ux, "y" to uy, "type" to "unwork") { cv, view ->
            cv.tryStopWorkingTile(tv(view, ux, uy)); cv.updateCityStats()
        }
        val afterFree = citizenTiles(session, id1).first { it.integer("x") == shared.x && it.integer("y") == shared.y }
        assertTrue("腾出人口后他城辖区未工作地块应可分配", afterFree["canWork"]!!.jsonPrimitive.boolean)
        apply(session, nat, "分配他城辖区地块", "cityCitizen", id1, "x" to shared.x, "y" to shared.y, "type" to "work") { cv, view ->
            cv.tryWorkTile(tv(view, shared.x, shared.y)); cv.updateCityStats()
        }
        assertTrue("分配后该地块应被城市1工作",
            citizenTiles(session, id1).first { it.integer("x") == shared.x && it.integer("y") == shared.y }["worked"]!!.jsonPrimitive.boolean)
    }

    @Test fun blockadedTileCannotBeWorked() {
        // 陆地地块被交战方军事单位占据即被封锁：不可分配人口，work 命令被拒且不改动状态。
        val (session, _) = scenario("dev-blockade") { game ->
            val player = game.currentPlayerCiv
            val ai = game.civilizations.single { it.civName == "Greece" }
            if (!player.knows(ai)) player.diplomacyFunctions.makeCivilizationsMeet(ai)
            player.getDiplomacyManager(ai)!!.declareWar()
            BattleFixtures.add(game, "Warrior", DevelopmentFixtures.farm.x, DevelopmentFixtures.farm.y, ai)
            game.civilizations.forEach { it.popupAlerts.clear(); it.notifications.clear() }
        }
        val id = cityId(session)
        val tile = citizenTiles(session, id).first {
            it.integer("x") == DevelopmentFixtures.farm.x && it.integer("y") == DevelopmentFixtures.farm.y
        }
        assertTrue("敌军占据的地块应被标记封锁", tile["blockaded"]!!.jsonPrimitive.boolean)
        assertFalse("被封锁地块不可分配", tile["canWork"]!!.jsonPrimitive.boolean)
        rejected(session, request(session, "cityCitizen", "cityId" to id,
            "x" to DevelopmentFixtures.farm.x, "y" to DevelopmentFixtures.farm.y, "type" to "work"), "CANNOT_ASSIGN")
    }

    // endregion

    // region 焦点／增长／专家

    @Test fun everyVisibleFocusMatchesNative() {
        val (session, nat) = scenario("dev-focus")
        val id = cityId(session)
        val focuses = cityDto(session, id)["focusOptions"]!!.jsonArray.map { it.jsonObject.text("id") }
        assertTrue("应暴露多个可见焦点", focuses.size >= 5)
        for (focus in focuses) {
            apply(session, nat, "焦点 $focus", "cityFocus", id, "focus" to focus) { cv, _ ->
                cv.trySetCityFocus(CityFocus.valueOf(focus)); cv.updateCityStats()
            }
            assertEquals(focus, cityDto(session, id).text("focus"))
        }
    }

    @Test fun avoidGrowthToggleIsIdempotentAndMatchesNative() {
        val (session, nat) = scenario("dev-avoid")
        val id = cityId(session)
        assertFalse(cityDto(session, id)["avoidGrowth"]!!.jsonPrimitive.boolean)
        apply(session, nat, "开启避免增长", "cityAvoidGrowth", id, "enabled" to true) { cv, _ ->
            cv.tryToggleAvoidGrowth(); cv.updateCityStats()
        }
        assertTrue(cityDto(session, id)["avoidGrowth"]!!.jsonPrimitive.boolean)
        // 幂等：已达目标值时只刷新统计，不改状态。
        apply(session, nat, "重复开启避免增长", "cityAvoidGrowth", id, "enabled" to true) { cv, _ ->
            cv.updateCityStats()
        }
        apply(session, nat, "关闭避免增长", "cityAvoidGrowth", id, "enabled" to false) { cv, _ ->
            cv.tryToggleAvoidGrowth(); cv.updateCityStats()
        }
        assertFalse(cityDto(session, id)["avoidGrowth"]!!.jsonPrimitive.boolean)
    }

    @Test fun specialistAssignUnassignAndManualMatchNative() {
        val (session, nat) = scenario("dev-specialist")
        val id = cityId(session)
        val slots = cityDto(session, id)["specialists"]!!.jsonObject["slots"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, slots.first { it.text("name") == "Merchant" }.integer("max"))
        assertEquals(1, slots.first { it.text("name") == "Engineer" }.integer("max"))
        // 腾出两名空闲人口（初始 freePopulation 为 0）。
        val spare = citizenTiles(session, id).filter { it["canUnwork"]!!.jsonPrimitive.boolean }
            .take(2).map { it.integer("x") to it.integer("y") }
        assertEquals("场景应至少有两块已工作地块", 2, spare.size)
        for ((wx, wy) in spare)
            apply(session, nat, "撤回以腾出人口", "cityCitizen", id, "x" to wx, "y" to wy, "type" to "unwork") { cv, view ->
                cv.tryStopWorkingTile(tv(view, wx, wy)); cv.updateCityStats()
            }
        apply(session, nat, "开启手动专家", "citySpecialists", id, "type" to "setManual", "name" to "Merchant", "enabled" to true) { cv, _ ->
            cv.tryEnableManualSpecialists(); cv.updateCityStats()
        }
        apply(session, nat, "分配商人", "citySpecialists", id, "type" to "assign", "name" to "Merchant") { cv, _ ->
            cv.tryAssignSpecialist("Merchant"); cv.updateCityStats()
        }
        apply(session, nat, "分配工程师", "citySpecialists", id, "type" to "assign", "name" to "Engineer") { cv, _ ->
            cv.tryAssignSpecialist("Engineer"); cv.updateCityStats()
        }
        apply(session, nat, "撤回商人", "citySpecialists", id, "type" to "unassign", "name" to "Merchant") { cv, _ ->
            cv.tryUnassignSpecialist("Merchant"); cv.updateCityStats()
        }
        apply(session, nat, "关闭手动专家", "citySpecialists", id, "type" to "setManual", "name" to "Merchant", "enabled" to false) { cv, _ ->
            cv.tryDisableManualSpecialists(); cv.updateCityStats()
        }
        assertFalse(cityDto(session, id)["specialists"]!!.jsonObject["manual"]!!.jsonPrimitive.boolean)
    }

    @Test fun growthTriggersAutoAssignmentMatchingNative() {
        val (session, nat) = scenario("dev-growth") { game ->
            DevelopmentFixtures.city(game).population.foodStored = 100
        }
        assertEquals(5, cityDto(session).integer("population"))
        advance(session, nat, "增长回合 1")
        advance(session, nat, "增长回合 2")
        assertTrue("人口应增长", cityDto(session).integer("population") > 5)
    }

    @Test fun starvationReducesPopulationAndReassignsMatchingNative() {
        val (session, nat) = scenario("dev-starve")
        val id = cityId(session)
        assertFalse("初始不应饥荒", cityDto(session, id)["growth"]!!.jsonObject["isStarving"]!!.jsonPrimitive.boolean)
        // 撤回全部已工作地块：空闲人口仍消耗粮食，每回合粮食转负 → 饥荒。
        val worked = citizenTiles(session, id).filter { it["canUnwork"]!!.jsonPrimitive.boolean }
            .map { it.integer("x") to it.integer("y") }
        assertTrue("场景应有已工作地块", worked.isNotEmpty())
        for ((wx, wy) in worked)
            apply(session, nat, "撤回制造饥荒 $wx,$wy", "cityCitizen", id, "x" to wx, "y" to wy, "type" to "unwork") { cv, view ->
                cv.tryStopWorkingTile(tv(view, wx, wy)); cv.updateCityStats()
            }
        assertTrue("撤回后应饥荒", cityDto(session, id)["growth"]!!.jsonObject["isStarving"]!!.jsonPrimitive.boolean)
        val before = cityDto(session, id).integer("population")
        advance(session, nat, "饥荒回合")
        val after = cityDto(session, id).integer("population")
        assertTrue("饥荒应减少人口", after < before)
        // 饥荒减员后原生 autoAssignPopulation 立即重分配：空闲人口应归零（后续分配）。
        assertEquals("饥荒减员后应重新自动分配人口", 0, cityDto(session, id)["specialists"]!!.jsonObject.integer("freePopulation"))
    }

    // endregion

    // region 生产队列

    @Test fun queueAddRemoveRaiseLowerMatchNative() {
        val (session, nat) = scenario("dev-queue")
        val id = cityId(session)
        assertEquals(listOf("Nothing"), queueNames(session, id))
        val unitName = enabledConstruction(session, "unit", id)
        val buildingName = enabledConstruction(session, "building", id)
        addQueue(session, nat, id, unitName, "加入单位")
        addQueue(session, nat, id, buildingName, "加入建筑")
        addQueue(session, nat, id, unitName, "再次加入同名单位")
        assertEquals(listOf(unitName, buildingName, unitName), queueNames(session, id))
        apply(session, nat, "上移末项", "cityQueue", id, "type" to "raise", "name" to unitName, "index" to 2) { cv, _ ->
            cv.tryRaisePriority(2); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(unitName, unitName, buildingName), queueNames(session, id))
        apply(session, nat, "下移中间项", "cityQueue", id, "type" to "lower", "name" to unitName, "index" to 1) { cv, _ ->
            cv.tryLowerPriority(1); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(unitName, buildingName, unitName), queueNames(session, id))
        apply(session, nat, "移除建筑", "cityQueue", id, "type" to "remove", "name" to buildingName, "index" to 1) { cv, _ ->
            cv.tryRemoveFromQueue(1, false); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(unitName, unitName), queueNames(session, id))
    }

    @Test fun duplicateUnitByIndexIsRemovedMatchingNative() {
        val (session, nat) = scenario("dev-queue-dup-unit")
        val id = cityId(session)
        val unitName = enabledConstruction(session, "unit", id)
        addQueue(session, nat, id, unitName, "加入单位 1")
        addQueue(session, nat, id, unitName, "加入单位 2")
        addQueue(session, nat, id, unitName, "加入单位 3")
        assertEquals(listOf(unitName, unitName, unitName), queueNames(session, id))
        // 按索引移除中间一项，验证同名条目按位置而非名称操作。
        apply(session, nat, "按索引移除", "cityQueue", id, "type" to "remove", "name" to unitName, "index" to 1) { cv, _ ->
            cv.tryRemoveFromQueue(1, false); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(unitName, unitName), queueNames(session, id))
    }

    @Test fun perpetualConstructionReplaceReorderRemoveMatchNative() {
        val (session, nat) = scenario("dev-perpetual")
        val id = cityId(session)
        val perpetuals = constructions(session, id)
            .filter { it.text("type") == "perpetual" && it["enabled"]!!.jsonPrimitive.boolean }.map { it.text("name") }
        assertTrue("应至少有两种持续生产", perpetuals.size >= 2)
        val first = perpetuals[0]
        val second = perpetuals[1]
        val unitName = enabledConstruction(session, "unit", id)
        addQueue(session, nat, id, first, "设为持续生产 $first")
        assertEquals(listOf(first), queueNames(session, id))
        addQueue(session, nat, id, unitName, "持续项前插入单位")
        assertEquals(listOf(unitName, first), queueNames(session, id))
        addQueue(session, nat, id, second, "替换为持续生产 $second")
        assertEquals(listOf(unitName, second), queueNames(session, id))
        apply(session, nat, "上移持续项", "cityQueue", id, "type" to "raise", "name" to second, "index" to 1) { cv, _ ->
            cv.tryRaisePriority(1); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(second, unitName), queueNames(session, id))
        apply(session, nat, "移除单位", "cityQueue", id, "type" to "remove", "name" to unitName, "index" to 1) { cv, _ ->
            cv.tryRemoveFromQueue(1, false); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(second), queueNames(session, id))
        apply(session, nat, "移除末项持续生产", "cityQueue", id, "type" to "remove", "name" to second, "index" to 0) { cv, _ ->
            cv.tryRemoveFromQueue(0, false); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals("清空队列应回落到 Nothing", listOf("Nothing"), queueNames(session, id))
    }

    @Test fun fullQueueRejectsFurtherAdditions() {
        val (session, nat) = scenario("dev-queue-full")
        val id = cityId(session)
        val unitName = enabledConstruction(session, "unit", id)
        // 队列上限为 10：首个单位替换 Nothing，其后逐个追加至满。
        repeat(10) { n -> addQueue(session, nat, id, unitName, "加入单位 ${n + 1}") }
        assertEquals(10, queueNames(session, id).size)
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "add", "name" to unitName), "CANNOT_EDIT_QUEUE")
    }

    @Test fun duplicateBuildingAndBadIndexAreRejected() {
        val (session, nat) = scenario("dev-queue-reject")
        val id = cityId(session)
        val buildingName = enabledConstruction(session, "building", id)
        addQueue(session, nat, id, buildingName, "加入建筑")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "add", "name" to buildingName), "CANNOT_EDIT_QUEUE")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "remove", "name" to buildingName, "index" to -1), "CANNOT_EDIT_QUEUE")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "remove", "name" to buildingName, "index" to 99), "CANNOT_EDIT_QUEUE")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "raise", "name" to buildingName, "index" to 0), "CANNOT_EDIT_QUEUE")
        val last = queueNames(session, id).size - 1
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "lower", "name" to buildingName, "index" to last), "CANNOT_EDIT_QUEUE")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "remove", "name" to buildingName), "INVALID_ARGUMENT")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "bogus", "name" to buildingName), "INVALID_ARGUMENT")
        rejected(session, request(session, "cityQueue", "cityId" to id, "type" to "add", "name" to "NotAConstruction"), "INVALID_ARGUMENT")
    }

    @Test fun queueReorderThenAdvanceCompletesMatchingNative() {
        val (session, nat) = scenario("dev-queue-advance")
        val id = cityId(session)
        val unitName = enabledConstruction(session, "unit", id)
        val buildingName = enabledConstruction(session, "building", id)
        addQueue(session, nat, id, buildingName, "加入建筑")
        addQueue(session, nat, id, unitName, "加入单位")
        assertEquals(listOf(buildingName, unitName), queueNames(session, id))
        // 上移单位到首位，改变当前生产（重排）。
        apply(session, nat, "上移单位", "cityQueue", id, "type" to "raise", "name" to unitName, "index" to 1) { cv, _ ->
            cv.tryRaisePriority(1); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals(listOf(unitName, buildingName), queueNames(session, id))
        // 推进直到单位完工：溢出生产转入下一项（完整差分由 advance 内 assertGameplayEquals 保证）。
        var guard = 0
        while (session.game!!.currentPlayerCiv.units.getCivUnits().none { it.name == unitName } && guard++ < 40)
            advance(session, nat, "生产回合 $guard")
        assertTrue("单位应在有限回合内完工", guard < 40)
        assertEquals("完工后当前生产应前进到下一项", buildingName, queueNames(session, id).first())
    }

    @Test fun orphanedCreatesOneImprovementMarkerIsClearedOnQueueValidation() {
        // 基础规则没有 CreatesOneImprovement 建筑，故用 markForCreatesOneImprovement 制造“孤立预留标记”：
        // 队列中并无生产该改良的建筑。移除普通条目本身不清标记；只有回合内 constructIfEnough →
        // validateCreatesOneImprovementMarkers 才清除孤立标记。两端完整差分确保清除时机一致。
        val (session, nat) = scenario("dev-creates-one") { game ->
            DevelopmentFixtures.tile(game, DevelopmentFixtures.farm).improvementFunctions.markForCreatesOneImprovement("Farm")
        }
        val id = cityId(session)
        assertTrue("夹具应预留改良标记",
            session.game!!.tileMap[DevelopmentFixtures.farm].isMarkedForCreatesOneImprovement("Farm"))
        val unitName = enabledConstruction(session, "unit", id)
        addQueue(session, nat, id, unitName, "加入单位")
        apply(session, nat, "移除单位条目", "cityQueue", id, "type" to "remove", "name" to unitName, "index" to 0) { cv, _ ->
            cv.tryRemoveFromQueue(0, false); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertTrue("移除普通条目不应清除孤立标记",
            session.game!!.tileMap[DevelopmentFixtures.farm].isMarkedForCreatesOneImprovement("Farm"))
        advance(session, nat, "回合校验清标记")
        assertFalse("网关端孤立标记应在回合校验后清除",
            session.game!!.tileMap[DevelopmentFixtures.farm].isMarkedForCreatesOneImprovement("Farm"))
        assertFalse("原生端同样清除孤立标记",
            nat.game.tileMap[DevelopmentFixtures.farm].isMarkedForCreatesOneImprovement("Farm"))
    }

    @Test fun removingInProgressConstructionDoesNotRefundStockpileOrGold() {
        val (session, nat) = scenario("dev-queue-refund")
        val id = cityId(session)
        val unitName = enabledConstruction(session, "unit", id)
        addQueue(session, nat, id, unitName, "加入单位")
        advance(session, nat, "累积生产一回合")
        assertEquals("单位应仍在建（未完工）", unitName, queueNames(session, id).first())
        val goldBefore = session.game!!.currentPlayerCiv.gold
        val resourcesBefore = session.game!!.currentPlayerCiv.getCivResourcesByName().toSortedMap().toString()
        // 取消在建项目：非奇观不折算黄金退款，也不退还库存资源（helper 取消工程无库存退款）。
        apply(session, nat, "移除在建单位", "cityQueue", id, "type" to "remove", "name" to unitName, "index" to 0) { cv, _ ->
            cv.tryRemoveFromQueue(0, false); cv.tryReassignPopulation(); cv.updateCityStats()
        }
        assertEquals("移除非奇观在建项目不得折算黄金退款", goldBefore, session.game!!.currentPlayerCiv.gold)
        assertEquals("移除在建项目不得退还库存资源", resourcesBefore,
            session.game!!.currentPlayerCiv.getCivResourcesByName().toSortedMap().toString())
    }

    // endregion

    @Test fun illegalCityRequestsAreRejectedWithoutMutation() {
        val (session, _) = scenario("dev-city-illegal")
        val id = cityId(session)
        val aiCityId = session.game!!.civilizations.single { it.civName == "Greece" }.cities.first().id
        // 非己方城市。
        rejected(session, request(session, "cityFocus", "cityId" to aiCityId, "focus" to "FoodFocus"), "NOT_OWNED")
        rejected(session, request(session, "cityCitizen", "cityId" to aiCityId, "x" to 0, "y" to 0, "type" to "work"), "NOT_OWNED")
        // 未知焦点。
        rejected(session, request(session, "cityFocus", "cityId" to id, "focus" to "NotAFocus"), "INVALID_ARGUMENT")
        // 未知专家类型／专家名。
        rejected(session, request(session, "citySpecialists", "cityId" to id, "type" to "bogus", "name" to "Merchant"), "INVALID_ARGUMENT")
        rejected(session, request(session, "citySpecialists", "cityId" to id, "type" to "assign", "name" to "NotASpecialist"), "INVALID_ARGUMENT")
        // 城中心不可分配人口。
        rejected(session, request(session, "cityCitizen", "cityId" to id, "x" to 0, "y" to 0, "type" to "work"), "CANNOT_ASSIGN")
        // 初始 freePopulation 为 0：工作一块可工作的空闲地块被拒。
        val spare = citizenTiles(session, id).first {
            !it["worked"]!!.jsonPrimitive.boolean && !it["cityCenter"]!!.jsonPrimitive.boolean &&
                it["owned"]!!.jsonPrimitive.boolean && it["resource"] is JsonNull
        }
        rejected(session, request(session, "cityCitizen", "cityId" to id, "x" to spare.integer("x"), "y" to spare.integer("y"), "type" to "work"), "CANNOT_ASSIGN")
        // 未知地块命令类型。
        rejected(session, request(session, "cityCitizen", "cityId" to id, "x" to 1, "y" to 0, "type" to "bogus"), "INVALID_ARGUMENT")
    }

    /** 生成固定 development-ui.json（两城／堆叠单位／六方向道路／长名称／敌方施工／差一锤完工）供 Godot smoke UI 展示场景消费；登记为测试输出。 */
    @Test fun emitUiDisplayScenarioForGodotSmoke() {
        val file = DevelopmentFixtures.uiFile()
        assertTrue("development-ui.json 应写入测试输出目录", file.isFile && file.length() > 0)
    }
}
