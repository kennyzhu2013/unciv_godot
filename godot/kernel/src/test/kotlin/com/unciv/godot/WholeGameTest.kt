package com.unciv.godot

import com.unciv.godot.BattleFixtures.run
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 第 8 批整局验收：从真实 newGame 开始、只走网关命令的固定种子长局。
 *
 * 与第 1—7 批的专项测试不同，这里不预置任何“动作前”状态：地图、文明、起始单位全部由
 * [GameSession] 的 newGame 交给原生 GameStarter 生成，玩家的命令与 AI 回合也都只经会话入口。
 * 三条路线分别是陆地主导征服、发展型科学与海洋混合，另有一条 Medium 短局只用于记录大地图
 * 耗时与内存，避免把地图规模混进其余三条的结论。
 *
 * 每回合顺序固定为：处理待决 → 下达命令 → 再处理命令新产生的待决 → 原生 nextTurn。
 * 自然产生的待决若 supported=false 或没有网关入口，立即失败并打印该待决（按第 8 批口径，
 * 这属于对应批次未完成）。完整轨迹与耗时写入 godot/.local/tests/whole-game/<name>.json。
 */
class WholeGameTest {
    private data class Profile(
        val name: String, val mapSize: String, val mapType: String, val aiPlayers: Int, val cityStates: Int,
        val seed: String, val turns: Int, val maxCities: Int, val minTechnologies: Int, val warTurn: Int?,
        val constructions: List<String>, val technologies: List<String>
    )

    companion object {
        private val root = File(System.getProperty("unciv.root"))
        @BeforeClass @JvmStatic fun setup() { KernelRuntime.initialize(root) }
    }

    private val profiles = listOf(
        Profile("land-conquest", "Tiny", "Pangaea", 2, 0, "860101", 70, 3, 4, 40,
            listOf("Warrior", "Worker", "Settler", "Archer", "Monument", "Library", "Granary"),
            listOf("Archery", "Bronze Working", "Pottery", "Writing", "Animal Husbandry", "Mining", "Calendar")),
        Profile("science", "Small", "Small Continents", 3, 0, "860202", 60, 4, 6, null,
            listOf("Library", "Monument", "Settler", "Worker", "Granary", "Archer"),
            listOf("Pottery", "Writing", "Philosophy", "Calendar", "Mining", "Animal Husbandry", "Trapping", "Archery")),
        Profile("naval", "Small", "Archipelago", 3, 2, "860303", 60, 3, 5, null,
            listOf("Work Boat", "Library", "Monument", "Settler", "Worker", "Trireme", "Galley"),
            listOf("Sailing", "Optics", "Pottery", "Writing", "Calendar", "Mining", "Animal Husbandry")),
        // Medium 只测大地图快照／AI 回合的耗时与内存，不参与“至少 60 回合”的结论。
        Profile("medium-baseline", "Medium", "Pangaea", 3, 0, "860404", 12, 1, 1, null,
            listOf("Library", "Monument", "Settler", "Worker"),
            listOf("Pottery", "Writing", "Calendar"))
    )

    @Test fun landConquestLongGame() = play(profiles[0])

    @Test fun scienceLongGame() = play(profiles[1])

    @Test fun navalLongGame() = play(profiles[2])

    @Test fun mediumMapPerformanceBaseline() = play(profiles[3])

    private fun play(profile: Profile) {
        Route(profile).play()
    }

