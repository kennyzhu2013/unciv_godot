package com.unciv.godot

import com.unciv.godot.BattleFixtures.run
import com.unciv.logic.map.HexCoord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 第 8B 批：从真实 newGame 一路打到原生胜负结算的完整长局（陆地征服路线）。
 *
 * 与 [WholeGameTest] 的“固定回合数中局”不同，这里不以回合数收口：驱动持续真实命令与
 * AI 原生回合，直到原生 checkForVictory 产生 victoryData（目标 Domination——占领全部
 * 原始首都），随后经 victoryContinue 进入 oneMoreTurnMode，再保存、跨会话重载并续玩，
 * 验证胜利结算后的持久化与继续游戏语义。
 *
 * 差分口径分两类（AI 回合为何不逐字节比对的根据见 [advanceTurn] 注释）：
 * - 玩家命令（确定的原生调用）：同时施加到 [NativeReplay]——一个从持久化存档文本独立加载、
 *   只调用 core 原生入口的重放端；命令后立即做完整归一化差分（[GameplayAssertions]）。
 * - AI 回合（nextTurn）：网关仅委托原生 GameInfo.nextTurn(clone+setTransients)、无自研 AI 逻辑，
 *   改为验证网关回合后状态经 save→独立原生 load 往返稳定（网关重载 == 独立原生重载），并以该
 *   往返态同步 session/twin，保证后续玩家命令仍逐条独立原生差分。
 * 轨迹记录每条命令的完整参数、回合、差分结果（matched）与验证方式，并记录每个 nextTurn 后的
 * 自动存档名；重放器遇到未映射的玩家命令立即失败，按第 8 批口径属于对应批次未完成。
 */
class FullGameTest {
    companion object {
        private val root = File(System.getProperty("unciv.root"))
        // 回合上限：Standard 速度原生 500 回合触发 Score/Time 结算，这里取 450 作为守卫——留足长局余量
        // （实测 74~337 回合达成 Domination），又在 Score 结算前失败，确保只接受真实 Domination 结算。
        private const val TURN_LIMIT = 450
        private const val SAVE_EVERY = 40
        private const val WAR_TURN = 12
        // 征服路线只造近战（可占城）；远程/攻城只能削血不能占领，故不入优先表。
        private val militaryPriority = listOf("Infantry", "Rifleman", "Cavalry", "Musketman", "Knight",
            "Longswordsman", "Pikeman", "Swordsman", "Horseman", "Spearman", "Warrior")
        private val peacePriority = listOf("Settler", "Worker", "Monument", "Library", "Granary", "Courthouse")
        // 科技先走近战解锁链（Mining→Bronze→Iron；Animal Husbandry→Horseback），再补经济与后期军事。
        private val techPriority = listOf("Mining", "Bronze Working", "Iron Working", "Animal Husbandry",
            "Horseback Riding", "Pottery", "Writing", "Mathematics", "Construction", "Philosophy",
            "Civil Service", "Steel", "Chivalry", "Gunpowder", "Military Science", "Replaceable Parts",
            "The Wheel", "Trapping", "Calendar", "Engineering", "Machinery", "Archery")

        @BeforeClass @JvmStatic fun setup() { KernelRuntime.initialize(root) }
    }

    @Test fun landDominationToNativeVictory() = Conquest().play()

