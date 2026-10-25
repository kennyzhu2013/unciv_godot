package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.removeConditionals
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * 宗教与伟人主动能力（T3.3）。
 * - `unitAbilityOptions` 只读：服务端生成候选 ID、能力类型、当前位置／目标、原生说明、
 *   使用次数与消耗提示、可执行性及原因；目标由当前位置与原生动作确定，不接受任意远程城市施法。
 * - `unitAbility` 写命令：重新生成并核对候选与票据后，只执行一次原生闭包；
 *   不自行扣行动力／次数／资源，不自行销毁单位，不复制科研、宗教压力或外交结算公式。
 *
 * 候选全部取自原生 [UnitActions] 的类型化入口；黄金时代仅接受关联 Unique 为
 * [UniqueType.OneTimeEnterGoldenAgeTurns] 的 TriggerUnique 候选，不开放通用 TriggerUnique 执行入口。
 * 本期清单外能力（海军统帅特殊能力、任意 Mod 触发动作、太空船组装）不登记。
 */
internal class UnitAbilityCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv

    /** 本期开放的能力类型 → 原生 UnitActionType；顺序即候选展示顺序。 */
    private val abilityTypes = linkedMapOf(
        "spreadReligion" to UnitActionType.SpreadReligion,
        "removeHeresy" to UnitActionType.RemoveHeresy,
        "hurryResearch" to UnitActionType.HurryResearch,
        "hurryBuilding" to UnitActionType.HurryBuilding,
        "tradeMission" to UnitActionType.ConductTradeMission,
        "createImprovement" to UnitActionType.CreateImprovement,
        "goldenAge" to UnitActionType.TriggerUnique,
    )

    /** 无关联 Unique 的类型化伟人动作：原生闭包直接消耗单位，次数即一次。 */
    private val consumingTyped = setOf("hurryResearch", "hurryBuilding", "tradeMission")

    /** 原生 SpreadReligion／RemoveHeresy／伟人类型化动作不携带 associatedUnique，按类型回查其动作 Unique 以读取次数与消耗。 */
    private val abilityUniques = mapOf(
        "spreadReligion" to UniqueType.CanSpreadReligion,
        "removeHeresy" to UniqueType.CanRemoveHeresy,
        "hurryResearch" to UniqueType.CanHurryResearch,
        "hurryBuilding" to UniqueType.CanSpeedupConstruction,
        "tradeMission" to UniqueType.CanTradeWithCityStateForGoldAndInfluence,
    )

    private data class Candidate(val wireType: String, val id: String, val action: UnitAction)

    private fun ownedRaw(id: Int): MapUnit = player.units.getCivUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")

    /** 只保留本期清单内的原生候选：瞬建排除工作船水上改良，黄金时代排除其它 TriggerUnique。 */
    private fun keep(wireType: String, action: UnitAction): Boolean = when (wireType) {
        "createImprovement" -> action.associatedUnique?.type == UniqueType.ConstructImprovementInstantly
        "goldenAge" -> action.associatedUnique?.type == UniqueType.OneTimeEnterGoldenAgeTurns
        else -> true
    }

    private fun candidateId(wireType: String, action: UnitAction): String {
        val material = wireType + "|" + action.title + "|" + (action.associatedUnique?.text ?: "")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 重新生成候选：与写入侧同源，保证票据核对时两侧列表一致。 */
    private fun candidates(raw: MapUnit): List<Candidate> = abilityTypes.flatMap { (wireType, nativeType) ->
        UnitActions.getUnitActions(raw, nativeType)
            .filter { keep(wireType, it) }
            .map { Candidate(wireType, candidateId(wireType, it), it) }
    }

    // —— 只读消耗／次数提示：仅读取原生状态与 Unique 修饰符，不做任何结算 ——
    private fun maxUsages(raw: MapUnit, unique: Unique): Int? {
        val extra = raw.getMatchingUniques(unique.type!!)
            .filter { it.text.removeConditionals() == unique.text.removeConditionals() }
            .flatMap { it.getModifiers(UniqueType.UnitActionExtraLimitedTimes) }
            .sumOf { it.params[0].toInt() }
        val times = unique.getModifiers(UniqueType.UnitActionLimitedTimes).maxOfOrNull { it.params[0].toInt() }
        if (times != null) return times + extra
        if (unique.hasModifier(UniqueType.UnitActionOnce)) return 1 + extra
        return null
    }

    private fun usagesLeft(raw: MapUnit, unique: Unique): Int? {
        val max = maxUsages(raw, unique) ?: return null
        return max - (raw.abilityToTimesUsed[unique.text.removeConditionals()] ?: 0)
    }

    private fun movementCost(raw: MapUnit, unique: Unique): Int {
        if (unique.hasModifier(UniqueType.UnitActionMovementCostAll)) return raw.getMaxMovement()
        return unique.modifiers
            .filter { it.type == UniqueType.UnitActionMovementCost || it.type == UniqueType.UnitActionMovementCostRequired }
            .maxOfOrNull { it.params[0].toInt() } ?: 1
    }

    private fun actionUnique(raw: MapUnit, candidate: Candidate): Unique? =
        candidate.action.associatedUnique
            ?: abilityUniques[candidate.wireType]?.let { raw.getMatchingUniques(it).firstOrNull() }

    private fun consumesUnit(raw: MapUnit, candidate: Candidate): Boolean {
        // 伟人类型化动作（加速研究／加速建筑／贸易任务）的原生闭包恒定 unit.consume()，与 Unique 修饰符无关，先行判定。
        if (candidate.wireType in consumingTyped) return true
        val unique = actionUnique(raw, candidate) ?: return false
        val left = usagesLeft(raw, unique)
        return unique.hasModifier(UniqueType.UnitActionConsumeUnit) ||
            (unique.hasModifier(UniqueType.UnitActionAfterWhichConsumed) && left == 1)
    }

    /** 目标：传教／清异教／加速建筑作用于所在或所站城市，贸易作用于城邦，瞬建作用于当前格；科研与黄金时代为帝国范围。 */
    private fun targetDto(raw: MapUnit, wireType: String): JsonObject? {
        val tile = raw.currentTile
        return when (wireType) {
            "spreadReligion", "removeHeresy", "hurryBuilding" ->
                tile.getCity()?.let { dto("city" to it.name, "position" to it.location.dto()) }
            "tradeMission" ->
                tile.owningCity?.let { dto("city" to it.name, "position" to it.location.dto()) }
            "createImprovement" -> dto("position" to tile.position.dto())
            else -> null
        }
    }

    private fun reason(raw: MapUnit, candidate: Candidate): String {
        if (candidate.action.action != null) return ""
        return if (!raw.hasMovement()) "行动力不足"
        else "当前目标或条件不满足（需站在合适地块、次数未耗尽且未达原生限制）"
    }

    private fun effectText(wireType: String, action: UnitAction): String =
        action.associatedUnique?.text?.removeConditionals() ?: when (wireType) {
            "hurryResearch" -> "消耗单位，立即获得一批科研（原生结算）"
            "hurryBuilding" -> "消耗单位，为所在城市当前建筑追加产能（原生结算）"
            "tradeMission" -> "消耗单位，在城邦完成贸易任务获得金币与影响力（原生结算）"
            else -> action.title
        }

    private fun candidateDto(raw: MapUnit, candidate: Candidate): JsonObject {
        val unique = actionUnique(raw, candidate)
        return dto(
            "id" to candidate.id,
            "type" to candidate.wireType,
            "title" to candidate.action.title,
            "effect" to effectText(candidate.wireType, candidate.action),
            "position" to raw.currentTile.position.dto(),
            "target" to targetDto(raw, candidate.wireType),
            "usesLeft" to unique?.let { usagesLeft(raw, it) },
            "maxUsages" to unique?.let { maxUsages(raw, it) },
            "movementCost" to unique?.let { movementCost(raw, it) },
            "consumesUnit" to consumesUnit(raw, candidate),
            "enabled" to (candidate.action.action != null),
            "reason" to reason(raw, candidate),
        )
    }

    /** 票据绑定 session、revision、gameId、unitId、位置与候选内容；任一变化即失效。 */
    private fun token(raw: MapUnit, list: List<Candidate>): String {
        val contents = dto("session" to session, "revision" to revision, "gameId" to game.gameId,
            "unitId" to raw.id, "position" to raw.currentTile.position.dto(),
            "candidates" to list.map { it.id })
        return MessageDigest.getInstance("SHA-256").digest(contents.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun checkKeys(request: JsonObject, keys: Set<String>) {
        if ((request.keys - envelope - keys).isNotEmpty()) throw GatewayError("INVALID_ARGUMENT", "含未支持的能力参数")
    }

    private fun text(request: JsonObject, key: String): String = (request[key] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        ?: throw GatewayError("INVALID_ARGUMENT", "缺少非空字符串参数或类型错误：$key")

    fun options(request: JsonObject): JsonObject {
        checkKeys(request, setOf("unitId"))
        GameSession.validateGame(game)
        val id = request.integer("unitId")
        val raw = ownedRaw(id)
        val list = candidates(raw)
        return dto("unitId" to id, "position" to raw.currentTile.position.dto(),
            "token" to token(raw, list),
            "candidates" to list.map { candidateDto(raw, it) },
            "note" to "选择能力仅预览，确认后才写入；标注消耗单位的能力执行后单位消失。")
    }

    /** 快照只构造轻量摘要，不生成票据或完整候选 DTO。 */
    fun summaryDto(raw: MapUnit): JsonObject {
        val list = candidates(raw)
        return dto("count" to list.size, "types" to list.map { it.wireType }.distinct(),
            "usable" to list.any { it.action.action != null })
    }

    fun prepare(request: JsonObject): () -> Unit {
        checkKeys(request, setOf("unitId", "candidateId", "token"))
        GameSession.validateGame(game)
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val id = request.integer("unitId")
        val candidateId = text(request, "candidateId")
        val supplied = text(request, "token")
        val raw = ownedRaw(id)
        val list = candidates(raw)
        if (supplied != token(raw, list)) throw GatewayError("ABILITY_DECISION", "能力候选已变化，请刷新后重试")
        val candidate = list.firstOrNull { it.id == candidateId }
            ?: throw GatewayError("ABILITY_CANDIDATE", "该能力不是当前可选项")
        val closure = candidate.action.action
            ?: throw GatewayError("UNIT_ACTION", reason(raw, candidate))
        return { closure.invoke() }
    }

    companion object {
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
    }
}

internal fun contributeUnitAbilities(registry: CommandRegistry, session: GameSession) {
    registry.query("unitAbilityOptions") {
        UnitAbilityCommands(session.requireGame(), it.sessionId, it.revision).options(it.request)
    }
    registry.effect("unitAbility") { ctx ->
        UnitAbilityCommands(session.requireGame(), ctx.sessionId, ctx.revision).prepare(ctx.request)
    }
}