    private inner class Route(private val profile: Profile) {
        private val directory = File(root, "godot/.local/tests/whole-game/${profile.name}")
        private val report = File(root, "godot/.local/tests/whole-game/${profile.name}.json")
        private val trace = ArrayList<JsonObject>()
        private val started = System.nanoTime()
        private var session = GameSession(root, directory)
        private var warStarted = false
        private var purchasedThisTurn = false

        fun play() {
            try {
                drive()
                verify()
            } catch (error: Throwable) {
                // 失败也落盘轨迹，否则“某一回合开始不可推进”只能看到一行断言。
                writeEvidence(error)
                throw error
            }
            writeEvidence(null)
        }

        private fun drive() {
            run(session, "newGame", "setup" to dto(
                "nation" to "Rome", "difficulty" to "Prince", "speed" to "Standard",
                "mapSize" to profile.mapSize, "mapType" to profile.mapType,
                "aiPlayers" to profile.aiPlayers, "cityStates" to profile.cityStates,
                "barbarians" to false, "ruins" to false, "seed" to profile.seed))
            assertEquals("新局从第 0 回合开始", 0, session.game!!.turns)
            record("newGame", "mapSize" to profile.mapSize, "mapType" to profile.mapType, "seed" to profile.seed)
            // 固定地图种子不固定 gameId 与完整发展轨迹。最低回合数之后仍须达到研发门槛，
            // 只允许继续真实命令／AI 回合，并以两倍预算封顶；不降断言、不重开抽取更快的对局。
            while (session.game!!.turns < profile.turns ||
                session.game!!.currentPlayerCiv.tech.techsResearched.size < profile.minTechnologies) {
                val turn = session.game!!.turns
                assertTrue("${profile.name} 到第 $turn 回合仍未达到研发门槛 ${profile.minTechnologies}",
                    turn < profile.turns * 2)
                if (turn == profile.turns / 2) saveAndReload(turn)
                resolvePending()
                act(turn)
                resolvePending()
                val before = session.game!!.turns
                run(session, "nextTurn")
                assertEquals("回合必须推进一位", before + 1, session.game!!.turns)
                record("nextTurn")
            }
        }

        // ---- 跨会话重载：期望端是写盘前的当前局；此处没有操作系统进程退出重启 ----

        private fun saveAndReload(turn: Int) {
            val expected = session.game!!
            val path = run(session, "save", "name" to "mid-${profile.name}")["savedPath"]!!.jsonPrimitive.content
            assertTrue("中点存档应已写盘", File(path).isFile)
            val continued = GameSession(root, directory)
            run(continued, "load", "path" to path)
            assertEquals("继续后的回合", turn, continued.game!!.turns)
            GameplayAssertions.assertGameplayEquals("${profile.name} 保存后跨会话重载（第 $turn 回合）", expected, continued.game!!)
            session = continued
            record("reload", "turn" to turn, "save" to File(path).name)
        }

        // ---- 待决处置：每一种自然产生的待决都必须有网关入口 ----

        private fun resolvePending() {
            repeat(600) {
                val head = pending().firstOrNull() ?: return
                assertTrue("自然产生的待决必须可用网关处置：$head", head.boolean("supported"))
                when (head.text("kind")) {
                    "production" -> production(head)
                    "research" -> research()
                    "policy" -> policy()
                    "alert" -> {
                        run(session, "acknowledge")
                        record("acknowledge", "message" to head.text("message"))
                    }
                    "trade" -> {
                        run(session, "declineTrade")
                        record("declineTrade", "message" to head.text("message"))
                    }
                    "greatPerson" -> greatPerson()
                    "religion" -> religion()
                    "diplomacyAlert" -> diplomacyAlert()
                    "cityCapture" -> cityCapture(head)
                    "assetDecision" -> assetDecision()
                    "event" -> event()
                    "victory" -> victory(head)
                    // 外交投票需要联合国与原子时代，60—70 回合的原生对局不可能到达；
                    // 若真的出现，说明长局已经偏离前提，直接按未完成处理而不是静默跳过。
                    "vote", "voteResult" -> throw AssertionError("长局不应出现外交投票：$head")
                    else -> throw AssertionError("未接入的长局待决：$head")
                }
            }
            throw AssertionError("待决没有收敛：${pending()}")
        }

        private fun production(head: JsonObject) {
            val cityId = head.text("target")
            val options = run(session, "cityOptions", "cityId" to cityId)["data"]!!.jsonObject
            val available = options["constructions"]!!.jsonArray.map { it.jsonObject }
            val build = profile.constructions.firstNotNullOfOrNull { name ->
                available.firstOrNull { it.text("name") == name && it.boolean("enabled") }
            } ?: available.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("$cityId 没有可生产的项目：$available")
            run(session, "production", "cityId" to cityId, "name" to build.text("name"))
            record("production", "cityId" to cityId, "name" to build.text("name"))
        }

        private fun research() {
            val entries = run(session, "researchOptions")["data"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
            val tech = profile.technologies.firstNotNullOfOrNull { name ->
                entries.firstOrNull { it.text("name") == name && it.boolean("enabled") }
            } ?: entries.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("没有可研究的科技")
            run(session, "research", "name" to tech.text("name"))
            record("research", "name" to tech.text("name"))
        }

        private fun policy() {
            val data = run(session, "policyOptions")["data"]!!.jsonObject
            val entries = data["entries"]!!.jsonArray.map { it.jsonObject }
            val adopted = entries.firstOrNull { it.boolean("enabled") }
            if (adopted != null) {
                run(session, "policy", "name" to adopted.text("name"))
                record("policy", "name" to adopted.text("name"))
            } else {
                assertTrue("没有可采纳政策时必须能明确暂缓：$data", data.boolean("canDefer"))
                run(session, "deferPolicy")
                record("deferPolicy")
            }
        }

        private fun greatPerson() {
            val data = run(session, "greatPersonOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: throw AssertionError("免费伟人待决与查询不一致")
            assertTrue("免费伟人待决必须可领取：$decision", decision.boolean("enabled"))
            val candidate = data["candidates"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("没有可选的免费伟人：$data")
            run(session, "greatPersonChoose", "unitName" to candidate.text("unitName"),
                "decisionToken" to decision.text("token"))
            record("greatPersonChoose", "unitName" to candidate.text("unitName"))
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
                run(session, "religionFound", "decisionToken" to decision.text("token"),
                    "religionId" to symbol.text("id"), "displayName" to symbol.text("defaultName"), "beliefs" to beliefs)
                record("religionFound", "religionId" to symbol.text("id"))
            } else {
                run(session, "religionChooseBeliefs", "decisionToken" to decision.text("token"), "beliefs" to beliefs)
                record("religionChooseBeliefs", "mode" to decision.text("mode"))
            }
        }

        private fun diplomacyAlert() {
            val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
            val alert = data["pendingAlert"]?.jsonObject ?: throw AssertionError("外交待决与查询不一致")
            val choice = alert["choices"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("外交待决没有可用选项：$alert")
            run(session, "diplomacyAlertDecision", "alertToken" to alert.text("alertToken"), "choice" to choice.text("id"))
            record("diplomacyAlertDecision", "type" to alert.text("type"), "choice" to choice.text("id"))
        }

        private fun cityCapture(head: JsonObject) {
            val cityId = head.text("target")
            // 与原生占城弹窗默认的“傀儡”一致：不额外接管人口与生产，避免改变城市管理语义。
            run(session, "cityDecision", "cityId" to cityId, "choice" to "puppet")
            record("cityDecision", "cityId" to cityId, "choice" to "puppet")
        }

        private fun assetDecision() {
            val data = run(session, "assetDecisionOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: throw AssertionError("资产待决与查询不一致")
            val choice = decision["choices"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.boolean("enabled") }
                ?: throw AssertionError("资产待决没有可用选项：$decision")
            run(session, "assetDecision", "decisionToken" to decision.text("token"), "choice" to choice.text("id"))
            record("assetDecision", "type" to decision.text("type"), "choice" to choice.text("id"))
        }

        private fun event() {
            val data = run(session, "eventOptions")["data"]!!.jsonObject
            val decision = data["decision"]?.jsonObject ?: throw AssertionError("事件待决与查询不一致")
            val choices = decision["choices"]!!.jsonArray.map { it.jsonObject }
            if (choices.isEmpty()) {
                run(session, "eventChoose", "eventToken" to decision.text("token"))
                record("eventChoose", "index" to -1)
            } else {
                val index = choices.first().integer("index")
                run(session, "eventChoose", "eventToken" to decision.text("token"), "index" to index)
                record("eventChoose", "index" to index)
            }
        }

        private fun victory(head: JsonObject) {
            val data = run(session, "victoryOptions")["data"]!!.jsonObject
            val continuation = data["continue"]!!.jsonObject
            assertTrue("胜负结算必须可以继续游戏：$data", continuation.boolean("enabled"))
            run(session, "victoryContinue", "resultToken" to continuation.text("token"))
            record("victoryContinue", "outcome" to head.text("target"))
        }

        // ---- 玩家命令：建城、生产与购买之外的单位行动都只走地图命令 ----

        private fun act(turn: Int) {
            purchasedThisTurn = false
            val snapshot = snapshot()
            val cities = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
            val explored = snapshot["tiles"]!!.jsonArray.map { it.jsonObject }
                .filter { it.text("visibility") != "unknown" }
                .map { "${it.integer("x")}:${it.integer("y")}" }.toHashSet()
            val home = cities.firstOrNull()?.let { intArrayOf(it.integer("x"), it.integer("y")) }
            if (profile.warTurn != null && turn >= profile.warTurn) pursueWar()
            val units = snapshot["units"]!!.jsonArray.map { it.jsonObject }.filter { it.boolean("own") }
            // 三个以上陆军单位才向可见敌方城市推进；否则回防首都，避免长局因空城被 AI 直接占领。
            val soldiers = units.count { it.text("domain") == "land" && !it.boolean("civilian") }
            val enemy = if (warStarted && soldiers >= 3) visibleEnemyCity(snapshot) else null
            purchase(cities)
            var founded = cities.size
            for (unit in units) {
                val options = run(session, "unitOptions", "unitId" to unit.integer("id"))["data"]!!.jsonObject
                val name = unit.text("name")
                if (name == "Settler" && founded < profile.maxCities && options.boolean("canFound")) {
                    run(session, "foundCity", "unitId" to unit.integer("id"))
                    record("foundCity", "x" to unit.integer("x"), "y" to unit.integer("y"))
                    founded++
                    resolvePending()
                    continue
                }
                if (worker(unit, options) || attack(unit, options)) continue
                move(unit, options, explored, home, enemy)
            }
        }

        /** 当前格施工：进行中的工程不移动，完工后由下一回合重新评估。 */
        private fun worker(unit: JsonObject, options: JsonObject): Boolean {
            val worker = options["worker"] as? JsonObject ?: return false
            val id = unit.integer("id")
            if (worker.text("mode") == "waterInstant" && worker.boolean("enabled")) {
                run(session, "workerOrder", "unitId" to id, "type" to "createWaterImprovement", "name" to worker.text("improvement"))
                record("workerOrder", "unitId" to id, "name" to worker.text("improvement"))
                return true
            }
            if (worker.text("mode") != "worker" || !worker.boolean("supported")) return false
            val inProgress = worker["inProgress"]
            if (inProgress != null && inProgress !is JsonNull) return true
            val builds = worker["improvements"]!!.jsonArray.map { it.jsonObject }
            val build = builds.firstOrNull { it.boolean("enabled") && it["providesResource"] is JsonPrimitive }
                ?: builds.firstOrNull { it.boolean("enabled") } ?: return false
            run(session, "workerOrder", "unitId" to id, "type" to "start", "name" to build.text("name"))
            record("workerOrder", "unitId" to id, "name" to build.text("name"))
            return true
        }

        /** 相邻敌军的攻击仍走原生 attack；占城弹窗随即在同一回合内处理。 */
        private fun attack(unit: JsonObject, options: JsonObject): Boolean {
            if (!options.boolean("combatSupported")) return false
            val targets = options["attackTargets"] as? JsonArray ?: return false
            val aim = targets.firstOrNull()?.jsonObject ?: return false
            run(session, "attack", "unitId" to unit.integer("id"), "x" to aim.integer("x"), "y" to aim.integer("y"))
            record("attack", "unitId" to unit.integer("id"), "x" to aim.integer("x"), "y" to aim.integer("y"))
            resolvePending()
            return true
        }

        private fun move(unit: JsonObject, options: JsonObject, explored: Set<String>, home: IntArray?, enemy: IntArray?) {
            val reachable = (options["reachable"] as? JsonArray)?.map { it.jsonObject } ?: return
            if (reachable.isEmpty()) return
            val pick = when {
                // 已方首都驻军不外出，避免长局因无人防守被 AI 直接占领。
                home != null && unit.integer("x") == home[0] && unit.integer("y") == home[1] && warStarted -> null
                enemy != null -> reachable.minByOrNull { distance(it, enemy) }
                warStarted && home != null -> reachable.minByOrNull { distance(it, home) }
                else -> {
                    val unseen = reachable.filter { "${it.integer("x")}:${it.integer("y")}" !in explored }
                    val pool = if (unseen.isNotEmpty()) unseen else reachable
                    if (home == null) pool.minByOrNull { it.integer("y") * 4096 + it.integer("x") }
                    else pool.maxByOrNull { distance(it, home) }
                }
            } ?: return
            run(session, "move", "unitId" to unit.integer("id"), "x" to pick.integer("x"), "y" to pick.integer("y"))
            record("move", "unitId" to unit.integer("id"), "x" to pick.integer("x"), "y" to pick.integer("y"))
        }

        private fun distance(tile: JsonObject, point: IntArray): Int {
            val dx = tile.integer("x") - point[0]
            val dy = tile.integer("y") - point[1]
            return dx * dx + dy * dy
        }

        /** 只购买原生报价已启用的金币项目，用真实城市购买入口消耗金币。 */
        private fun purchase(cities: List<JsonObject>) {
            for (city in cities) {
                if (purchasedThisTurn) return
                val data = run(session, "cityOptions", "cityId" to city.text("id"))["data"]!!.jsonObject
                val picks = data["economy"]!!.jsonObject["purchaseOptions"]!!.jsonArray.map { it.jsonObject }
                    .filter { it.boolean("supported") && it.text("type") == "unit" }
                    .mapNotNull { option ->
                        val quote = option["quotes"]!!.jsonArray.map { it.jsonObject }
                            .firstOrNull { it.text("stat") == "Gold" && it.boolean("enabled") } ?: return@mapNotNull null
                        listOf(option.text("name"), quote.integer("cost").toString())
                    }.sortedBy { it[1].toInt() }
                val pick = picks.firstOrNull() ?: continue
                run(session, "cityPurchase", "cityId" to city.text("id"), "name" to pick[0], "stat" to "Gold", "queueIndex" to -1)
                record("cityPurchase", "cityId" to city.text("id"), "name" to pick[0], "cost" to pick[1].toInt())
                purchasedThisTurn = true
            }
        }

        // ---- 外交：陆地路线必须真实宣战，其余路线只记录自然发生的战争 ----

        private fun pursueWar() {
            if (warStarted) return
            val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
            val majors = data["civilizations"]!!.jsonArray.map { it.jsonObject }.filter { it.text("type") == "major" }
            if (majors.any { it.text("status") == "War" }) {
                warStarted = true
                record("warObserved")
                return
            }
            val target = majors.firstOrNull { civ ->
                (civ["actions"] as? JsonObject)?.get("declareWar")?.jsonObject?.boolean("enabled") == true
            } ?: return
            run(session, "diplomacyDeclareWar", "civId" to target.text("civId"))
            warStarted = true
            record("diplomacyDeclareWar", "civId" to target.text("civId"), "name" to target.text("name"))
        }

        /** 已探索到的敌方城市中心，用于把陆军推向可见战线；没有可见目标时原地留守。 */
        private fun visibleEnemyCity(snapshot: JsonObject): IntArray? {
            if (!warStarted) return null
            return snapshot["cities"]!!.jsonArray.map { it.jsonObject }.firstOrNull { !it.boolean("own") }
                ?.let { intArrayOf(it.integer("x"), it.integer("y")) }
        }

        // ---- 取证 ----

        private fun snapshot(): JsonObject = run(session, "snapshot")["snapshot"]?.jsonObject
            ?: throw AssertionError("对局快照缺失")

        private fun pending(): List<JsonObject> = snapshot()["pending"]!!.jsonArray.map { it.jsonObject }

        private fun record(action: String, vararg fields: Pair<String, Any?>) {
            val entries = mutableListOf<Pair<String, Any?>>("action" to action, "turn" to session.game!!.turns)
            entries.addAll(fields)
            trace += dto(*entries.toTypedArray())
        }

        private fun verify() {
            val game = session.game!!
            val player = game.currentPlayerCiv
            val technologies = player.tech.techsResearched.size
            val policies = player.policies.getAdoptedPolicies().size
            val kinds = trace.map { it.text("action") }.toSet()
            assertNull("${profile.name} 长局不应提前结束", game.victoryData)
            assertFalse("${profile.name} 玩家不应被灭亡", player.isDefeated())
            assertTrue("${profile.name} 至少应有一座城市", player.cities.isNotEmpty())
            assertTrue("${profile.name} 研发科技应至少 ${profile.minTechnologies} 项，实际 $technologies",
                technologies >= profile.minTechnologies)
            if (profile.turns >= 30) assertTrue("${profile.name} 应至少采用一项政策，实际 $policies", policies >= 1)
            val required = mutableListOf("newGame", "nextTurn", "reload", "production", "research", "foundCity", "move")
            if (profile.turns >= 30) required += "policy"
            for (action in required) assertTrue("${profile.name} 轨迹缺少 $action：$kinds", action in kinds)
            if (profile.warTurn != null) assertTrue("${profile.name} 陆地路线应进入战争", warStarted)
        }

        private fun writeEvidence(error: Throwable?) {
            val game = session.game
            val player = game?.currentPlayerCiv
            val evidence = dto(
                "profile" to profile.name, "mapSize" to profile.mapSize, "mapType" to profile.mapType,
                "seed" to profile.seed, "aiPlayers" to profile.aiPlayers, "cityStates" to profile.cityStates,
                "targetTurns" to profile.turns, "turnLimit" to profile.turns * 2,
                "minimumTechnologies" to profile.minTechnologies, "turns" to game?.turns,
                "cities" to player?.cities?.size, "units" to player?.units?.getCivUnits()?.count(),
                "technologies" to player?.tech?.techsResearched?.size, "policies" to player?.policies?.getAdoptedPolicies()?.size,
                "warStarted" to warStarted, "actionCount" to trace.size, "actions" to trace,
                "error" to error?.let { "${it.javaClass.simpleName}: ${it.message}" },
                "elapsedMillis" to (System.nanoTime() - started) / 1_000_000,
                "heapUsedBytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            report.parentFile.mkdirs()
            report.writeText(evidence.toString())
            if (error != null) return
            println("整局验收 ${profile.name}：回合 ${game?.turns}，城市 ${player?.cities?.size}，" +
                "单位 ${player?.units?.getCivUnits()?.count()}，科技 ${player?.tech?.techsResearched?.size}，" +
                "政策 ${player?.policies?.getAdoptedPolicies()?.size}，命令 ${trace.size}，" +
                "耗时 ${evidence.integer("elapsedMillis")} ms，堆用 ${evidence.integer("heapUsedBytes")} B")
        }
    }
}
