package com.unciv.godot

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 冒烟专用的整局自动驱动（陆地征服／科学发展／海洋混合三条路线策略）。
 *
 * 目的：让真实窗口能从 `newGame` 一路打到原生胜负结算，而不必把内核 [FullGameTest]／[FullGameRoutesTest]
 * 里已验证的数百行决策逻辑重写成 GDScript。窗口每回合经 HTTP 调用一次 [playTurn]，由本驱动只走网关命令
 * （对 [GameSession.handle] 的重入子请求）完成“处置全部待决 + 指挥单位／战争”，随后窗口**真实点击**
 * “下一回合”推进 AI 回合；一旦出现原生胜负结算，[playTurn] 立即停手并在返回值里标记 `victoryReached`，
 * 把结算交回窗口做真实胜负面板取证（两尺寸、继续／取消），不在内核侧自动 victoryContinue。
 *
 * 三条路线决策与内核同源：
 * - **land**：与 [FullGameTest] 陆地征服路线同源（militaryPriority/peacePriority/techPriority、WAR_TURN、
 *   铺城封顶、Worker 连铁马、全军集中单一主攻、≥2 城且 ≥10 近战才宣战），目标原生 Domination。
 * - **science**：与 [FullGameRoutesTest.Science] 同源（warTurn 极大不主动开战、首都持续排产科学建筑、
 *   深入科技树、被偷袭才防御反击），收敛到原生 Scientific／Time／Domination 结算。
 * - **naval**：与 [FullGameRoutesTest.Naval] 同源（Work Boat 海上改良、海军舰队配额、Astronomy 后远洋舰队
 *   成型才主动跨海宣战、登船跨海、真实海军交战），收敛到原生 Domination／Scientific 结算。
 *
 * 差别仅在于：本驱动不做 twin 重放（每条子命令本身就是真实网关命令，其正确性已由各命令的独立原生差分测试
 * 覆盖，且路线整体到原生结算的确定性由 [AutoplayGameTest]／[AutoplayRouteTest] 的 save→独立原生 load 往返
 * 证明），并在结算处停手交回窗口。
 *
 * 仅在 `UNCIV_SMOKE=1` 门控下可用（见 [GameSession.smokeEnabled]）：真实游玩窗口不设该变量，`autoplay`
 * 动词等同不存在，不会成为产品玩法面的一部分。
 */
internal class AutoplayDriver(private val session: GameSession) {
    /** 三条整局路线；默认 [LAND]，保持既有陆地征服／重启／性能取证调用（不带 route 参数）行为不变。 */
    enum class Route { LAND, SCIENCE, NAVAL }

    /** 路线专属决策配置（与内核 [FullGameTest]／[FullGameRoutesTest] 各路线同源常量）。 */
    private data class RouteConfig(
        val route: Route,
        val warTurn: Int,
        val maxCities: Int,
        val minUnitsForWar: Int,
        val militaryPriority: List<String>,
        val developmentPriority: List<String>,
        val techPriority: List<String>,
        val scienceBuildings: List<String> = emptyList(),
        val navalUnits: List<String> = emptyList(),
        val oceanNavy: List<String> = emptyList(),
    )

    private var route = Route.LAND
    private var warStarted = false
    private var purchasedThisTurn = false
    private val knownEnemyCities = LinkedHashSet<HexCoord>()
    private var actionsIssued = 0
    private var trackedGameId: String? = null

