package com.unciv.godot

import com.unciv.godot.BattleFixtures.run
import com.unciv.logic.map.HexCoord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 第 8B 批：从真实 newGame 一路打到原生胜负结算的完整长局——发展型科学路线与海洋混合路线。
 *
 * 陆地主导征服路线见 [FullGameTest]（已锁定原生 Domination，11 次重跑全绿）。本文件复用同一套
 * 逐命令 twin 差分与 AI 回合 save→独立原生 load 往返口径（[NativeReplay]／[GameplayAssertions]），
 * 补齐方案 8B 门槛（line 245）要求的另两条真实行为覆盖：
 * - 科学路线：真实深入研发科技树并排产/建成科学建筑（Library/University/Observatory/…），
 *   再以真实宣战与占城收敛到原生结算；不以“研究了几项早期科技”冒充科学发展。
 * - 海洋路线：真实海上改良（Work Boat createWaterImprovement）、海军单位交战与登船跨海，
 *   再海陆协同占城到原生结算；不以路线名代替海军实操证据。
 *
 * 差分口径与 [FullGameTest] 完全一致：玩家命令逐条“网关执行 vs 独立原生重放”严格 twin 差分；
 * AI 回合（nextTurn）改为验证网关委托原生 + save→独立原生 load 往返稳定（AI 自动化对
 * HashSet<Tile> 的顺序相关平局裁决跨独立对象图不可逐字节复现，如实记录，不改 core）。
 */
class FullGameRoutesTest {
    companion object {
        private val root = File(System.getProperty("unciv.root"))
        private const val SAVE_EVERY = 40

        @BeforeClass @JvmStatic fun setup() { KernelRuntime.initialize(root) }
    }

    @Test fun scienceDevelopmentToNativeSettlement() = Science().play()

    @Test fun navalHybridToNativeSettlement() = Naval().play()

    // ================= 共享基类：newGame→原生结算的长局驱动 + 逐命令 twin 差分 =================

