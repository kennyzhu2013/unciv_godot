package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Counter
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * 玩家可见宗教边界：状态／费用／信条／符号只读，所有玩法效果委托原生 ReligionManager、类型化 UnitActions
 * 与无逻辑 Java 桥接；网关不复制宗教结算、不自行扣信仰或销毁单位。
 */
internal class ReligionCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private val manager = player.religionManager
    private val civView = GameView(game, player).civView

    private fun problem(code: String, text: String) = GatewayError(code, text)
    private fun ensure(ok: Boolean, code: String, text: String) { if (!ok) throw problem(code, text) }
    private fun checked(block: () -> Unit): GatewayError? = try { block(); null } catch (e: GatewayError) { e }
    private fun ordinary() { GameSession.validateGame(game); CombatCommands(game).ensureNoBlockingBattleDecision() }
    private fun religionEnabled() = game.isReligionEnabled()

    /** 只读提取原生私有持久字段（GDX json 序列化，缺省字段按持久化默认值处理）；不写私有字段。 */
    private fun persistentFields(): Pair<String?, Boolean> {
        val obj = Json.parseToJsonElement(com.unciv.json.json().toJson(manager)).jsonObject
        return obj["foundingCityId"]?.jsonPrimitive?.contentOrNull to
            (obj["shouldChoosePantheonBelief"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    // ---- 待决路由（采用 CivView 原生条件，只暴露一个当前 decision）----
    fun pendingMode(): String? = when {
        civView.canFoundPantheon() -> "pantheon"
        civView.canExpandPantheon() -> "expandPantheon"
        civView.isFoundingReligion() -> "foundReligion"
        civView.isEnhancingReligion() -> "enhanceReligion"
        civView.hasFreeBeliefs() -> "freeBeliefs"
        else -> null
    }

    private fun expandSlots(counter: Counter<BeliefType>): List<BeliefType> {
        val list = ArrayList<BeliefType>()
        for ((type, count) in counter) {
            if (type == BeliefType.None) throw problem("UNSUPPORTED", "信条槽位类别为 None，请保存后用原客户端处理")
            if (count < 0) throw problem("UNSUPPORTED", "信条槽位数量为负，请保存后用原客户端处理")
            repeat(count) { list.add(type) }
            if (list.size > 20) throw problem("UNSUPPORTED", "信条槽位过多，请保存后用原客户端处理")
        }
        return list
    }

    private fun validatedFreeBeliefs(): Counter<BeliefType> {
        val result = Counter<BeliefType>()
        for ((key, count) in manager.freeBeliefs) {
            if (count == 0) continue  // 零值忽略展示但不修改原对象
            val type = BeliefType.entries.firstOrNull { it.name == key }
                ?: throw problem("UNSUPPORTED", "免费信条额度异常，请保存后用原客户端处理")
            if (type == BeliefType.None) throw problem("UNSUPPORTED", "免费信条额度异常，请保存后用原客户端处理")
            if (count < 0) throw problem("UNSUPPORTED", "免费信条额度异常，请保存后用原客户端处理")
            result.add(type, count)
            if (result.sumValues() > 20) throw problem("UNSUPPORTED", "免费信条额度异常，请保存后用原客户端处理")
        }
        return result
    }

    private fun validateFoundingCity() {
        val (foundingCityId, _) = persistentFields()
        ensure(foundingCityId != null, "UNSUPPORTED", "创立城市缺失，请保存后用原客户端处理")
        val city = player.cities.firstOrNull { it.id == foundingCityId }
            ?: throw problem("UNSUPPORTED", "创立城市已失去归属，请保存后用原客户端处理")
        ensure(!city.isHolyCity(), "UNSUPPORTED", "创立城市已是圣城，请保存后用原客户端处理")
    }

    private fun slotsFor(mode: String): List<BeliefType> = when (mode) {
        "pantheon", "expandPantheon" -> listOf(BeliefType.Pantheon)
        "foundReligion" -> { validateFoundingCity(); expandSlots(manager.getBeliefsToChooseAtFounding()) }
        "enhanceReligion" -> expandSlots(manager.getBeliefsToChooseAtEnhancing())
        "freeBeliefs" -> {
            ensure(manager.religion != null, "UNSUPPORTED", "无宗教却有免费信条额度，请保存后用原客户端处理")
            expandSlots(validatedFreeBeliefs())
        }
        else -> throw problem("RELIGION_DECISION", "未知宗教待决模式：$mode")
    }

    // ---- 只读资格（复刻原生 Picker 谓词）----
    private fun isSelectable(belief: Belief) = manager.getReligionWithBelief(belief) == null && belief.isAvailable(player.state)
    private fun candidateBeliefs(type: BeliefType) = game.ruleset.beliefs.values.filter { it.type == type || type == BeliefType.Any }
    private fun candidateIds(type: BeliefType) = candidateBeliefs(type).filter { isSelectable(it) }.map { it.name }

    /** 纯只读二分图匹配（Kuhn）：判断是否存在完整、互不重复的可选组合，只判可完成性，不替用户选。 */
    private fun completable(slots: List<BeliefType>): Boolean {
        if (slots.isEmpty()) return true
        val candidates = slots.map { candidateIds(it).toHashSet() }
        val beliefToSlot = HashMap<String, Int>()
        fun assign(slot: Int, seen: MutableSet<String>): Boolean {
            for (belief in candidates[slot]) {
                if (!seen.add(belief)) continue
                val holder = beliefToSlot[belief]
                if (holder == null || assign(holder, seen)) { beliefToSlot[belief] = slot; return true }
            }
            return false
        }
        for (index in slots.indices) if (!assign(index, HashSet())) return false
        return true
    }

    private fun faithCost(): Int =
        if (manager.religionState == ReligionState.None && !manager.usingFreeBeliefs()) manager.faithForPantheon() else 0

    private fun availableSymbols() = game.ruleset.religions.filter { !game.religions.containsKey(it) }

    private fun warnings(mode: String): List<String> = buildList {
        val quotas = manager.freeBeliefs.entries.filter { it.value != 0 }
        if (quotas.any()) add("采用信条会先清空现有全部免费额度（" + quotas.joinToString("、") { "${it.key}×${it.value}" } + "）；本次信条效果新授予的额度会保留。")
        if (mode == "pantheon" || mode == "expandPantheon") add("一次只采用一个 Pantheon 信条，其余未用额度也会清空。")
        val cost = faithCost()
        if (cost > 0) add("本次选择将消耗 $cost 点信仰。")
    }

    // ---- 票据 ----
    private fun fingerprint(kind: String, contents: JsonObject): String {
        val text = dto("session" to session, "revision" to revision, "gameId" to game.gameId,
            "player" to player.civID, "kind" to kind, "contents" to contents).toString()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun decisionToken(mode: String, slots: List<BeliefType>): String {
        val (foundingCityId, shouldChoosePantheon) = persistentFields()
        return fingerprint("decision", dto(
            "mode" to mode, "state" to manager.religionState.name, "religionId" to manager.religion?.name,
            "foundingCityId" to foundingCityId, "shouldChoosePantheonBelief" to shouldChoosePantheon,
            "storedFaith" to manager.storedFaith, "faithCost" to faithCost(),
            "freeBeliefs" to manager.freeBeliefs.entries.sortedBy { it.key }.map { dto("type" to it.key, "count" to it.value) },
            "slots" to slots.map { it.name }, "candidates" to slots.map { candidateIds(it) },
            "symbols" to availableSymbols()))
    }

    private fun prophetAction(unit: MapUnit, mode: String) = UnitActions.getUnitActions(unit,
        if (mode == "found") UnitActionType.FoundReligion else UnitActionType.EnhanceReligion).firstOrNull()

    private fun actionToken(unit: MapUnit, mode: String): String {
        val action = prophetAction(unit, mode)
        val pos = unit.currentTile.position
        return fingerprint("prophet", dto("unitId" to unit.id, "x" to pos.x, "y" to pos.y, "mode" to mode,
            "label" to action?.title, "executable" to (action?.action != null), "state" to manager.religionState.name))
    }

    private fun actionProblem(unit: MapUnit, mode: String): GatewayError? = checked {
        ensure(religionEnabled(), "UNSUPPORTED", "本局未启用宗教")
        ensure(manager.religionState != ReligionState.FoundingReligion && manager.religionState != ReligionState.EnhancingReligion,
            "RELIGION_DECISION", "已有待决的宗教选择，请先完成后再使用预言家")
        val action = prophetAction(unit, mode) ?: throw problem("RELIGION_ACTION", "此单位没有对应的宗教动作")
        ensure(action.action != null, "RELIGION_ACTION", "此单位当前不能执行该宗教动作")
        if (mode == "found") {
            // 原生开始方法不校验归属，但最终创立只从己方城市查找 foundingCityId；非己方位置在消耗前拒绝。
            val tile = unit.currentTile
            ensure(tile.isCityCenter() && tile.getCity()?.civ == player, "UNSUPPORTED", "只能在本方城市中心创立宗教")
        }
    }

    // ---- decision DTO ----
    private data class Decision(val mode: String, val slots: List<BeliefType>, val supported: Boolean, val reason: String, val enabled: Boolean)

    private fun resolveDecision(): Decision? {
        val mode = pendingMode() ?: return null
        if (!religionEnabled()) return Decision(mode, emptyList(), false, "本局未启用宗教", false)
        return try {
            val slots = slotsFor(mode)
            ensure(completable(slots), "UNSUPPORTED", "当前无法形成完整的信条选择，请保存后用原客户端处理")
            Decision(mode, slots, true, "", checked { ordinary() } == null)
        } catch (e: GatewayError) {
            Decision(mode, emptyList(), false, e.message, false)
        }
    }

    private fun decisionDto(decision: Decision): JsonObject = dto(
        "mode" to decision.mode, "supported" to decision.supported, "enabled" to decision.enabled, "reason" to decision.reason,
        "token" to (if (decision.supported) decisionToken(decision.mode, decision.slots) else null),
        "slots" to decision.slots.mapIndexed { index, type -> dto("index" to index, "type" to type.name, "candidateIds" to candidateIds(type)) },
        "useFreeBeliefs" to manager.usingFreeBeliefs(), "faithCost" to faithCost(), "warnings" to warnings(decision.mode))

    private fun beliefEffects(belief: Belief) = belief.uniqueObjects.filter { !it.isHiddenToUsers() }.map { it.getDisplayText() }
    private fun beliefDto(belief: Belief) = dto("id" to belief.name, "type" to belief.type.name, "effects" to beliefEffects(belief))
    private fun beliefInfo(belief: Belief): JsonObject {
        val owned = manager.getReligionWithBelief(belief) != null
        val available = belief.isAvailable(player.state)
        return dto("id" to belief.name, "type" to belief.type.name, "effects" to beliefEffects(belief),
            "enabled" to (!owned && available),
            "reason" to when { owned -> "此信条已被采用"; !available -> "此信条当前不可用"; else -> "" })
    }

    private fun freeBeliefsDto(): List<JsonObject> = try {
        validatedFreeBeliefs().entries.map { dto("type" to it.key.name, "count" to it.value) }
    } catch (e: GatewayError) {
        listOf(dto("error" to "免费信条额度异常，请保存后用原客户端处理"))
    }

    /** 只读完整宗教详情，不增加 revision。 */
    fun options(): JsonObject {
        GameSession.validateGame(game)
        val decision = resolveDecision()
        val religion = manager.religion
        val slotTypes = decision?.slots?.toSet() ?: emptySet()
        val beliefIds = game.ruleset.beliefs.values
            .filter { belief -> slotTypes.any { belief.type == it || it == BeliefType.Any } }
            .map { it.name }
        return dto(
            "enabled" to religionEnabled(), "state" to manager.religionState.name, "storedFaith" to manager.storedFaith,
            "faithForPantheon" to manager.faithForPantheon(), "faithForNextGreatProphet" to manager.faithForNextGreatProphet(),
            "canGenerateProphet" to manager.canGenerateProphet(),
            "generateProphetNote" to "达到费用不保证下回合立即生成，原生按概率生成预言家。",
            "currentReligion" to religion?.let {
                dto("id" to it.name, "displayName" to it.getReligionDisplayName(), "iconName" to it.getIconName(),
                    "beliefs" to it.getAllBeliefsOrdered().map { belief -> beliefDto(belief) }.toList())
            },
            "cities" to player.cities.map { city ->
                dto("cityId" to city.id, "name" to city.name,
                    "followers" to (religion?.let { city.religion.getFollowersOf(it.name) } ?: 0),
                    "holyCity" to (religion?.let { city.isHolyCityOf(it.name) } ?: false))
            },
            "symbols" to game.ruleset.religions.map { id ->
                val taken = game.religions.containsKey(id)
                dto("id" to id, "defaultName" to id, "available" to !taken, "reason" to if (taken) "此符号已被使用" else "")
            },
            "beliefs" to beliefIds.map { beliefInfo(game.ruleset.beliefs[it]!!) },
            "freeBeliefs" to freeBeliefsDto(),
            "decision" to decision?.let { decisionDto(it) },
            "prophets" to player.units.getCivUnits()
                .filter { it.hasUnique(UniqueType.MayFoundReligion) || it.hasUnique(UniqueType.MayEnhanceReligion) }
                .map { unit ->
                    val pos = unit.currentTile.position
                    dto("unitId" to unit.id, "x" to pos.x, "y" to pos.y,
                        "actions" to listOf("found", "enhance").map { mode ->
                            val problem = actionProblem(unit, mode)
                            dto("mode" to mode, "label" to (prophetAction(unit, mode)?.title ?: defaultLabel(mode)),
                                "enabled" to (problem == null), "reason" to (problem?.message ?: ""),
                                "actionToken" to actionToken(unit, mode))
                        })
                }.toList())
    }

    private fun defaultLabel(mode: String) = if (mode == "found") "创立宗教" else "强化宗教"

    /** 快照轻量摘要：不含票据或完整候选。 */
    fun snapshotSummary(): JsonObject {
        val religion = manager.religion
        return dto("enabled" to religionEnabled(), "state" to manager.religionState.name, "storedFaith" to manager.storedFaith,
            "religionId" to religion?.name, "religionDisplayName" to religion?.getReligionDisplayName(), "pendingMode" to pendingMode())
    }

    private fun modeLabel(mode: String) = when (mode) {
        "pantheon" -> "选择万神殿"
        "expandPantheon" -> "扩展万神殿"
        "foundReligion" -> "创立宗教"
        "enhanceReligion" -> "强化宗教"
        "freeBeliefs" -> "选择免费信条"
        else -> "宗教选择"
    }

    /** 待决节点：kind=religion，target=pendingMode，真实模式／可支持性。 */
    fun pending(): JsonObject? {
        val decision = resolveDecision() ?: return null
        val message = if (decision.supported) "请到宗教页完成${modeLabel(decision.mode)}"
        else "${modeLabel(decision.mode)}：${decision.reason}"
        return dto("kind" to "religion", "target" to decision.mode, "message" to message, "supported" to decision.supported)
    }

    /** 单位宗教能力摘要（不含票据；票据经 religionOptions 取得）。 */
    fun unitDto(unit: MapUnit): JsonObject? {
        if (!unit.hasUnique(UniqueType.MayFoundReligion) && !unit.hasUnique(UniqueType.MayEnhanceReligion)) return null
        fun item(mode: String): JsonObject {
            val problem = actionProblem(unit, mode)
            return dto("enabled" to (problem == null), "reason" to (problem?.message ?: ""))
        }
        return dto("found" to item("found"), "enhance" to item("enhance"))
    }

    // ---- 写命令 ----
    fun prepare(request: JsonObject): () -> Unit {
        GameSession.validateGame(game)
        val action = request.text("action")
        val keys = when (action) {
            "religionUseProphet" -> setOf("unitId", "mode", "actionToken")
            "religionChooseBeliefs" -> setOf("decisionToken", "beliefs")
            "religionFound" -> setOf("decisionToken", "religionId", "displayName", "beliefs")
            else -> throw problem("UNSUPPORTED", "未接入的宗教命令")
        }
        ensure((request.keys - envelope - keys).isEmpty(), "INVALID_ARGUMENT", "含未支持的宗教参数")
        ordinary()
        ensure(religionEnabled(), "UNSUPPORTED", "本局未启用宗教")
        return when (action) {
            "religionUseProphet" -> prepareUseProphet(request)
            "religionChooseBeliefs" -> prepareChooseBeliefs(request)
            else -> prepareFound(request)
        }
    }

    private fun prepareUseProphet(request: JsonObject): () -> Unit {
        val unitId = request.requiredInt("unitId")
        val mode = request.requiredText("mode")
        ensure(mode == "found" || mode == "enhance", "INVALID_ARGUMENT", "mode 只能为 found 或 enhance")
        val token = request.requiredText("actionToken")
        val unit = player.units.getCivUnits().firstOrNull { it.id == unitId }
            ?: throw problem("NOT_OWNED", "找不到己方单位")
        actionProblem(unit, mode)?.let { throw it }
        ensure(token == actionToken(unit, mode), "RELIGION_DECISION", "单位状态已变化，请刷新后重试")
        val act = prophetAction(unit, mode)!!.action!!
        return { act() }
    }

    private fun prepareChooseBeliefs(request: JsonObject): () -> Unit {
        val token = request.requiredText("decisionToken")
        val beliefNames = request.requiredStringArray("beliefs")
        val decision = resolveDecision() ?: throw problem("RELIGION_DECISION", "当前没有宗教待决")
        ensure(decision.mode != "foundReligion", "RELIGION_DECISION", "最终创立请使用 religionFound 命令")
        ensure(decision.supported, "UNSUPPORTED", decision.reason)
        ensure(token == decisionToken(decision.mode, decision.slots), "RELIGION_DECISION", "宗教待决已变化，请刷新后重试")
        validateBeliefs(beliefNames, decision.slots)
        return { manager.chooseBeliefs(beliefNames.map { game.ruleset.beliefs[it]!! }, manager.usingFreeBeliefs()) }
    }

    private fun prepareFound(request: JsonObject): () -> Unit {
        val token = request.requiredText("decisionToken")
        val religionId = request.requiredText("religionId")
        val displayName = request.requiredString("displayName")
        val beliefNames = request.requiredStringArray("beliefs")
        val decision = resolveDecision() ?: throw problem("RELIGION_DECISION", "当前没有宗教待决")
        ensure(decision.mode == "foundReligion", "RELIGION_DECISION", "当前待决不是最终创立")
        ensure(decision.supported, "UNSUPPORTED", decision.reason)
        ensure(token == decisionToken(decision.mode, decision.slots), "RELIGION_DECISION", "宗教待决已变化，请刷新后重试")
        validateName(religionId, displayName)
        // 槽位在最终创立前读取（getBeliefsToChooseAtFounding），原生 foundReligion 会清除 shouldChoosePantheonBelief。
        validateBeliefs(beliefNames, decision.slots)
        return {
            NativeReligionBridge.foundReligion(manager, displayName, religionId)
            manager.chooseBeliefs(beliefNames.map { game.ruleset.beliefs[it]!! }, manager.usingFreeBeliefs())
        }
    }

    private fun validateName(religionId: String, displayName: String) {
        ensure(game.ruleset.religions.contains(religionId), "RELIGION_NAME", "宗教符号必须来自当前规则")
        ensure(!game.religions.containsKey(religionId), "RELIGION_NAME", "此宗教符号已被使用")
        if (displayName == religionId) return  // 保留默认名：允许使用所选原版名称
        ensure(displayName.isNotEmpty(), "RELIGION_NAME", "自定义名称不能为空")
        ensure(displayName != Constants.noReligionName, "RELIGION_NAME", "此名称为保留名")
        ensure(!game.ruleset.religions.contains(displayName), "RELIGION_NAME", "自定义名称不能与规则宗教符号相同")
        ensure(game.religions.none { it.value.name == displayName }, "RELIGION_NAME", "自定义名称不能与现存宗教相同")
        ensure(displayName.length <= 32, "RELIGION_NAME", "自定义名称最多 32 个字符")
        ensure(displayName.none { it in "[]{}\"\\<>\n\r" }, "RELIGION_NAME", "自定义名称含非法字符")
    }

    private fun validateBeliefs(beliefNames: List<String>, slots: List<BeliefType>) {
        ensure(beliefNames.size == slots.size, "BELIEF_CHOICE", "信条数量与槽位不符")
        val seen = HashSet<String>()
        for ((index, name) in beliefNames.withIndex()) {
            val belief = game.ruleset.beliefs[name] ?: throw problem("BELIEF_CHOICE", "未知信条：$name")
            val slot = slots[index]
            ensure(belief.type == slot || slot == BeliefType.Any, "BELIEF_CHOICE", "信条类别与槽位不符：$name")
            ensure(seen.add(name), "BELIEF_CHOICE", "信条重复：$name")
            ensure(isSelectable(belief), "BELIEF_CHOICE", "信条不可用或已被占用：$name")
        }
    }

    companion object {
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
    }
}

private fun JsonObject.requiredText(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotEmpty() }?.content
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少字符串参数或类型错误：$key")
private fun JsonObject.requiredString(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少字符串参数或类型错误：$key")
private fun JsonObject.requiredInt(key: String): Int = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString }?.intOrNull
    ?: throw GatewayError("INVALID_ARGUMENT", "整数不接受字符串／小数／越界值：$key")
private fun JsonObject.requiredStringArray(key: String): List<String> {
    val array = this[key] as? JsonArray ?: throw GatewayError("INVALID_ARGUMENT", "缺少数组参数：$key")
    return array.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw GatewayError("INVALID_ARGUMENT", "数组元素必须为字符串：$key")
    }
}