    /** 驱动当前回合：处置待决 + 指挥单位；出现原生结算则停手。返回本回合自动决策摘要（含路线证据字段）。 */
    fun playTurn(routeName: String = "land"): JsonObject {
        if (session.game == null) throw GatewayError("NO_GAME", "autoplay 需要先新游戏或读档")
        route = when (routeName.lowercase()) {
            "science" -> Route.SCIENCE
            "naval" -> Route.NAVAL
            else -> Route.LAND
        }
        // 对局切换（新游戏/读档/进程重启后读档）时清空跨回合记忆，避免陈旧的 warStarted/knownEnemyCities 影响新对局。
        val gameId = session.game!!.gameId
        if (trackedGameId != gameId) {
            warStarted = false
            knownEnemyCities.clear()
            trackedGameId = gameId
        }
        actionsIssued = 0
        purchasedThisTurn = false
        val config = config()
        if (!resolvePending()) {
            act(session.game!!.turns, config)
            resolvePending()
        }
        val game = session.game!!
        val victory = game.victoryData
        val player = game.currentPlayerCiv
        val base: JsonObject = dto(
            "turn" to game.turns,
            "actionsIssued" to actionsIssued,
            "warStarted" to warStarted,
            "victoryReached" to (victory != null),
            "victoryType" to victory?.victoryType,
            "winningCiv" to victory?.let { record ->
                game.civilizations.firstOrNull { it.civID == record.winningCiv }?.civName
            },
            "oneMoreTurnMode" to game.oneMoreTurnMode,
            "cities" to player.cities.size,
            "units" to player.units.getCivUnits().count(),
            "technologies" to player.tech.techsResearched.size)
        return JsonObject(base.toMap() + routeEvidence())
    }

    /**
     * 路线专属证据（每回合从快照／城市现算，供真实窗口取证直接读取，无需内核侧记忆）：
     * scienceBuildingsBuilt＝已建成科学建筑数；waterImprovements／navalUnits／embarkedUnits 从快照统计。
     */
    private fun routeEvidence(): Map<String, JsonElement> {
        val player = session.game!!.currentPlayerCiv
        val built = player.cities.flatMap { it.cityConstructions.builtBuildings }.count { it in SCIENCE_BUILDINGS }
        val snap = snapshot()
        val embarked = snap["units"]!!.jsonArray.map { it.jsonObject }.count {
            it.boolean("own") && it.boolean("embarked")
        }
        val navalUnits = snap["units"]!!.jsonArray.map { it.jsonObject }.count {
            it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "water"
        }
        val waterImprovements = snap["tiles"]!!.jsonArray.map { it.jsonObject }.count {
            val terrain = it.text("terrain")
            (terrain == "Coast" || terrain == "Ocean") && it.text("improvement").isNotEmpty()
        }
        return mapOf(
            "route" to JsonPrimitive(route.name.lowercase()),
            "scienceBuildingsBuilt" to JsonPrimitive(built),
            "waterImprovements" to JsonPrimitive(waterImprovements),
            "navalUnits" to JsonPrimitive(navalUnits),
            "embarkedUnits" to JsonPrimitive(embarked))
    }

    private fun config(): RouteConfig = when (route) {
        Route.LAND -> RouteConfig(
            route = Route.LAND, warTurn = 12, maxCities = 3, minUnitsForWar = 10,
            militaryPriority = LAND_MILITARY, developmentPriority = LAND_DEVELOPMENT,
            techPriority = LAND_TECH)
        Route.SCIENCE -> RouteConfig(
            route = Route.SCIENCE, warTurn = 100000, maxCities = 4, minUnitsForWar = 14,
            militaryPriority = LAND_MILITARY, developmentPriority = SCIENCE_DEVELOPMENT,
            techPriority = SCIENCE_TECH, scienceBuildings = SCIENCE_BUILDINGS)
        Route.NAVAL -> RouteConfig(
            route = Route.NAVAL, warTurn = 40, maxCities = 3, minUnitsForWar = 6,
            militaryPriority = NAVAL_MILITARY, developmentPriority = NAVAL_DEVELOPMENT,
            techPriority = NAVAL_TECH, navalUnits = NAVAL_UNITS, oceanNavy = OCEAN_NAVY)
    }

    // ---- 网关子请求：查询不改状态、命令推进 revision ----

    private fun run(action: String, vararg params: Pair<String, Any?>): JsonObject {
        val request = dto("protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
            "requestId" to UUID.randomUUID().toString(), "action" to action, *params)
        val response = session.handle(request)
        if (!response.boolean("ok")) throw GatewayError("AUTOPLAY_COMMAND_FAILED",
            "autoplay 子命令 $action 失败：${response["error"]}")
        return response
    }

    private fun query(action: String, vararg params: Pair<String, Any?>): JsonObject = run(action, *params)

    private fun command(action: String, vararg params: Pair<String, Any?>): JsonObject {
        val response = run(action, *params)
        actionsIssued++
        return response
    }