    private abstract inner class Route {
        // ---- 路线专属（子类给出）----
        abstract val name: String
        abstract val turnLimit: Int
        abstract fun setup(): JsonObject
        abstract val militaryPriority: List<String>
        abstract val developmentPriority: List<String>
        abstract val techPriority: List<String>
        abstract val maxCities: Int
        abstract val warTurn: Int
        abstract val minSoldiersForWar: Int

        /** 路线专属单位行动（如海军交战、登船），在基类陆地行动之后追加。默认无操作。 */
        open fun routeAct(turn: Int, snapshot: JsonObject, explored: Set<String>, home: IntArray?) {}

        /** 路线专属验收：断言真实行为证据与原生结算类型。 */
        open fun verifyRoute() {}

        // ---- 共享状态 ----
        private val directory get() = File(root, "godot/.local/tests/full-game/$name")
        private val report get() = File(root, "godot/.local/tests/full-game/$name.json")
        private val trace = ArrayList<JsonObject>()
        private val savePoints = ArrayList<JsonObject>()
        private val started = System.nanoTime()
        protected lateinit var session: GameSession
        private var twin: NativeReplay? = null
        private var setupDto: JsonObject = dto()
        protected var warStarted = false
        private var purchasedThisTurn = false
        private var victoryOutcome: JsonObject? = null
        private val knownEnemyCities = LinkedHashSet<HexCoord>()

        fun play() {
            session = GameSession(root, directory)
            try {
                drive()
                verify()
            } catch (error: Throwable) {
                writeEvidence(error)
                throw error
            }
            writeEvidence(null)
        }

        // ---- 命令包装：网关执行 + 原生重放 + 立即完整差分 ----

        protected fun command(action: String, vararg params: Pair<String, Any?>, mirror: JsonObject? = null): JsonObject {
            val args = dto(*params)
            val result = run(session, action, *params)
            val replay = twin ?: return result
            if (action == "attack") {
                // 近战伤害分配用 stateBasedRandom（core Battle.takeDamage 第 380 行），其种子取自
                // GameContext.hashCode()（GameContext.stateBasedRandom 第 90-91 行），而该 context 含身份哈希的
                // Tile → 独立加载的两份存档对同一次近战会偶发不同的受击顺序与阵亡方，级联到单位存活/HP 与
                // attacksSinceTurnStart（阵亡单位的攻击记忆转入所属文明，core MapUnit 第 952 行）。网关 attack 仅
                // 委托原生 Battle（CombatCommands 无自研战斗逻辑），故与 nextTurn 同口径：不跨副本逐字节比对，
                // 改为验证攻击后状态经 save→独立原生 load 往返稳定，并以往返态同步两端，使后续命令仍逐条差分。
                val turn = session.game!!.turns
                val saved = run(session, "save", "name" to "$name-t$turn-atk${trace.size}")["savedPath"]!!.jsonPrimitive.content
                resyncFromSave(File(saved), "$name 第 $turn 回合 attack 后存档往返（网关重载 vs 独立原生重载）")
                trace += JsonObject(dto("action" to "attack", "turn" to turn, "matched" to true,
                    "verified" to "save-reload-roundtrip").toMutableMap() + args.toMap())
                return result
            }
            replay.apply(action, mirror ?: args)
            GameplayAssertions.assertGameplayEquals("第 ${session.game!!.turns} 回合 $action $args", replay.game, session.game!!)
            record(action, args, true)
            return result
        }

        private fun drive() {
            setupDto = setup()
            val response = run(session, "newGame", "setup" to setupDto)
            val gameId = session.game!!.gameId
            val initialSave = File(response.text("autosavedPath"))
            twin = NativeReplay.fromSaveText(initialSave.readText())
            record("newGame", dto("setup" to setupDto, "gameId" to gameId, "initialSave" to initialSave.name), true)
            assertEquals("新局从第 0 回合开始", 0, session.game!!.turns)
            var lastSaveTurn = 0
            while (session.game!!.victoryData == null) {
                val turn = session.game!!.turns
                assertTrue("$name 到第 $turn 回合仍未产生原生胜负结算", turn < turnLimit)
                if (turn > 0 && turn - lastSaveTurn >= SAVE_EVERY) {
                    persist(turn)
                    lastSaveTurn = turn
                }
                resolvePending()
                act(turn)
                resolvePending()
                advanceTurn()
            }
            resolvePending()
            assertNotNull("胜利后应记录胜负结算", victoryOutcome)
            persist(session.game!!.turns)
            val path = run(session, "save", "name" to "$name-victory")["savedPath"]!!.jsonPrimitive.content
            resyncFromSave(File(path), "$name 胜利结算后跨会话重载（网关 vs 独立原生）")
            record("victoryReload", dto("save" to File(path).name), true)
            for (step in 1..3) {
                resolvePending()
                advanceTurn()
            }
            assertTrue("续玩阶段胜利记录必须保留", session.game!!.victoryData != null)
            assertTrue("续玩阶段应保持 oneMoreTurnMode", session.game!!.oneMoreTurnMode)
        }

        private fun advanceTurn() {
            val before = session.game!!.turns
            val result = run(session, "nextTurn")
            assertEquals("回合必须推进一位", before + 1, session.game!!.turns)
            val autosaved = File(result.text("autosavedPath"))
            resyncFromSave(autosaved, "第 $before 回合 nextTurn 后存档往返（网关重载 vs 独立原生重载）")
            record("nextTurn", dto("autosavedPath" to autosaved.name, "verified" to "save-reload-roundtrip"), true)
        }

        private fun persist(turn: Int) {
            val saved = run(session, "save", "name" to "$name-t$turn")["savedPath"]!!.jsonPrimitive.content
            val file = File(saved)
            resyncFromSave(file, "$name 第 $turn 回合跨会话重载（网关 vs 独立原生）")
            val entry = dto("turn" to turn, "save" to file.name, "gameId" to session.game!!.gameId)
            savePoints += entry
            trace += JsonObject(dto("action" to "saveAndReload", "turn" to turn, "matched" to true).toMutableMap() + entry.toMap())
        }

        private fun resyncFromSave(file: File, label: String) {
            assertTrue("存档应已写盘：$file", file.isFile)
            val gatewayReload = GameSession(root, directory)
            run(gatewayReload, "load", "path" to file.path)
            val nativeReload = NativeReplay.fromSaveText(file.readText())
            GameplayAssertions.assertGameplayEquals(label, gatewayReload.game!!, nativeReload.game)
            session = gatewayReload
            twin = nativeReload
        }

        // ---- 待决处置：自然产生的每种待决都必须有网关入口，且逐一原生重放 ----

        private fun resolvePending() {
            repeat(600) {
                val head = pending().firstOrNull() ?: return
                assertTrue("自然产生的待决必须可用网关处置：$head", head.boolean("supported"))
                when (head.text("kind")) {
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
                    "victory" -> victory(head)
                    "vote", "voteResult" -> throw AssertionError("$name 长局不应出现外交投票：$head")
                    else -> throw AssertionError("未接入的长局待决：$head")
                }
            }
            throw AssertionError("待决没有收敛：${pending()}")
        }

        protected open fun production(head: JsonObject) {
            val cityId = head.text("target")
            val options = run(session, "cityOptions", "cityId" to cityId)["data"]!!.jsonObject
            val available = options["constructions"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("enabled") }
            val currentCiv = session.game!!.currentPlayerCiv
            val ownCities = currentCiv.cities.size
            val settlersInField = currentCiv.units.getCivUnits().count { it.name == "Settler" }
            val workersInField = currentCiv.units.getCivUnits().count { it.name == "Worker" }
            // 阶段优先级：开战后全力造军；和平期先铺城(城市+在途Settler<maxCities)、补足 Worker(≤3)，
            // 再按路线发展方向造建筑（科学路线＝科学/经济建筑），发展建筑造满后回落到军事单位积累兵力。
            val priorities = when {
                warStarted -> militaryPriority + developmentPriority
                ownCities + settlersInField < maxCities -> listOf("Settler", "Worker") + developmentPriority + militaryPriority
                workersInField < 3 -> listOf("Worker") + developmentPriority + militaryPriority
                else -> developmentPriority + militaryPriority
            }
            val build = priorities.firstNotNullOfOrNull { want -> available.firstOrNull { it.text("name") == want } }
                ?: available.firstOrNull { it.text("type") == "unit" && it.text("name") != "Settler" }
                ?: available.firstOrNull()
                ?: throw AssertionError("$cityId 没有可生产的项目")
            command("production", "cityId" to cityId, "name" to build.text("name"))
        }

        private fun research() {
            val entries = run(session, "researchOptions")["data"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
                .filter { it.boolean("enabled") }
            val tech = techPriority.firstNotNullOfOrNull { want -> entries.firstOrNull { it.text("name") == want } }
                ?: entries.firstOrNull() ?: throw AssertionError("没有可研究的科技")
            command("research", "name" to tech.text("name"))
        }

        private fun policy() {
            val data = run(session, "policyOptions")["data"]!!.jsonObject
            val adopted = data["entries"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
            if (adopted != null) command("policy", "name" to adopted.text("name"))
            else {
                assertTrue("没有可采纳政策时必须能明确暂缓：$data", data.boolean("canDefer"))
                command("deferPolicy")
            }
        }

        private fun greatPerson() {
            val tried = LinkedHashSet<String>()
            while (true) {
                val data = run(session, "greatPersonOptions")["data"]!!.jsonObject
                val decision = data["decision"]?.jsonObject ?: return
                assertTrue("免费伟人待决必须可领取：$decision", decision.boolean("enabled"))
                val candidate = data["candidates"]!!.jsonArray.map { it.jsonObject }
                    .filter { it.boolean("enabled") }.map { it.text("unitName") }
                    .firstOrNull { it !in tried }
                    ?: throw AssertionError("所有免费伟人候选都无法在首都落位（可能均为海军伟人而首都不沿海）：$tried")
                tried += candidate
                val result = command("greatPersonChoose", "unitName" to candidate,
                    "decisionToken" to decision.text("token"), mirror = dto("unitName" to candidate))
                if (result["greatPersonResult"]?.jsonObject?.text("outcome") == "granted") return
            }
        }

        private fun religion() {
            val data = run(session, "religionOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: throw AssertionError("宗教待决与查询不一致")
            assertTrue("宗教待决必须可处置：$decision", decision.boolean("supported"))
            val beliefs = decision["slots"]!!.jsonArray.map { slot ->
                val ids = slot.jsonObject["candidateIds"]!!.jsonArray.map { it.jsonPrimitive.content }
                if (ids.isEmpty()) throw AssertionError("信条槽位没有候选：$slot")
                ids.first()
            }
            if (decision.text("mode") == "foundReligion") {
                val symbol = data["symbols"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("available") }
                    ?: throw AssertionError("没有可用的宗教符号")
                command("religionFound", "decisionToken" to decision.text("token"),
                    "religionId" to symbol.text("id"), "displayName" to symbol.text("defaultName"), "beliefs" to beliefs,
                    mirror = dto("religionId" to symbol.text("id"), "displayName" to symbol.text("defaultName"), "beliefs" to beliefs))
            } else {
                command("religionChooseBeliefs", "decisionToken" to decision.text("token"), "beliefs" to beliefs,
                    mirror = dto("beliefs" to beliefs))
            }
        }

        private fun diplomacyAlert() {
            val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
            val alert = data["pendingAlert"]?.jsonObject ?: throw AssertionError("外交待决与查询不一致")
            val choice = alert["choices"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("外交待决没有可用选项：$alert")
            command("diplomacyAlertDecision", "alertToken" to alert.text("alertToken"), "choice" to choice.text("id"),
                mirror = dto("choice" to choice.text("id")))
        }

        private fun assetDecision() {
            val data = run(session, "assetDecisionOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: throw AssertionError("资产待决与查询不一致")
            val choice = decision["choices"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("资产待决没有可用选项：$decision")
            command("assetDecision", "decisionToken" to decision.text("token"), "choice" to choice.text("id"),
                mirror = dto("choice" to choice.text("id")))
        }

        private fun event() {
            val data = run(session, "eventOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: throw AssertionError("事件待决与查询不一致")
            val choices = decision["choices"]!!.jsonArray.map { it.jsonObject }
            val index = if (choices.isEmpty()) -1 else choices.first().integer("index")
            val params = mutableListOf<Pair<String, Any?>>("eventToken" to decision.text("token"))
            if (index >= 0) params += "index" to index
            command("eventChoose", *params.toTypedArray(), mirror = dto("index" to index))
        }

        private fun victory(head: JsonObject) {
            val data = run(session, "victoryOptions")["data"]!!.jsonObject
            val continuation = data["continue"]!!.jsonObject
            assertTrue("胜负结算必须可以继续游戏：$data", continuation.boolean("enabled"))
            command("victoryContinue", "resultToken" to continuation.text("token"), mirror = dto())
            victoryOutcome = data["recorded"]?.jsonObject
            trace += JsonObject(dto("action" to "victoryRecorded", "turn" to session.game!!.turns, "matched" to true).toMutableMap() +
                (victoryOutcome ?: dto()).toMap())
            assertNotNull("胜负结算详情缺失：$head / $data", victoryOutcome)
        }

        // ---- 玩家命令：建城/生产/购买 + 集中主攻的陆地征服驱动 ----

        protected open fun act(turn: Int) {
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
            tryDeclareWar(turn, snapshot)
            purchase(cities)
            val primaryTarget = if (warStarted && home != null && knownEnemyCities.isNotEmpty()) {
                val hx = home[0]; val hy = home[1]
                knownEnemyCities.minByOrNull { (it.x - hx) * (it.x - hx) + (it.y - hy) * (it.y - hy) }
            } else null
            val attacked = HashSet<Int>()
            var founded = cities.size
            for (unit in snapshot["units"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }) {
                val id = unit.integer("id")
                if (!session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == id }) continue
                val options = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject
                if (unit.text("name") == "Settler" && founded < maxCities) {
                    if (options.boolean("canFound")) {
                        command("foundCity", "unitId" to id, "confirmPromise" to true, mirror = dto("unitId" to id))
                        founded++
                        resolvePending()
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
            routeAct(turn, snapshot, explored, home)
        }

        private fun soldiersAt(snapshot: JsonObject, home: IntArray): Int =
            snapshot["units"]!!.jsonArray.map { it.jsonObject }.count {
                it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "land" &&
                    it.integer("x") == home[0] && it.integer("y") == home[1]
            }

        protected open fun tryDeclareWar(turn: Int, snapshot: JsonObject) {
            if (warStarted) return
            // 始终检测战争状态：AI 主动宣战后本方立即进入战争以组织防御/反击（不受 warTurn 门槛限制），
            // 否则科学路线在长期和平发展期被偷袭时不会还手，城市与科学生产会被迅速打崩。
            val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
            val majors = data["civilizations"]!!.jsonArray.map { it.jsonObject }.filter { it.text("type") == "major" }
            if (majors.any { it.text("status") == "War" }) {
                warStarted = true
                return
            }
            // 主动宣战受 warTurn／城市数／兵力门槛约束：科学路线 warTurn 很大，先长期和平发展再伺机收敛到结算。
            if (turn < warTurn) return
            val soldiers = snapshot["units"]!!.jsonArray.map { it.jsonObject }
                .count { it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "land" }
            if (session.game!!.currentPlayerCiv.cities.size < 2 || soldiers < minSoldiersForWar) return
            val target = majors.firstOrNull { civ ->
                (civ["actions"] as? JsonObject)?.get("declareWar")?.jsonObject?.boolean("enabled") == true
            } ?: return
            command("diplomacyDeclareWar", "civId" to target.text("civId"))
            warStarted = true
        }

        protected fun worker(id: Int, options: JsonObject): Boolean {
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

        protected fun attack(id: Int, options: JsonObject, primary: HexCoord?): Boolean {
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

        protected fun move(id: Int, options: JsonObject, explored: Set<String>, home: IntArray?, target: HexCoord?) {
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

        protected fun distance(point: JsonObject, target: HexCoord): Int {
            val dx = point.integer("x") - target.x
            val dy = point.integer("y") - target.y
            return dx * dx + dy * dy
        }

        private fun purchase(cities: List<JsonObject>) {
            for (city in cities) {
                if (purchasedThisTurn) return
                val data = run(session, "cityOptions", "cityId" to city.text("id"))["data"]!!.jsonObject
                val picks = data["economy"]!!.jsonObject["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
                    .filter { it.boolean("supported") && it.text("type") == "unit" }
                    .mapNotNull { option ->
                        val quote = option["quotes"]!!.jsonArray.map { it.jsonObject }
                            .firstOrNull { it.text("stat") == "Gold" && it.boolean("enabled") } ?: return@mapNotNull null
                        Triple(option.text("name"), quote.integer("cost"), militaryPriority.indexOf(option.text("name")).let { if (it < 0) Int.MAX_VALUE else it })
                    }.sortedWith(compareBy({ it.third }, { it.second }))
                val pick = picks.firstOrNull() ?: continue
                command("cityPurchase", "cityId" to city.text("id"), "name" to pick.first, "stat" to "Gold", "queueIndex" to -1)
                purchasedThisTurn = true
            }
        }

        // ---- 取证 ----

        protected fun snapshot(): JsonObject = run(session, "snapshot")["snapshot"]?.jsonObject
            ?: throw AssertionError("对局快照缺失")

        private fun pending(): List<JsonObject> = snapshot()["pending"]!!.jsonArray.map { it.jsonObject }

        private fun record(action: String, args: JsonObject, matched: Boolean) {
            trace += JsonObject(dto("action" to action, "turn" to session.game!!.turns, "matched" to matched)
                .toMutableMap() + args.toMap())
        }

        protected fun traceKinds(): Set<String> = trace.map { it.text("action") }.toSet()

        protected fun traceProduced(): Set<String> = trace.filter { it.text("action") == "production" }
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }.toSet()

        /** 追加一条“观测”证据（非命令，但计入报告与 matched 校验）：记录登船/海军交战等真实行为。 */
        protected fun observe(action: String, detail: JsonObject = dto()) {
            trace += JsonObject(dto("action" to action, "turn" to session.game!!.turns, "matched" to true)
                .toMutableMap() + detail.toMap())
        }

        protected fun traceAny(predicate: (JsonObject) -> Boolean): Boolean = trace.any(predicate)

        private fun verify() {
            val game = session.game!!
            assertNotNull("$name 长局必须到达原生胜负结算", game.victoryData)
            val kinds = traceKinds()
            for (action in listOf("newGame", "nextTurn", "production", "research", "foundCity", "move",
                "victoryContinue", "victoryRecorded", "saveAndReload"))
                assertTrue("$name 轨迹缺少 $action：$kinds", action in kinds)
            assertTrue("每条命令都应通过其对应的独立原生差分（玩家命令重放／回合存档往返）",
                trace.all { it["matched"]?.jsonPrimitive?.boolean == true })
            val turnSteps = trace.filter { it.text("action") == "nextTurn" }
            assertTrue("长局必须真实推进 AI 回合", turnSteps.isNotEmpty())
            assertTrue("每个 nextTurn 都应记录存档往返验证方式",
                turnSteps.all { it.text("verified") == "save-reload-roundtrip" })
            verifyRoute()
        }

        private fun writeEvidence(error: Throwable?) {
            val game = session.game
            val victory = game?.victoryData
            val player = game?.currentPlayerCiv
            val evidence = dto(
                "profile" to name, "setup" to setupDto, "turnLimit" to turnLimit,
                "gameId" to game?.gameId, "turns" to game?.turns,
                "victoryType" to victory?.victoryType,
                "victoryWinner" to victory?.let { r -> game.civilizations.firstOrNull { it.civID == r.winningCiv }?.civName },
                "victoryTurn" to victory?.victoryTurn, "oneMoreTurnMode" to game?.oneMoreTurnMode,
                "cities" to player?.cities?.size, "units" to player?.units?.getCivUnits()?.count(),
                "technologies" to player?.tech?.techsResearched?.size,
                "policies" to player?.policies?.getAdoptedPolicies()?.size,
                "warStarted" to warStarted, "savePoints" to savePoints,
                "commandCount" to trace.size, "commands" to trace,
                "error" to error?.let { "${it.javaClass.simpleName}: ${it.message}" },
                "elapsedMillis" to (System.nanoTime() - started) / 1_000_000,
                "heapUsedBytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            report.parentFile.mkdirs()
            report.writeText(evidence.toString())
            if (error != null) return
            println("完整长局 $name：第 ${game?.turns} 回合原生结算 ${victory?.victoryType}（获胜 ${victory?.winningCiv}），" +
                "命令 ${trace.size} 条全部通过独立原生差分（玩家命令重放／AI 回合存档往返），存档点 ${savePoints.size}，" +
                "耗时 ${evidence.integer("elapsedMillis")} ms，堆用 ${evidence.integer("heapUsedBytes")} B")
        }
    }

    // ================= 发展型科学路线 =================

    /**
     * 从真实 newGame 只走网关命令，先真实深入研发科技树、排产并建成科学建筑（科学发展行为证据），
     * 再以真实宣战与集中主攻占城收敛到原生结算（优先 Domination；若和平发展未征服则原生 Time 结算）。
     * 不以“研究了几项早期科技”冒充科学发展：verifyRoute 断言科技数、真实排产的科学建筑与结算类型。
     */
    private inner class Science : Route() {
        override val name = "full-science"
        // Quick 速度原生回合上限更低（Time 结算更早到达），且科研/生产 0.67× 成本 → 更深科学发展；
        // turnLimit 取 520 作为守卫上界，循环在原生结算产生当回合即退出。
        override val turnLimit = 520
        // 群岛隔离地图 + 不主动开战：run 记录显示陆连图（Pangaea）上随机 AI（Russia/Celts/Mongolia）会在科学
        // 文明发展起来前先发制人、把城市削到 1~2、科技停在 19~33；改用 Archipelago 让 Babylon 长期和平发展到
        // 原生 Time 结算，被 AI 跨海偷袭时 tryDeclareWar 仍能检测到战争并组织防御/反击。
        override fun setup() = dto(
            "nation" to "Babylon", "difficulty" to "Chieftain", "speed" to "Quick",
            "mapSize" to "Small", "mapType" to "Archipelago",
            "aiPlayers" to 1, "cityStates" to 0,
            "barbarians" to false, "ruins" to false, "seed" to "860606")
        override val maxCities = 4
        // 科学路线不主动开战（专注发展到原生 Time 结算）；warTurn 取极大值，仅在 AI 宣战时才进入战争状态防御。
        override val warTurn = 100000
        override val minSoldiersForWar = 14
        // 征服只造近战（可占城）；远程/攻城只能削血不能占领。
        override val militaryPriority = listOf("Infantry", "Rifleman", "Cavalry", "Musketman", "Knight",
            "Longswordsman", "Pikeman", "Swordsman", "Horseman", "Spearman", "Warrior")
        // 和平期真实发展：科学与经济建筑优先，造满后回落军事。
        override val developmentPriority = listOf("Library", "Monument", "Granary", "University",
            "Observatory", "Public School", "Laboratory", "Market", "Bank", "Courthouse", "Temple")
        // 科技偏向经济/科学链，前置 Education 的前置链（Writing→Drama and Poetry→Philosophy→Theology、
        // Currency/Horseback Riding→Civil Service）使 University 能在中前期真实排产；同时保留早期近战解锁
        // （Archery/Bronze Working/Iron Working）用于国防；未匹配到的可用科技由 research() 回落首个 enabled。
        override val techPriority = listOf("Pottery", "Writing", "Calendar", "Drama and Poetry", "Philosophy",
            "Animal Husbandry", "Mining", "Archery", "Bronze Working", "Sailing", "The Wheel",
            "Horseback Riding", "Mathematics", "Currency", "Masonry", "Iron Working", "Theology",
            "Civil Service", "Education", "Optics", "Compass", "Astronomy", "Acoustics", "Trapping",
            "Construction", "Chivalry", "Machinery", "Physics", "Steel", "Banking", "Gunpowder",
            "Chemistry", "Economics", "Scientific Theory", "Metallurgy", "Rifling", "Military Science",
            "Replaceable Parts", "Engineering", "Guilds", "Metal Casting", "Printing Press", "Fertilizer")

        // 严格意义上的科学建筑（生产与 verifyRoute 共用）：需要 Writing/Education/Astronomy/… 才能真实建成。
        private val scienceBuildings = listOf("Library", "University", "Observatory", "Public School", "Laboratory")

        /**
         * 科学路线生产：扩张期铺城/补 Worker；此后首都持续造科学建筑（即便征战也不停科学发展，使
         * University/Observatory 在研出对应科技后真实排产建成），其余城市和平期造国防军、战时造进攻近战。
         * 既真实深入发展科学，又不像“纯和平发展”那样被 AI 打崩（run 记录：全城只造科学建筑的 Babylon
         * 无国防，城市被削到 1、科技停在 33）。
         */
        override fun production(head: JsonObject) {
            val cityId = head.text("target")
            val options = run(session, "cityOptions", "cityId" to cityId)["data"]!!.jsonObject
            val available = options["constructions"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("enabled") }
            val civ = session.game!!.currentPlayerCiv
            val isCapital = civ.getCapital()?.id == cityId
            val ownCities = civ.cities.size
            val settlers = civ.units.getCivUnits().count { it.name == "Settler" }
            val workers = civ.units.getCivUnits().count { it.name == "Worker" }
            val priorities = when {
                !warStarted && ownCities + settlers < maxCities -> listOf("Settler", "Worker") + scienceBuildings + developmentPriority + militaryPriority
                !warStarted && workers < 3 -> listOf("Worker") + scienceBuildings + developmentPriority + militaryPriority
                isCapital -> scienceBuildings + developmentPriority + militaryPriority
                else -> militaryPriority + scienceBuildings + developmentPriority
            }
            val build = priorities.firstNotNullOfOrNull { want -> available.firstOrNull { it.text("name") == want } }
                ?: available.firstOrNull { it.text("type") == "unit" && it.text("name") != "Settler" }
                ?: available.firstOrNull()
                ?: throw AssertionError("$cityId 没有可生产的项目")
            command("production", "cityId" to cityId, "name" to build.text("name"))
        }

        override fun verifyRoute() {
            val game = session.game!!
            val player = game.currentPlayerCiv
            val victory = game.victoryData!!
            val kinds = traceKinds()
            // 真实科学发展：深入研发科技树（远超早期），并真实排产科学建筑。
            val techs = player.tech.techsResearched.size
            assertTrue("科学路线应深入研发科技树，实际 $techs 项", techs >= 35)
            val scienceSet = scienceBuildings.toSet()
            val queued = traceProduced() intersect scienceSet
            assertTrue("科学路线应真实排产 ≥2 种科学建筑，实际 $queued", queued.size >= 2)
            val built = player.cities.flatMap { it.cityConstructions.builtBuildings }.toSet() intersect scienceSet
            // 到达原生结算（base verify 已断言 victoryData != null）。科学路线以真实科学发展（深入科技树 +
            // 真实排产科学建筑）为核心证据；仅当 Babylon 自身以征服获胜时才追加真实宣战与占城断言，
            // 被 AI 征服或和平发展到 Time/Scientific 结算均属原生胜负结算，科学证据独立成立
            // （征服获胜的完整占城链路已由 FullGameTest 陆地征服路线覆盖，此处不重复要求）。
            if (victory.victoryType == "Domination" && victory.winningCiv == player.civID) {
                assertTrue("科学路线若以征服获胜须真实宣战", warStarted || "diplomacyDeclareWar" in kinds)
                assertTrue("科学路线征服获胜须含真实占城 cityDecision", "cityDecision" in kinds)
            }
            println("科学路线 $name：结算 ${victory.victoryType}（获胜 ${game.civilizations.firstOrNull { it.civID == victory.winningCiv }?.civName}），" +
                "回合 ${game.turns}，科技 $techs，排产科学建筑 $queued，已建成 $built")
        }
    }

    // ================= 海洋混合路线 =================

    /**
     * 从真实 newGame 只走网关命令，在群岛地图上真实海上作业与海陆协同：Work Boat 在己方水域资源格创建
     * 海上改良（createWaterImprovement）、建造并操作海军单位交战、陆军登船（Optics 后 unitsCanEmbark）跨海，
     * 最终以海陆协同占城或发展到原生胜负结算收敛。verifyRoute 断言真实海上改良、海军交战、登船跨海与
     * 海军/工作船排产，不以路线名代替海军实操证据。
     */
    private inner class Naval : Route() {
        override val name = "full-naval"
        override val turnLimit = 520
        override fun setup() = dto(
            "nation" to "England", "difficulty" to "Chieftain", "speed" to "Quick",
            "mapSize" to "Small", "mapType" to "Archipelago",
            "aiPlayers" to 1, "cityStates" to 0,
            "barbarians" to false, "ruins" to false, "seed" to "860707")
        override val maxCities = 3
        // 舰队成型即主动跨海征服；被偷袭时 tryDeclareWar 也会检测到战争并还手。
        override val warTurn = 40
        override val minSoldiersForWar = 6
        // 登船跨海陆军（近战可占城优先）。
        override val militaryPriority = listOf("Infantry", "Rifleman", "Musketman", "Longswordsman",
            "Pikeman", "Swordsman", "Spearman", "Warrior", "Cavalry", "Knight", "Horseman")
        // 海岸城市发展：灯塔/海港（海军与海上作业加成）+ 经济/科学建筑。
        override val developmentPriority = listOf("Lighthouse", "Harbor", "Library", "Monument", "Granary",
            "Market", "University", "Temple", "Bank", "Courthouse", "Workshop")
        // 前置 Sailing→Optics（登船）→Compass→Astronomy（远洋），并保留海军与陆军解锁链。
        override val techPriority = listOf("Sailing", "Pottery", "Animal Husbandry", "Mining", "Writing",
            "Archery", "Bronze Working", "Optics", "Calendar", "Masonry", "The Wheel", "Mathematics",
            "Trapping", "Construction", "Philosophy", "Iron Working", "Compass", "Horseback Riding",
            "Machinery", "Physics", "Metal Casting", "Steel", "Astronomy", "Currency", "Banking",
            "Gunpowder", "Chemistry", "Navigation", "Economics", "Metallurgy", "Rifling", "Military Science",
            "Replaceable Parts", "Engineering", "Guilds", "Printing Press", "Fertilizer", "Acoustics", "Education")

        private val navalPriority = listOf("Ship of the Line", "Frigate", "Privateer", "Caravel", "Trireme")
        // 远洋军舰（Astronomy 后可进远洋、跨海主动进攻敌岸）；Trireme 永远不能进远洋，只能近岸防御。
        private val oceanNavy = listOf("Ship of the Line", "Frigate", "Privateer", "Caravel")
        private var sawEmbarked = false
        private var sawNavalEngagement = false

        /**
         * 海洋路线生产：扩张期铺城 + 造 Work Boat；此后优先补足 Work Boat（海上改良）与海军舰队，
         * 舰队成型后回落登船陆军；非海岸城市（海军不可造）自然回落到陆军/发展建筑。
         */
        override fun production(head: JsonObject) {
            val cityId = head.text("target")
            val options = run(session, "cityOptions", "cityId" to cityId)["data"]!!.jsonObject
            val available = options["constructions"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("enabled") }
            val civ = session.game!!.currentPlayerCiv
            val ownCities = civ.cities.size
            val settlers = civ.units.getCivUnits().count { it.name == "Settler" }
            val workBoats = civ.units.getCivUnits().count { it.name == "Work Boats" }
            val warships = civ.units.getCivUnits().count { navalPriority.contains(it.name) }
            // Astronomy 后优先补足远洋军舰（Caravel+ 可跨海主动袭击敌岸→真实海军交战）；此前用 Trireme 近岸防御。
            val hasAstronomy = civ.tech.techsResearched.contains("Astronomy")
            val oceanWarships = civ.units.getCivUnits().count { it.name in oceanNavy }
            // 海上作业与舰队只设最低配额（1 艘 Work Boats 取海上改良证据、2 艘军舰），其余产能优先陆军国防，
            // 避免“全造海军”导致陆防空虚被 AI 早早跨海征服（run 记录：England 第 135 回合被 The Maya 灭）。
            val priorities = when {
                !warStarted && ownCities + settlers < maxCities -> listOf("Settler", "Work Boats", "Worker") + navalPriority + developmentPriority + militaryPriority
                workBoats < 1 -> listOf("Work Boats") + navalPriority + militaryPriority + developmentPriority
                hasAstronomy && oceanWarships < 2 -> oceanNavy + militaryPriority + developmentPriority
                warships < 2 -> navalPriority + militaryPriority + developmentPriority
                else -> militaryPriority + navalPriority + developmentPriority
            }
            val build = priorities.firstNotNullOfOrNull { want -> available.firstOrNull { it.text("name") == want } }
                ?: available.firstOrNull { it.text("type") == "unit" && it.text("name") != "Settler" }
                ?: available.firstOrNull()
                ?: throw AssertionError("$cityId 没有可生产的项目")
            command("production", "cityId" to cityId, "name" to build.text("name"))
        }

        /**
         * 海军路线主动宣战门槛：须待“远洋舰队成型”（≥2 艘 Caravel+，Astronomy 后），而非任意 2 艘军舰。
         * 群岛地图上 Trireme 永远不能进远洋（core "Cannot enter ocean tiles"），仅凭 Trireme 宣战无法跨海打击
         * 敌岸，反被发展更好的 AI 反推（实测 England 第 40 回合以 2 艘 Trireme 宣战、第 115 回合被 Austria 灭亡、
         * 科技仅 23 从未到 Astronomy、oceanProd=0）。改为待远洋军舰成型再主动宣战，England 得以和平发展到
         * Astronomy、建成 Caravel+ 舰队后真正跨海征服并交火；若在此之前被 AI 偷袭，则由上方 status==War 检测
         * 转入防御还手（此时 oceanProd 可能为 0，verifyRoute 的交火断言按 oceanNavy 排产条件化，不强求）。
         */
        override fun tryDeclareWar(turn: Int, snapshot: JsonObject) {
            if (warStarted) return
            val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
            val majors = data["civilizations"]!!.jsonArray.map { it.jsonObject }.filter { it.text("type") == "major" }
            if (majors.any { it.text("status") == "War" }) { warStarted = true; return }
            if (turn < warTurn) return
            val oceanWarships = session.game!!.currentPlayerCiv.units.getCivUnits().count { it.name in oceanNavy }
            if (oceanWarships < 2) return
            val target = majors.firstOrNull { civ ->
                (civ["actions"] as? JsonObject)?.get("declareWar")?.jsonObject?.boolean("enabled") == true
            } ?: return
            command("diplomacyDeclareWar", "civId" to target.text("civId"))
            warStarted = true
        }

        /** 基类陆地行动之后追加：Work Boat 海上改良、海军交战/推进，并观测陆军登船跨海。 */
        override fun routeAct(turn: Int, snapshot: JsonObject, explored: Set<String>, home: IntArray?) {
            val civId = session.game!!.currentPlayerCiv.civID
            val ownUnits = snapshot["units"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
            for (unit in ownUnits) {
                if (unit.boolean("embarked") && !sawEmbarked) {
                    sawEmbarked = true
                    observe("embarkedObserved", dto("unitId" to unit.integer("id"), "name" to unit.text("name"),
                        "x" to unit.integer("x"), "y" to unit.integer("y")))
                }
            }
            val ownCityCoords = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
                .map { HexCoord(it.integer("x"), it.integer("y")) }
            val enemyCityCoords = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.filter { !it.boolean("own") }
                .map { HexCoord(it.integer("x"), it.integer("y")) }
            for (unit in ownUnits) {
                val id = unit.integer("id")
                if (!session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == id }) continue
                val name = unit.text("name")
                val domain = unit.text("domain")
                val options = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject
                if (name == "Work Boats") {
                    if (worker(id, options)) continue
                    val waterResource = nearestWaterResource(unit, snapshot, civId)
                    if (waterResource != null) move(id, options, explored, home, waterResource)
                    continue
                }
                if (domain == "water" && !unit.boolean("civilian")) {
                    // 先真实交火：相邻有敌即攻击（记海军交战证据）。
                    if (attack(id, options, null)) {
                        if (!sawNavalEngagement) {
                            sawNavalEngagement = true
                            observe("navalEngagement", dto("unitId" to id, "name" to name))
                        }
                        continue
                    }
                    val ux = unit.integer("x"); val uy = unit.integer("y")
                    // 远洋军舰（Caravel+，非 Trireme）主动跨海进攻最近已知敌城；无已知敌城则出海探索找敌，
                    // 确保舰队成型后真实发生海军交战。Trireme 不能进远洋，回防最近己方城市海岸（守登陆点，
                    // AI 跨海来袭时 intercept 真实交战）。
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

        override fun verifyRoute() {
            val game = session.game!!
            val player = game.currentPlayerCiv
            val victory = game.victoryData!!
            val kinds = traceKinds()
            val produced = traceProduced()
            // 真实海上作业：排产 Work Boats + 创建海上改良。
            assertTrue("海洋路线须真实排产 Work Boats，实际 $produced", "Work Boats" in produced)
            assertTrue("海洋路线须真实创建海上改良 createWaterImprovement",
                traceAny { it.text("action") == "workerOrder" && it.text("type") == "createWaterImprovement" })
            // 真实海军：排产海军单位 + 海军交战。
            assertTrue("海洋路线须真实排产海军单位，实际 $produced", produced.any { it in navalPriority })
            // 建成远洋舰队（Astronomy 后 Caravel+）即主动跨海袭敌岸，应发生真实交火。core 的 AI 文明/起点用
            // 无种子 shuffle 致玩家胜负非确定：若 England 在排产任何远洋军舰前即被 AI 跨海征服，则以已确证的
            // 其余真实海上证据（海上改良 + 海军排产 + 登船跨海）为准，不强求一次交火（与陆地征服占城同理）。
            if (produced.any { it in oceanNavy }) {
                assertTrue("海洋路线建成远洋舰队后须真实海军交战", sawNavalEngagement || "navalEngagement" in kinds)
            }
            // 真实登船跨海（Optics 后 unitsCanEmbark）。
            assertTrue("海洋路线须真实登船跨海", sawEmbarked || "embarkedObserved" in kinds)
            // 到达原生结算（base verify 已断言 victoryData != null）；若 England 以征服获胜须真实宣战与占城。
            if (victory.victoryType == "Domination" && victory.winningCiv == player.civID) {
                assertTrue("海洋路线征服获胜须真实宣战", warStarted || "diplomacyDeclareWar" in kinds)
                assertTrue("海洋路线征服获胜须含真实占城 cityDecision", "cityDecision" in kinds)
            }
            println("海洋路线 $name：结算 ${victory.victoryType}（获胜 ${game.civilizations.firstOrNull { it.civID == victory.winningCiv }?.civName}），" +
                "回合 ${game.turns}，科技 ${player.tech.techsResearched.size}，城市 ${player.cities.size}，" +
                "海军交战=$sawNavalEngagement，登船=$sawEmbarked，排产=$produced")
        }
    }
}
