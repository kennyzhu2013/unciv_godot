package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.WorkerImprovementActions
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.stats.Stats
import com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * 适配当前地块的工人施工入口：查询与提交都复用 core 规则，绝不绕过 [WorkerImprovementActions]。
 * 只处理“选中单位所在地块”，不涉及跨地块自动铺路、链式工程或买地。
 */
internal class WorkerCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val ruleset = game.ruleset

    fun ownedUnit(id: Int): MapUnit = player.units.getCivUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")

    /** 施工前置条件不满足时返回原因；`null` 表示可以在当前地块施工。 */
    private fun blockedReason(unit: MapUnit): String? {
        val tile = unit.currentTile
        return when {
            !unit.cache.hasUniqueToBuildImprovements -> "此单位不能施工"
            !unit.baseUnit.isLandUnit || unit.baseUnit.isAirUnit() -> "只有陆军单位能在陆地上施工"
            unit.isEmbarked() -> "登船单位不能施工"
            tile.isCityCenter() -> "城市中心地块不能施工"
            !unit.hasMovement() -> "本回合行动力已耗尽"
            else -> null
        }
    }

    /** unitOptions.worker 子对象：按单位能力分流为陆地工人或水上瞬建。 */
    fun workerDto(unit: MapUnit): JsonObject =
        if (unit.cache.hasUniqueToCreateWaterImprovements) waterDto(unit) else landDto(unit)

    /** 水上工作船：当前格资源的即时改良，一次闭包完成并消耗单位；不开放伟人的通用瞬建能力。 */
    private fun waterDto(unit: MapUnit): JsonObject {
        val tile = unit.currentTile
        val name = waterImprovementName(unit)
        val action = name?.let { waterAction(unit, it) }
        val enabled = action?.action != null
        val improvement = name?.let { ruleset.tileImprovements[it] }
        val reason = when {
            enabled -> ""
            !tile.isWater -> "工作船只能在水上资源格创建改良"
            name == null -> "当前地块没有可改良的可见资源"
            action == null -> "当前不能在此创建该改良"
            !unit.hasMovement() -> "本回合行动力已耗尽"
            else -> "当前不能创建此改良"
        }
        return dto(
            "mode" to "waterInstant",
            "supported" to enabled,
            "enabled" to enabled,
            "reason" to reason,
            "consumesUnit" to true,
            "tile" to tile.position.dto(),
            "improvement" to name,
            "resource" to tile.tileResource?.takeIf { player.canSeeResource(it) }?.name,
            "yieldDelta" to (if (improvement != null) statsDto(tile.stats.getStatDiffForImprovement(improvement, player, tile.getCity())) else dto()),
            "maintenance" to (if (improvement != null) statsDto(tile.improvementFunctions.getMaintenance(improvement, player)) else dto())
        )
    }

    /** 当前格资源的原生改良名；资源不可见或无对应改良返回 null。 */
    private fun waterImprovementName(unit: MapUnit): String? {
        val tile = unit.currentTile
        val resource = tile.tileResource ?: return null
        if (!player.canSeeResource(resource)) return null
        return resource.getImprovingImprovement(tile, unit.cache.state)?.name
    }

    /** 原生 CreateImprovement 映射返回的水上资源动作，以标题模板 "Create [X]" 精确匹配。 */
    private fun waterAction(unit: MapUnit, name: String): UnitAction? =
        UnitActions.getUnitActions(unit, UnitActionType.CreateImprovement).firstOrNull { it.title == "Create [$name]" }

    /** unitOptions.worker 陆地工人子对象：当前工程、保留标记、改良候选、修复与取消。 */
    private fun landDto(unit: MapUnit): JsonObject {
        val tile = unit.currentTile
        val blocked = blockedReason(unit)
        val reserved = tile.isMarkedForCreatesOneImprovement()
        val inProgress = tile.improvementInProgress
        return dto(
            "mode" to "worker",
            "supported" to (blocked == null),
            "reason" to (blocked ?: ""),
            "reservedForCreatesOneImprovement" to reserved,
            "inProgress" to (if (inProgress == null) null else dto(
                "name" to inProgress,
                "turnsLeft" to tile.turnsToImprovement,
                "isRepair" to (inProgress == Constants.repair))),
            "improvements" to improvementOptions(unit, tile, blocked, reserved).map { it.toDto() },
            "repair" to repairDto(unit, tile, blocked),
            "cancel" to dto(
                "available" to (blocked == null && inProgress != null && !reserved),
                "reason" to when {
                    blocked != null -> blocked
                    inProgress == null -> "当前没有正在进行的施工"
                    reserved -> "此地块已被“建造一次改良”预留"
                    else -> ""
                })
        )
    }

    private class Option(
        val improvement: TileImprovement,
        val enabled: Boolean,
        val reason: String,
        val current: Boolean,
        val turns: Int,
        val yieldDelta: Stats,
        val maintenance: Stats,
        val providesResource: String?
    ) {
        fun toDto(): JsonObject = dto(
            "name" to improvement.name,
            "enabled" to enabled,
            "reason" to reason,
            "current" to current,
            "turns" to turns,
            "yieldDelta" to statsDto(yieldDelta),
            "maintenance" to statsDto(maintenance),
            "providesResource" to providesResource
        )
    }

    private fun improvementOptions(unit: MapUnit, tile: Tile, blocked: String?, reserved: Boolean): List<Option> {
        val options = ArrayList<Option>()
        val withoutLastTerrain = tileWithoutLastTerrain(tile)
        for (improvement in ruleset.tileImprovements.values) {
            // 与 ImprovementPickerScreen 一致：跳过“仅可放置”的改良（伟人改良等），但保留取消；修复走专用字段。
            if (improvement.turnsToBuild == -1 && improvement.name != Constants.cancelImprovementOrder) continue
            if (improvement.name == Constants.repair) continue
            if (improvement.name == Constants.cancelImprovementOrder) continue
            if (improvement.name == tile.improvement) continue
            if (!unit.canBuildImprovement(improvement)) continue

            val problems = tile.improvementFunctions.getImprovementBuildingProblems(improvement, unit.cache.state).toSet()
            // 不可报告的问题意味着“现在建不了且无法解释”；若能通过先移除地貌解决，则仍列出但禁用。
            val needsRemoval = !ImprovementPickerScreen.canReport(problems)
            if (needsRemoval) {
                val afterRemoval = withoutLastTerrain
                    ?.improvementFunctions?.getImprovementBuildingProblems(improvement, unit.cache.state)?.toSet()
                if (afterRemoval == null || !ImprovementPickerScreen.canReport(afterRemoval)) continue
            }

            val enabled = blocked == null && !reserved && problems.isEmpty()
            val reason = when {
                enabled -> ""
                blocked != null -> blocked
                reserved -> "此地块已被“建造一次改良”预留"
                needsRemoval -> "需要先移除[${tile.lastTerrain.name}]"
                else -> describe(problems)
            }
            val resource = tile.tileResource
            val providesResource = resource?.name?.takeIf {
                player.canSeeResource(resource) && resource.isImprovedBy(improvement.name)
            }
            options.add(Option(
                improvement = improvement,
                enabled = enabled,
                reason = reason,
                current = tile.improvementInProgress == improvement.name,
                turns = if (tile.improvementInProgress == improvement.name) tile.turnsToImprovement
                    else improvement.getTurnsToBuild(player, unit),
                yieldDelta = tile.stats.getStatDiffForImprovement(improvement, player, tile.getCity()),
                maintenance = tile.improvementFunctions.getMaintenance(improvement, player),
                providesResource = providesResource
            ))
        }
        return options
    }

    private fun describe(problems: Set<ImprovementBuildingProblem>): String = when {
        ImprovementBuildingProblem.MissingTech in problems -> "需要先研究相应科技"
        ImprovementBuildingProblem.NotJustOutsideBorders in problems -> "此地块需紧邻你的领土边界"
        ImprovementBuildingProblem.OutsideBorders in problems -> "此地块需在你的领土内"
        ImprovementBuildingProblem.MissingResources in problems -> "缺少建造所需的资源"
        else -> "当前不能在此地块建造该改良"
    }

    private fun repairDto(unit: MapUnit, tile: Tile, blocked: String?): JsonObject {
        val pillaged = tile.isPillaged()
        val available = blocked == null && pillaged && UnitActionsFromUniques.getRepairAction(unit)?.action != null
        return dto(
            "available" to available,
            "reason" to when {
                blocked != null -> blocked
                !pillaged -> "此地块没有受损的改良或道路"
                tile.improvementInProgress == Constants.repair -> "已在修复中"
                !available -> "此单位当前不能修复此地块"
                else -> ""
            },
            "turns" to (if (available) UnitActionsFromUniques.getRepairTurns(unit) else 0)
        )
    }

    private fun tileWithoutLastTerrain(tile: Tile): Tile? {
        if (Constants.remove + tile.lastTerrain.name !in ruleset.tileImprovements) return null
        val newTile = tile.clone(addUnits = false)
        newTile.setTerrainTransients()
        newTile.removeTerrainFeature(newTile.lastTerrain.name)
        return newTile
    }

    /** 校验并返回施工动作；动作在 GameSession 的当前局上执行，失败会触发回滚。 */
    fun prepareOrder(id: Int, type: String, name: String): () -> Unit {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        if (type == "createWaterImprovement") return prepareWaterImprovement(unit, name)
        val tile = unit.currentTile
        val blocked = blockedReason(unit)
        if (blocked != null) throw GatewayError("CANNOT_IMPROVE", blocked)
        when (type) {
            "start" -> {
                val improvement = ruleset.tileImprovements[name]
                    ?: throw GatewayError("INVALID_ARGUMENT", "未知改良项目")
                ensure(improvement.name != Constants.repair && improvement.name != Constants.cancelImprovementOrder,
                    "INVALID_ARGUMENT", "修复与取消请使用对应的 type")
                ensure(!tile.isMarkedForCreatesOneImprovement(), "CANNOT_IMPROVE", "此地块已被“建造一次改良”预留")
                val problems = tile.improvementFunctions.getImprovementBuildingProblems(improvement, unit.cache.state).toSet()
                ensure(unit.canBuildImprovement(improvement) && problems.isEmpty(),
                    "CANNOT_IMPROVE", describe(problems).ifEmpty { "当前不能在此地块建造该改良" })
                return {
                    ensure(WorkerImprovementActions.accept(tile, unit, improvement), "CANNOT_IMPROVE", "施工未提交")
                }
            }
            "repair" -> {
                val action = UnitActionsFromUniques.getRepairAction(unit)?.action
                    ?: throw GatewayError("CANNOT_IMPROVE", "当前不能修复此地块")
                return action
            }
            "cancel" -> {
                val cancel = ruleset.tileImprovements[Constants.cancelImprovementOrder]
                    ?: throw GatewayError("CANNOT_IMPROVE", "没有可取消的施工")
                ensure(tile.improvementInProgress != null && !tile.isMarkedForCreatesOneImprovement(),
                    "CANNOT_IMPROVE", "没有可取消的施工")
                return { WorkerImprovementActions.accept(tile, unit, cancel) }
            }
            else -> throw GatewayError("INVALID_ARGUMENT", "未知施工命令类型：$type")
        }
    }

    /** 水上瞬建：只处理单位当前格，名称必须匹配原生候选；执行一次原生闭包完成改良并消耗工作船。 */
    private fun prepareWaterImprovement(unit: MapUnit, name: String): () -> Unit {
        ensure(unit.cache.hasUniqueToCreateWaterImprovements, "CANNOT_IMPROVE", "此单位不能创建水上改良")
        val improvementName = waterImprovementName(unit)
            ?: throw GatewayError("CANNOT_IMPROVE", "当前地块没有可改良的可见资源")
        ensure(name == improvementName, "INVALID_ARGUMENT", "改良名称与当前原生候选不符")
        return waterAction(unit, name)?.action
            ?: throw GatewayError("CANNOT_IMPROVE", if (!unit.hasMovement()) "本回合行动力已耗尽" else "当前不能创建此改良")
    }

    private fun ensure(condition: Boolean, code: String, message: String) {
        if (!condition) throw GatewayError(code, message)
    }
}

internal fun contributeWorker(registry: CommandRegistry, session: GameSession) {
    registry.effect("workerOrder") {
        GameSession.validateGame(session.requireGame())
        WorkerCommands(session.requireGame()).prepareOrder(it.request.integer("unitId"), it.request.text("type"), it.request.text("name"))
    }
}

/** 只发送非零项，保留一位小数；不写 NaN/Infinity。 */
private fun statsDto(stats: Stats): JsonObject {
    val entries = ArrayList<Pair<String, Any?>>()
    for ((key, value) in stats) {
        val rounded = (value * 10).roundToInt() * 0.1f
        if (rounded != 0f && rounded.isFinite()) entries.add(key.name to rounded)
    }
    return dto(*entries.toTypedArray())
}