    private inner class Conquest {
        private val name = "full-land-domination"
        private val directory = File(root, "godot/.local/tests/full-game/$name")
        private val report = File(root, "godot/.local/tests/full-game/$name.json")
        private val trace = ArrayList<JsonObject>()
        private val savePoints = ArrayList<JsonObject>()
        private val started = System.nanoTime()
        private var session = GameSession(root, directory)
        private var twin: NativeReplay? = null
        private var warStarted = false
        private var purchasedThisTurn = false
        private var victoryOutcome: JsonObject? = null
        private val knownEnemyCities = LinkedHashSet<HexCoord>()

        fun play() {
            try {
                drive()
                verify()
            } catch (error: Throwable) {
                // 失败也落盘轨迹：完整长局的失败现场必须能按命令参数复盘。
                writeEvidence(error)
                throw error
            }
            writeEvidence(null)
        }

        // ---- 命令包装：网关执行 + 原生重放 + 立即完整差分 ----

        private fun command(action: String, vararg params: Pair<String, Any?>, mirror: JsonObject? = null): JsonObject {
            val args = dto(*params)
            val result = run(session, action, *params)
            val replay = twin ?: return result
            replay.apply(action, mirror ?: args)
            GameplayAssertions.assertGameplayEquals("第 ${session.game!!.turns} 回合 $action $args", replay.game, session.game!!)
            record(action, args, true)
            return result
        }

        private fun drive() {
            val response = run(session, "newGame", "setup" to dto(
                "nation" to "Rome", "difficulty" to "Chieftain", "speed" to "Standard",
                "mapSize" to "Tiny", "mapType" to "Pangaea",
                "aiPlayers" to 1, "cityStates" to 0,
                "barbarians" to false, "ruins" to false, "seed" to "860505"))
            val gameId = session.game!!.gameId
            // 重放端从新局自动存档独立加载；轨迹记录 gameId 与初始存档名，保证可重放。
            val initialSave = File(response.text("autosavedPath"))
            twin = NativeReplay.fromSaveText(initialSave.readText())
            record("newGame", dto("mapSize" to "Tiny", "mapType" to "Pangaea", "seed" to "860505",
                "gameId" to gameId, "initialSave" to initialSave.name), true)
            assertEquals("新局从第 0 回合开始", 0, session.game!!.turns)
            var lastSaveTurn = 0
            while (session.game!!.victoryData == null) {
                val turn = session.game!!.turns
                assertTrue("$name 到第 $turn 回合仍未产生原生胜负结算", turn < TURN_LIMIT)
                if (turn > 0 && turn - lastSaveTurn >= SAVE_EVERY) {
                    persist(turn)
                    lastSaveTurn = turn
                }
                resolvePending()
                act(turn)
                resolvePending()
                advanceTurn()
            }
            // 原生结算出现后：继续游戏、保存、跨会话重载，再续玩验证 oneMoreTurnMode 持久化。
            resolvePending()
            assertNotNull("胜利后应记录胜负结算", victoryOutcome)
            persist(session.game!!.turns)
            val path = run(session, "save", "name" to "$name-victory")["savedPath"]!!.jsonPrimitive.content
            // 胜利结算后同样以“网关重载 vs 独立原生重载”比较，规避加载时权威重算的瞬态字段。
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
            // AI 回合不重跑第二遍做逐字节比对：上游 Unciv 的 Tile 用身份哈希，fog-bust/wander/heal
            // 等自动化对 Tile 集合做 .random(rng)/maxByOrNull 的顺序相关平局裁决，即便 stateBasedRandom
            // 由稳定 ID 播种，独立加载的两份存档也会偶发选到不同等值地块并级联（伟人/平民单位落点、
            // AI 金币±1、城市连接）。网关 nextTurn 仅委托原生 GameInfo.nextTurn(clone+setTransients)、
            // 无自研 AI 逻辑，故改为验证回合后状态经 save→独立原生 load 往返稳定，并以往返态同步两端，
            // 使后续玩家命令仍逐条独立原生差分（玩家命令是确定的原生调用）。
            val autosaved = File(result.text("autosavedPath"))
            resyncFromSave(autosaved, "第 $before 回合 nextTurn 后存档往返（网关重载 vs 独立原生重载）")
            record("nextTurn", dto("autosavedPath" to autosaved.name, "verified" to "save-reload-roundtrip"), true)
        }

        /** 周期持久化：以“网关重载 vs 独立原生重载”比较（两者均经 setTransients），
         *  规避 connectedToCapitalStatus 等加载时被原生权威重算、而内存态在回合中占城后可能滞后的字段；
         *  同时验证网关 load 与独立原生 load 完全一致。随后以重载态继续驱动。 */
        private fun persist(turn: Int) {
            val saved = run(session, "save", "name" to "$name-t$turn")["savedPath"]!!.jsonPrimitive.content
            val file = File(saved)
            resyncFromSave(file, "$name 第 $turn 回合跨会话重载（网关 vs 独立原生）")
            val entry = dto("turn" to turn, "save" to file.name, "gameId" to session.game!!.gameId)
            savePoints += entry
            trace += JsonObject(dto("action" to "saveAndReload", "turn" to turn, "matched" to true).toMutableMap() + entry.toMap())
        }

        /** 从同一份持久化存档分别建立网关重载态与独立原生重载态并完整差分，随后以两者继续驱动。
         *  两者均经原生反序列化与 setTransients，规避 connectedToCapitalStatus 等加载时被原生权威重算、
         *  而内存态在 AI 回合中占城后可能滞后的瞬态字段；同时验证网关 load 与独立原生 load 完全一致。 */
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
                    "alert" -> {
                        command("acknowledge")
                    }
                    "trade" -> {
                        command("declineTrade")
                    }
                    "greatPerson" -> greatPerson()
                    "religion" -> religion()
                    "diplomacyAlert" -> diplomacyAlert()
                    "cityCapture" -> {
                        // 征服路线一律吞并：傀儡不产幸福且需要后续单独吞并，直接 annex 与原生占城弹窗等价。
                        command("cityDecision", "cityId" to head.text("target"), "choice" to "annex")
                    }
                    "assetDecision" -> assetDecision()
                    "event" -> event()
                    "victory" -> victory(head)
                    "vote", "voteResult" -> throw AssertionError("征服长局不应出现外交投票：$head")
                    else -> throw AssertionError("未接入的长局待决：$head")
                }
            }
            throw AssertionError("待决没有收敛：${pending()}")
        }

        private fun production(head: JsonObject) {
            val cityId = head.text("target")
            val options = run(session, "cityOptions", "cityId" to cityId)["data"]!!.jsonObject
            val available = options["constructions"]!!.jsonArray.map { it.jsonObject }
                .filter { it.boolean("enabled") }
            // 先扩张再征服：仅当“现有城市 + 在途开拓者 < 3”且未开战时优先造 Settler 铺城（更多产能与战略
            // 资源，才造得出 Swordsman/Knight 等需铁/马的近战）。计入在途 Settler 是关键——无有效建城点时
            // （Tiny 图 AI 也铺满）若不计数会无限堆 Settler、迟迟不转军事而空转到回合上限（run 3 350 回合未开战）。
            // 铺满或开战后全力造近战（可占城）。
            val currentCiv = session.game!!.currentPlayerCiv
            val ownCities = currentCiv.cities.size
            val settlersInField = currentCiv.units.getCivUnits().count { it.name == "Settler" }
            val workersInField = currentCiv.units.getCivUnits().count { it.name == "Worker" }
            // 阶段优先级：铺城(城市+在途Settler<3) → 和平期补足 Worker(≤3)去连接铁/马战略资源 →
            // 全力造近战备战/征战。Worker 是解锁 Swordsman/Knight/Longswordsman 的前提，故和平期优先补够。
            val priorities = when {
                !warStarted && ownCities + settlersInField < 3 -> listOf("Settler", "Worker") + militaryPriority
                !warStarted && workersInField < 3 -> listOf("Worker") + militaryPriority
                else -> militaryPriority + peacePriority.subList(2, peacePriority.size)
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
            // 伟人固定落在首都（GreatPersonManager.chooseFreeGreatPerson→addUnit(getCapital())）：海军伟人
            // （如字母序首个的 Great Admiral）在内陆首都无法落位，返回 outcome=notPlaced 且额度保留，但命令已
            // 推进 revision 使旧票据失效。故每轮重新查询取新票据，按候选名依次尝试直到某位真正 granted；全部无法
            // 落位（首都不能容纳任何候选）时按“无恢复路径”明确失败，不静默跳过（runs 4/5 的 600 次死循环根因）。
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

        // ---- 玩家命令：征服驱动 ----

        private fun act(turn: Int) {
            purchasedThisTurn = false
            val snapshot = snapshot()
            // 累积发现过的敌城，但剔除当前已归属玩家的（占领后自动转向下一目标）。
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
            // 己方领土内、已揭示资源但尚未改良的地块：派闲置 Worker 去连接铁/马等战略资源，
            // 解锁 Swordsman/Knight/Longswordsman 等强近战（否则只能造无资源需求的 Pikeman/Spearman，攻坚城乏力）。
            val resourceTiles = snapshot["tiles"]!!.jsonArray.map { it.jsonObject }.filter {
                it.text("owner") == civId && it.text("resource").isNotEmpty() && it.text("improvement").isEmpty()
            }.map { HexCoord(it.integer("x"), it.integer("y")) }
            tryDeclareWar(turn, snapshot)
            purchase(cities)
            // 全军集中单一主攻目标：离己方首都最近的已知敌城。所有近战朝它推进并优先攻击其本体，
            // 避免兵力分散到多座敌城被逐个击破——真实占城需要同一回合把压倒性兵力砸在同一座城上，
            // 使城防 HP 在其自愈前被砸穿，而不是零星送死（上一版每单位各打最近敌城，久攻不下）。
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
                if (unit.text("name") == "Settler" && founded < 3) {
                    if (options.boolean("canFound")) {
                        command("foundCity", "unitId" to id, "confirmPromise" to true,
                            mirror = dto("unitId" to id))
                        founded++
                        resolvePending()
                    } else {
                        // 原地距现有城市太近不能建城：朝远离己方城市的可达地块逐回合推进，直到可建城。
                        move(id, options, explored, home, null)
                    }
                    continue
                }
                if (unit.text("name") == "Worker") {
                    if (worker(id, options)) continue
                    // 当前格无可施工：走到最近的“己方已揭示资源但未改良”地块去连接战略资源（铁/马→强近战）。
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
                    && soldiersAt(snapshot, home) == 1) continue // 首都留最后一支守军
                val target = primaryTarget ?: if (warStarted) knownEnemyCities.minByOrNull { distance(unit, it) } else null
                move(id, options, explored, home, target)
            }
        }

        private fun soldiersAt(snapshot: JsonObject, home: IntArray): Int =
            snapshot["units"]!!.jsonArray.map { it.jsonObject }.count {
                it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "land" &&
                    it.integer("x") == home[0] && it.integer("y") == home[1]
            }

        private fun tryDeclareWar(turn: Int, snapshot: JsonObject) {
            if (warStarted || turn < WAR_TURN) return
            val soldiers = snapshot["units"]!!.jsonArray.map { it.jsonObject }
                .count { it.boolean("own") && !it.boolean("civilian") && it.text("domain") == "land" }
            // 先铺城扩张再开战：至少 2 城（更多产能与铁/马战略资源，才造得出 Swordsman/Knight 等强近战）
            // 且集结 ≥10 支近战再宣战——弱军零星送死会被 AI 反推灭亡（runs 1/3/4 均以 6 支弱兵开战、
            // 攻不下城反被征服）。真实占城需要同一回合把压倒性兵力砸在一座城上。
            if (session.game!!.currentPlayerCiv.cities.size < 2 || soldiers < 10) return
            val data = run(session, "diplomacyOptions")["data"]!!.jsonObject
            val majors = data["civilizations"]!!.jsonArray.map { it.jsonObject }.filter { it.text("type") == "major" }
            if (majors.any { it.text("status") == "War" }) {
                warStarted = true
                return
            }
            val target = majors.firstOrNull { civ ->
                (civ["actions"] as? JsonObject)?.get("declareWar")?.jsonObject?.boolean("enabled") == true
            } ?: return
            command("diplomacyDeclareWar", "civId" to target.text("civId"))
            warStarted = true
        }

        /** 当前格施工：进行中的工程不移动，完工后由下一回合重新评估。 */
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
            // 集中火力：优先主攻敌城本体（同一目标反复砸穿城防→真实占城），其次任意已知敌城，
            // 最后任意可达敌人（清障/自保）。全军优先砸同一座城，避免火力分散。
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

        /** 每回合最多一次金币购买：优先最便宜的军事单位，其次任意可购单位。 */
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

        private fun snapshot(): JsonObject = run(session, "snapshot")["snapshot"]?.jsonObject
            ?: throw AssertionError("对局快照缺失")

        private fun pending(): List<JsonObject> = snapshot()["pending"]!!.jsonArray.map { it.jsonObject }

        private fun record(action: String, args: JsonObject, matched: Boolean) {
            trace += JsonObject(dto("action" to action, "turn" to session.game!!.turns, "matched" to matched)
                .toMutableMap() + args.toMap())
        }

        private fun verify() {
            val game = session.game!!
            val victory = game.victoryData!!
            val player = game.currentPlayerCiv
            // 真实原生 Domination 结算（占领全部原始首都），不注入胜利记录。
            assertEquals("征服长局应达成原生 Domination 结算", "Domination", victory.victoryType)
            val winner = game.civilizations.firstOrNull { it.civID == victory.winningCiv }
            assertNotNull("获胜者应为真实文明", winner)
            assertTrue("获胜者应为主要文明", winner!!.isMajorCiv())
            assertFalse("获胜者不应被灭亡", winner.isDefeated())
            val kinds = trace.map { it.text("action") }.toSet()
            // 真实征服行为证据：玩家必须真实占领过城市（cityDecision），不能仅以宣战代替（方案 8B 门槛）。
            assertTrue("陆地征服长局必须含真实占城（cityDecision），当前只有 $kinds", "cityDecision" in kinds)
            if (winner.civID == player.civID) {
                assertFalse("玩家获胜时不应被灭亡", player.isDefeated())
                // Domination(CaptureAllCapitals) 精确含义（见 core Victory.kt:324）：对每个其他主要文明，
                // 其原始首都若仍存在则必被获胜方持有，若已不存在则该文明必被消灭；失败方仍可保留非首都城市，
                // 故不能断言“其余文明全部灭亡”（上一版据此把玩家真实 Domination 获胜误判为失败）。
                val capitals = game.getCities().filter { it.isOriginalCapital }.associateBy { it.foundingCivObject }
                assertTrue("玩家达成 Domination 时应持有所有仍存在的其他文明原始首都",
                    game.civilizations.filter { it.isMajorCiv() && it != player }.all { other ->
                        val capital = capitals[other]
                        if (capital != null) capital.civ == player else other.isDefeated()
                    })
            } else {
                // 玩家失败/灭亡：Domination 下玩家原始首都必被占领（或玩家已无城市）。
                assertTrue("玩家失败时应已灭亡或失去原始首都",
                    player.isDefeated() || player.cities.none { it.isOriginalCapital })
            }
            for (action in listOf("newGame", "nextTurn", "diplomacyDeclareWar", "attack", "cityDecision",
                "production", "research", "foundCity", "move", "victoryContinue", "victoryRecorded", "saveAndReload"))
                assertTrue("$name 轨迹缺少 $action：$kinds", action in kinds)
            // 玩家命令逐条独立原生重放差分；nextTurn/saveAndReload 为 save→独立原生 load 往返差分
            // （AI 回合不可跨独立对象图逐字节复现，见 advanceTurn 注释）。两类都要求 matched=true。
            assertTrue("每条命令都应通过其对应的独立原生差分（玩家命令重放／回合存档往返）",
                trace.all { it["matched"]?.jsonPrimitive?.boolean == true })
            // 诚实取证：每个 AI 回合都必须真的走了存档往返验证，而不是被静默跳过或退回逐字节比对。
            val turnSteps = trace.filter { it.text("action") == "nextTurn" }
            assertTrue("长局必须真实推进 AI 回合", turnSteps.isNotEmpty())
            assertTrue("每个 nextTurn 都应记录存档往返验证方式",
                turnSteps.all { it.text("verified") == "save-reload-roundtrip" })
        }

        private fun writeEvidence(error: Throwable?) {
            val game = session.game
            val victory = game?.victoryData
            val player = game?.currentPlayerCiv
            val evidence = dto(
                "profile" to name, "mapSize" to "Tiny", "mapType" to "Pangaea", "seed" to "860505",
                "aiPlayers" to 1, "cityStates" to 0, "turnLimit" to TURN_LIMIT,
                "gameId" to game?.gameId, "turns" to game?.turns,
                "victoryType" to victory?.victoryType,
                "victoryWinner" to victory?.let { record -> game.civilizations.firstOrNull { it.civID == record.winningCiv }?.civName },
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
}