    private fun snapshot(): JsonObject = query("snapshot")["snapshot"]?.jsonObject
        ?: throw GatewayError("KERNEL_ERROR", "对局快照缺失")

    private fun pending(): List<JsonObject> = snapshot()["pending"]!!.jsonArray.map { it.jsonObject }

    // ---- 待决处置：自然产生的每种待决都必须有网关入口；遇到原生胜负结算即停手交回窗口 ----

    /** 处置全部待决；返回 true 表示已出现原生胜负结算（停手，交窗口做真实面板取证）。 */
    private fun resolvePending(): Boolean {
        repeat(600) {
            if (session.game!!.victoryData != null) return true
            val head = pending().firstOrNull() ?: return false
            if (!head.boolean("supported")) throw GatewayError("UNSUPPORTED",
                "autoplay 遇到无法在真实窗口处置的待决：$head")
            when (head.text("kind")) {
                "victory" -> return true
                "production" -> production(head)
                "research" -> research()
                "policy" -> policy()
                "alert" -> command("acknowledge")
                "trade" -> command("declineTrade")
                "greatPerson" -> greatPerson()
                "religion" -> religion()
                "diplomacyAlert" -> diplomacyAlert()
                "cityCapture" -> command("cityDecision", "cityId" to head.text("target"), "choice" to "annex")
                "assetDecision" -> assetDecision()
                "event" -> event()
                "vote", "voteResult" -> throw GatewayError("UNSUPPORTED", "长局不应出现外交投票：$head")
                else -> throw GatewayError("UNSUPPORTED", "未接入的长局待决：$head")
            }
        }
        throw GatewayError("KERNEL_ERROR", "autoplay 待决没有收敛：${pending()}")
    }

    private fun production(head: JsonObject) {
        val cityId = head.text("target")
        val options = query("cityOptions", "cityId" to cityId)["data"]!!.jsonObject
        val available = options["constructions"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("enabled") }
        val civ = session.game!!.currentPlayerCiv
        val config = config()
        val ownCities = civ.cities.size
        val settlersInField = civ.units.getCivUnits().count { it.name == "Settler" }
        val workersInField = civ.units.getCivUnits().count { it.name == "Worker" }
        val priorities: List<String> = when (config.route) {
            Route.SCIENCE -> sciencePriorities(config, cityId, ownCities, settlersInField, workersInField,
                isCapital = civ.getCapital()?.id == cityId)
            Route.NAVAL -> navalPriorities(config, civ, ownCities, settlersInField)
            Route.LAND -> landPriorities(config, ownCities, settlersInField, workersInField)
        }
        val build = priorities.firstNotNullOfOrNull { want -> available.firstOrNull { it.text("name") == want } }
            ?: available.firstOrNull { it.text("type") == "unit" && it.text("name") != "Settler" }
            ?: available.firstOrNull()
            ?: throw GatewayError("KERNEL_ERROR", "$cityId 没有可生产的项目")
        command("production", "cityId" to cityId, "name" to build.text("name"))
    }

    private fun landPriorities(config: RouteConfig, ownCities: Int, settlers: Int, workers: Int): List<String> = when {
        !warStarted && ownCities + settlers < config.maxCities -> listOf("Settler", "Worker") + config.militaryPriority
        !warStarted && workers < 3 -> listOf("Worker") + config.militaryPriority
        else -> config.militaryPriority + config.developmentPriority.drop(2)
    }

    /** 科学路线：扩张期铺城/补 Worker；此后首都持续造科学建筑（不停科学发展），其余城市战时优先国防军。 */
    private fun sciencePriorities(
        config: RouteConfig, cityId: String,
        ownCities: Int, settlers: Int, workers: Int, isCapital: Boolean): List<String> = when {
        !warStarted && ownCities + settlers < config.maxCities ->
            listOf("Settler", "Worker") + config.scienceBuildings + config.developmentPriority + config.militaryPriority
        !warStarted && workers < 3 ->
            listOf("Worker") + config.scienceBuildings + config.developmentPriority + config.militaryPriority
        isCapital -> config.scienceBuildings + config.developmentPriority + config.militaryPriority
        else -> config.militaryPriority + config.scienceBuildings + config.developmentPriority
    }

    /** 海洋路线：扩张期铺城 + Work Boat；此后优先补足 Work Boat 与舰队配额，其余产能回落陆军国防。 */
    private fun navalPriorities(config: RouteConfig, civ: Civilization,
        ownCities: Int, settlers: Int): List<String> {
        val workBoats = civ.units.getCivUnits().count { it.name == "Work Boats" }
        val warships = civ.units.getCivUnits().count { config.navalUnits.contains(it.name) }
        val hasAstronomy = civ.tech.techsResearched.contains("Astronomy")
        val oceanWarships = civ.units.getCivUnits().count { it.name in config.oceanNavy }
        return when {
            !warStarted && ownCities + settlers < config.maxCities ->
                listOf("Settler", "Work Boats", "Worker") + config.navalUnits + config.developmentPriority + config.militaryPriority
            workBoats < 1 -> listOf("Work Boats") + config.navalUnits + config.militaryPriority + config.developmentPriority
            hasAstronomy && oceanWarships < 2 -> config.oceanNavy + config.militaryPriority + config.developmentPriority
            warships < 2 -> config.navalUnits + config.militaryPriority + config.developmentPriority
            else -> config.militaryPriority + config.navalUnits + config.developmentPriority
        }
    }

    private fun research() {
        val entries = query("researchOptions")["data"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
            .filter { it.boolean("enabled") }
        val tech = config().techPriority.firstNotNullOfOrNull { want -> entries.firstOrNull { it.text("name") == want } }
            ?: entries.firstOrNull() ?: throw GatewayError("KERNEL_ERROR", "没有可研究的科技")
        command("research", "name" to tech.text("name"))
    }

    private fun policy() {
        val data = query("policyOptions")["data"]!!.jsonObject
        val adopted = data["entries"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
        if (adopted != null) command("policy", "name" to adopted.text("name"))
        else {
            if (!data.boolean("canDefer")) throw GatewayError("UNSUPPORTED", "没有可采纳政策且不能暂缓：$data")
            command("deferPolicy")
        }
    }

    private fun greatPerson() {
        val tried = LinkedHashSet<String>()
        while (true) {
            val data = query("greatPersonOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: return
            if (!decision.boolean("enabled")) throw GatewayError("UNSUPPORTED", "免费伟人待决不可领取：$decision")
            val candidate = data["candidates"]!!.jsonArray.map { it.jsonObject }
                .filter { it.boolean("enabled") }.map { it.text("unitName") }
                .firstOrNull { it !in tried }
                ?: throw GatewayError("UNSUPPORTED", "所有免费伟人候选都无法在首都落位（可能均为海军伟人而首都不沿海）：$tried")
            tried += candidate
            val result = command("greatPersonChoose", "unitName" to candidate, "decisionToken" to decision.text("token"))
            if (result["greatPersonResult"]?.jsonObject?.text("outcome") == "granted") return
        }
    }

    private fun religion() {
        val data = query("religionOptions")["data"]!!.jsonObject
        val decision = data["decision"]?.jsonObject ?: throw GatewayError("UNSUPPORTED", "宗教待决与查询不一致")
        if (!decision.boolean("supported")) throw GatewayError("UNSUPPORTED", "宗教待决不可处置：$decision")
        val beliefs = decision["slots"]!!.jsonArray.map { slot ->
            val ids = slot.jsonObject["candidateIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (ids.isEmpty()) throw GatewayError("UNSUPPORTED", "信条槽位没有候选：$slot")
            ids.first()
        }
        if (decision.text("mode") == "foundReligion") {
            val symbol = data["symbols"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("available") }
                ?: throw GatewayError("UNSUPPORTED", "没有可用的宗教符号")
            command("religionFound", "decisionToken" to decision.text("token"),
                "religionId" to symbol.text("id"), "displayName" to symbol.text("defaultName"), "beliefs" to beliefs)
        } else {
            command("religionChooseBeliefs", "decisionToken" to decision.text("token"), "beliefs" to beliefs)
        }
    }

    private fun diplomacyAlert() {
        val data = query("diplomacyOptions")["data"]!!.jsonObject
        val alert = data["pendingAlert"]?.jsonObject ?: throw GatewayError("UNSUPPORTED", "外交待决与查询不一致")
        val choice = alert["choices"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
            ?: throw GatewayError("UNSUPPORTED", "外交待决没有可用选项：$alert")
        command("diplomacyAlertDecision", "alertToken" to alert.text("alertToken"), "choice" to choice.text("id"))
    }

    private fun assetDecision() {
        val data = query("assetDecisionOptions")["data"]!!.jsonObject
        val decision = data["decision"]?.jsonObject ?: throw GatewayError("UNSUPPORTED", "资产待决与查询不一致")
        val choice = decision["choices"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
            ?: throw GatewayError("UNSUPPORTED", "资产待决没有可用选项：$decision")
        command("assetDecision", "decisionToken" to decision.text("token"), "choice" to choice.text("id"))
    }

    private fun event() {
        val data = query("eventOptions")["data"]!!.jsonObject
        val decision = data["decision"]?.jsonObject ?: throw GatewayError("UNSUPPORTED", "事件待决与查询不一致")
        val choices = decision["choices"]!!.jsonArray.map { it.jsonObject }
        val index = if (choices.isEmpty()) -1 else choices.first().integer("index")
        val params = mutableListOf<Pair<String, Any?>>("eventToken" to decision.text("token"))
        if (index >= 0) params += "index" to index
        command("eventChoose", *params.toTypedArray())
    }

    // ---- 玩家命令：路线驱动（陆地行动 + 海洋路线追加海军行动） ----

    private fun act(turn: Int, config: RouteConfig) {
        purchasedThisTurn = false
        val snapshot = snapshot()
        val ownedNow = HashSet<HexCoord>()
        for (city in snapshot["cities"]!!.jsonArray.map { it.jsonObject }) {
            val at = HexCoord(city.integer("x"), city.integer("y"))
            if (city.boolean("own")) ownedNow += at else knownEnemyCities += at
        }
        knownEnemyCities.removeAll(ownedNow)
        val explored = snapshot["tiles"]!!.jsonArray.map { it.jsonObject }
            .filter { it.text("visibility") != "unknown" }
            .map { "${it.integer("x")}:${it.integer("y")}" }.toHashSet()
        val cities = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
        val home = cities.firstOrNull()?.let { intArrayOf(it.integer("x"), it.integer("y")) }
        val civId = session.game!!.currentPlayerCiv.civID
        val resourceTiles = snapshot["tiles"]!!.jsonArray.map { it.jsonObject }.filter {
            it.text("owner") == civId && it.text("resource").isNotEmpty() && it.text("improvement").isEmpty()
        }.map { HexCoord(it.integer("x"), it.integer("y")) }
        tryDeclareWar(turn, snapshot, config)
        purchase(cities, config)
        val primaryTarget = if (warStarted && home != null && knownEnemyCities.isNotEmpty()) {
            val hx = home[0]; val hy = home[1]
            knownEnemyCities.minByOrNull { (it.x - hx) * (it.x - hx) + (it.y - hy) * (it.y - hy) }
        } else null
        val attacked = HashSet<Int>()
        var founded = cities.size
        for (unit in snapshot["units"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }) {
            if (session.game!!.victoryData != null) return
            val id = unit.integer("id")
            if (!session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == id }) continue
            val options = query("unitOptions", "unitId" to id)["data"]!!.jsonObject
            if (unit.text("name") == "Settler" && founded < config.maxCities) {
                if (options.boolean("canFound")) {
                    command("foundCity", "unitId" to id, "confirmPromise" to true)
                    founded++
                    if (resolvePending()) return
                } else {
                    move(id, options, explored, home, null)
                }
                continue
            }
            if (unit.text("name") == "Worker") {
                if (worker(id, options)) continue
                val resourceTile = resourceTiles.minByOrNull { distance(unit, it) }
                if (resourceTile != null) move(id, options, explored, home, resourceTile)
                continue
            }
            if (unit.boolean("civilian") || unit.text("domain") != "land") continue
            if (warStarted && !attacked.contains(id) && attack(id, options, primaryTarget)) {
                attacked += id
                continue
            }
            if (warStarted && home != null && unit.integer("x") == home[0] && unit.integer("y") == home[1]
                && soldiersAt(snapshot, home) == 1) continue
            val target = primaryTarget ?: if (warStarted) knownEnemyCities.minByOrNull { distance(unit, it) } else null
            move(id, options, explored, home, target)
        }
        if (config.route == Route.NAVAL) navalAct(snapshot, explored, home)
    }

    private fun soldiersAt(snapshot: JsonObject, home: IntArray): Int =
        snapshot["units"]!!.jsonArray.map { it.jsonObject }.count {
            it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "land" &&
                it.integer("x") == home[0] && it.integer("y") == home[1]
        }

    private fun tryDeclareWar(turn: Int, snapshot: JsonObject, config: RouteConfig) {
        if (warStarted) return
        // 始终检测战争状态：AI 主动宣战后本方立即进入战争以组织防御/反击（不受 warTurn 门槛限制），
        // 否则科学路线在长期和平发展期被偷袭时不会还手，城市与科学生产会被迅速打崩。
        val data = query("diplomacyOptions")["data"]!!.jsonObject
        val majors = data["civilizations"]!!.jsonArray.map { it.jsonObject }.filter { it.text("type") == "major" }
        if (majors.any { it.text("status") == "War" }) {
            warStarted = true
            return
        }
        // 主动宣战受 warTurn／门槛约束：科学路线 warTurn 极大（先长期和平发展），海洋路线待远洋舰队成型才跨海征服。
        if (turn < config.warTurn) return
        if (config.route == Route.NAVAL) {
            val oceanWarships = session.game!!.currentPlayerCiv.units.getCivUnits().count { it.name in config.oceanNavy }
            if (oceanWarships < 2) return
        } else {
            val soldiers = snapshot["units"]!!.jsonArray.map { it.jsonObject }
                .count { it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "land" }
            if (session.game!!.currentPlayerCiv.cities.size < 2 || soldiers < config.minUnitsForWar) return
        }
        val target = majors.firstOrNull { civ ->
            (civ["actions"] as? JsonObject)?.get("declareWar")?.jsonObject?.boolean("enabled") == true
        } ?: return
        command("diplomacyDeclareWar", "civId" to target.text("civId"))
        warStarted = true
    }

    /** 海洋路线追加：Work Boat 海上改良、海军交战／推进（Trireme 回防海岸、远洋军舰跨海进攻敌岸）。 */
    private fun navalAct(snapshot: JsonObject, explored: Set<String>, home: IntArray?) {
        val civId = session.game!!.currentPlayerCiv.civID
        val ownUnits = snapshot["units"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
        val ownCityCoords = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
            .map { HexCoord(it.integer("x"), it.integer("y")) }
        val enemyCityCoords = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.filter { !it.boolean("own") }
            .map { HexCoord(it.integer("x"), it.integer("y")) }
        for (unit in ownUnits) {
            val id = unit.integer("id")
            if (!session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == id }) continue
            val name = unit.text("name")
            val domain = unit.text("domain")
            val options = query("unitOptions", "unitId" to id)["data"]!!.jsonObject
            if (name == "Work Boats") {
                if (worker(id, options)) continue
                val waterResource = nearestWaterResource(unit, snapshot, civId)
                if (waterResource != null) move(id, options, explored, home, waterResource)
                continue
            }
            if (domain == "water" && !unit.boolean("civilian")) {
                // 先真实交火：相邻有敌即攻击。
                if (attack(id, options, null)) continue
                val ux = unit.integer("x"); val uy = unit.integer("y")
                // 远洋军舰（Caravel+，非 Trireme）跨海进攻最近已知敌城；Trireme 不能进远洋，回防最近己方城市海岸。
                val navalTarget = if (name != "Trireme") {
                    if (enemyCityCoords.isNotEmpty())
                        enemyCityCoords.minByOrNull { (it.x - ux) * (it.x - ux) + (it.y - uy) * (it.y - uy) }
                    else null
                } else ownCityCoords.minByOrNull { (it.x - ux) * (it.x - ux) + (it.y - uy) * (it.y - uy) }
                move(id, options, explored, home, navalTarget)
            }
        }
    }

    /** 最近的可见水上资源格（优先己方领土），供 Work Boat 前往创建海上改良。 */
    private fun nearestWaterResource(unit: JsonObject, snapshot: JsonObject, civId: String): HexCoord? {
        val ux = unit.integer("x"); val uy = unit.integer("y")
        val candidates = snapshot["tiles"]!!.jsonArray.map { it.jsonObject }.filter {
            val terrain = it.text("terrain")
            (terrain == "Coast" || terrain == "Ocean") && it.text("visibility") != "unknown" &&
                it.text("resource").isNotEmpty() && it.text("improvement").isEmpty()
        }
        val owned = candidates.filter { it.text("owner") == civId }
        val pool = if (owned.isNotEmpty()) owned else candidates
        return pool.minByOrNull { (it.integer("x") - ux) * (it.integer("x") - ux) + (it.integer("y") - uy) * (it.integer("y") - uy) }
            ?.let { HexCoord(it.integer("x"), it.integer("y")) }
    }

    private fun worker(id: Int, options: JsonObject): Boolean {
        val worker = options["worker"] as? JsonObject ?: return false
        if (worker.text("mode") == "waterInstant" && worker.boolean("enabled")) {
            command("workerOrder", "unitId" to id, "type" to "createWaterImprovement", "name" to worker.text("improvement"))
            return true
        }
        if (worker.text("mode") != "worker" || !worker.boolean("supported")) return false
        val inProgress = worker["inProgress"]
        if (inProgress != null && inProgress !is JsonNull) return true
        val builds = worker["improvements"]!!.jsonArray.map { it.jsonObject }
        val build = builds.firstOrNull { it.boolean("enabled") && it["providesResource"] is JsonPrimitive }
            ?: builds.firstOrNull { it.boolean("enabled") } ?: return false
        command("workerOrder", "unitId" to id, "type" to "start", "name" to build.text("name"))
        return true
    }

    private fun attack(id: Int, options: JsonObject, primary: HexCoord?): Boolean {
        if (!options.boolean("combatSupported")) return false
        val targets = (options["attackTargets"] as? JsonArray)?.map { it.jsonObject } ?: return false
        if (targets.isEmpty()) return false
        val aim = (primary?.let { p -> targets.firstOrNull { it.integer("x") == p.x && it.integer("y") == p.y } })
            ?: targets.firstOrNull { HexCoord(it.integer("x"), it.integer("y")) in knownEnemyCities }
            ?: targets.first()
        command("attack", "unitId" to id, "x" to aim.integer("x"), "y" to aim.integer("y"))
        resolvePending()
        return true
    }

    private fun move(id: Int, options: JsonObject, explored: Set<String>, home: IntArray?, target: HexCoord?) {
        val reachable = (options["reachable"] as? JsonArray)?.map { it.jsonObject } ?: return
        if (reachable.isEmpty()) return
        val pick = when {
            target != null -> reachable.minByOrNull { distance(it, target) }
            else -> {
                val unseen = reachable.filter { "${it.integer("x")}:${it.integer("y")}" !in explored }
                val pool = if (unseen.isNotEmpty()) unseen else reachable
                val point = target ?: home?.let { HexCoord(it[0], it[1]) }
                if (point == null) pool.minByOrNull { it.integer("y") * 4096 + it.integer("x") }
                else pool.maxByOrNull { distance(it, point) }
            }
        } ?: return
        command("move", "unitId" to id, "x" to pick.integer("x"), "y" to pick.integer("y"))
    }

    private fun distance(point: JsonObject, target: HexCoord): Int {
        val dx = point.integer("x") - target.x
        val dy = point.integer("y") - target.y
        return dx * dx + dy * dy
    }

    private fun purchase(cities: List<JsonObject>, config: RouteConfig) {
        for (city in cities) {
            if (purchasedThisTurn) return
            val data = query("cityOptions", "cityId" to city.text("id"))["data"]!!.jsonObject
            val picks = data["economy"]!!.jsonObject["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
                .filter { it.boolean("supported") && it.text("type") == "unit" }
                .mapNotNull { option ->
                    val quote = option["quotes"]!!.jsonArray.map { it.jsonObject }
                        .firstOrNull { it.text("stat") == "Gold" && it.boolean("enabled") } ?: return@mapNotNull null
                    Triple(option.text("name"), quote.integer("cost"),
                        config.militaryPriority.indexOf(option.text("name")).let { if (it < 0) Int.MAX_VALUE else it })
                }.sortedWith(compareBy({ it.third }, { it.second }))
            val pick = picks.firstOrNull() ?: continue
            command("cityPurchase", "cityId" to city.text("id"), "name" to pick.first, "stat" to "Gold", "queueIndex" to -1)
            purchasedThisTurn = true
        }
    }

    companion object {
        // 陆地征服：与 FullGameTest 同源常量（征服只造近战，科技先走近战解锁链再补经济/后期军事）。
        private val LAND_MILITARY = listOf("Infantry", "Rifleman", "Cavalry", "Musketman", "Knight",
            "Longswordsman", "Pikeman", "Swordsman", "Horseman", "Spearman", "Warrior")
        private val LAND_DEVELOPMENT = listOf("Settler", "Worker", "Monument", "Library", "Granary", "Courthouse")
        private val LAND_TECH = listOf("Mining", "Bronze Working", "Iron Working", "Animal Husbandry",
            "Horseback Riding", "Pottery", "Writing", "Mathematics", "Construction", "Philosophy",
            "Civil Service", "Steel", "Chivalry", "Gunpowder", "Military Science", "Replaceable Parts",
            "The Wheel", "Trapping", "Calendar", "Engineering", "Machinery", "Archery")

        // 科学发展：与 FullGameRoutesTest.Science 同源（和平发展优先科学与经济建筑、深入科技树）。
        private val SCIENCE_DEVELOPMENT = listOf("Library", "Monument", "Granary", "University",
            "Observatory", "Public School", "Laboratory", "Market", "Bank", "Courthouse", "Temple")
        private val SCIENCE_BUILDINGS = listOf("Library", "University", "Observatory", "Public School", "Laboratory")
        private val SCIENCE_TECH = listOf("Pottery", "Writing", "Calendar", "Drama and Poetry", "Philosophy",
            "Animal Husbandry", "Mining", "Archery", "Bronze Working", "Sailing", "The Wheel",
            "Horseback Riding", "Mathematics", "Currency", "Masonry", "Iron Working", "Theology",
            "Civil Service", "Education", "Optics", "Compass", "Astronomy", "Acoustics", "Trapping",
            "Construction", "Chivalry", "Machinery", "Physics", "Steel", "Banking", "Gunpowder",
            "Chemistry", "Economics", "Scientific Theory", "Metallurgy", "Rifling", "Military Science",
            "Replaceable Parts", "Engineering", "Guilds", "Metal Casting", "Printing Press", "Fertilizer")

        // 海洋混合：与 FullGameRoutesTest.Naval 同源（Work Boat 海上改良、海军舰队、登船跨海、远洋交战）。
        private val NAVAL_MILITARY = listOf("Infantry", "Rifleman", "Musketman", "Longswordsman",
            "Pikeman", "Swordsman", "Spearman", "Warrior", "Cavalry", "Knight", "Horseman")
        private val NAVAL_DEVELOPMENT = listOf("Lighthouse", "Harbor", "Library", "Monument", "Granary",
            "Market", "University", "Temple", "Bank", "Courthouse", "Workshop")
        private val NAVAL_TECH = listOf("Sailing", "Pottery", "Animal Husbandry", "Mining", "Writing",
            "Archery", "Bronze Working", "Optics", "Calendar", "Masonry", "The Wheel", "Mathematics",
            "Trapping", "Construction", "Philosophy", "Iron Working", "Compass", "Horseback Riding",
            "Machinery", "Physics", "Metal Casting", "Steel", "Astronomy", "Currency", "Banking",
            "Gunpowder", "Chemistry", "Navigation", "Economics", "Metallurgy", "Rifling", "Military Science",
            "Replaceable Parts", "Engineering", "Guilds", "Printing Press", "Fertilizer", "Acoustics", "Education")
        private val NAVAL_UNITS = listOf("Ship of the Line", "Frigate", "Privateer", "Caravel", "Trireme")
        // 远洋军舰（Astronomy 后可进远洋、跨海主动进攻敌岸）；Trireme 永远不能进远洋，只能近岸防御。
        private val OCEAN_NAVY = listOf("Ship of the Line", "Frigate", "Privateer", "Caravel")
    }
}
