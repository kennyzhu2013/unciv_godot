package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import kotlinx.serialization.json.JsonObject

/**
 * 单位成长：单次晋升与正常付费升级，全部委托 core 的 [UnitPromotions][com.unciv.logic.map.mapunit.UnitPromotions]
 * 与 [UnitActionsUpgrade]。选项与写前校验共用同一套只读资格函数；预览不扣费、不消耗经验、不改状态。
 * 晋升对齐 [PromotionPickerScreen][com.unciv.ui.screens.pickerscreens.PromotionPickerScreen] 的整体门槛，
 * 但逐项判断免费或足够经验，不以整体 canBePromoted() 替代单个候选的费用检查。
 */
internal class UnitProgressionCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv

    fun ownedUnit(id: Int): MapUnit = player.units.getCivUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")

    /** 核武器的主动操作尚未接入；空军（含舰载机）的晋升与升级已与原生弹窗对齐，一并开放。 */
    private fun ensurePlannedUnit(unit: MapUnit) {
        if (unit.isNuclearWeapon())
            throw GatewayError("UNSUPPORTED", "核武器主动操作尚未接入")
    }

    /** 晋升整体门槛：复刻 canPromoteNow 中不依赖 GUI 状态的部分（可改状态由网关事务保证）。 */
    private fun promotionGate(unit: MapUnit): String = when {
        !unit.hasMovement() -> "本回合行动力已耗尽，不能晋升"
        unit.attacksThisTurn != 0 -> "本回合已攻击，不能晋升"
        else -> ""
    }

    /** unitOptions.progression：XP、已获晋升、晋升候选与升级候选。仅用于己方单位。 */
    fun progressionDto(unit: MapUnit): JsonObject {
        val promotions = unit.promotions
        val gate = promotionGate(unit)
        val xp = promotions.XP
        val xpNeeded = promotions.xpForNextPromotion()
        return dto(
            "supported" to !unit.isNuclearWeapon(),
            "reason" to if (unit.isNuclearWeapon()) "核武器主动操作尚未接入" else "",
            "xp" to xp,
            "xpForNextPromotion" to xpNeeded,
            "numberOfPromotions" to promotions.numberOfPromotions,
            "acquired" to promotions.promotions.toList().sorted(),
            "promotions" to promotionOptions(unit, gate, xp, xpNeeded),
            "upgrades" to upgradeOptions(unit)
        )
    }

    private fun promotionOptions(unit: MapUnit, gate: String, xp: Int, xpNeeded: Int): List<JsonObject> =
        unit.promotions.getAvailablePromotions().map { promotion ->
            val free = promotion.hasUnique(UniqueType.FreePromotion)
            val affordable = free || xp >= xpNeeded
            val enabled = gate.isEmpty() && affordable
            val reason = when {
                gate.isNotEmpty() -> gate
                !affordable -> "经验不足：需 $xpNeeded，当前 $xp"
                else -> ""
            }
            dto("kind" to "promotion", "name" to promotion.name,
                "description" to promotionDescription(promotion),
                "free" to free, "enabled" to enabled, "reason" to reason)
        }.toList()

    private fun promotionDescription(promotion: Promotion): String =
        promotion.uniques.joinToString("；").ifEmpty { promotion.name }

    private fun upgradeOptions(unit: MapUnit): List<JsonObject> =
        UnitActionsUpgrade.getUpgradeActions(unit)
            .filterIsInstance<UpgradeUnitAction>()
            .map { upgrade ->
                val target = upgrade.unitToUpgradeTo
                val planned = isPlannedUpgradeTarget(target)
                val enabled = planned && upgrade.action != null
                val reason = when {
                    !planned -> "此升级目标属于尚未接入的操作领域"
                    upgrade.action != null -> ""
                    else -> upgradeRejectReason(unit, upgrade)
                }
                dto("kind" to "upgrade", "name" to target.name,
                    "gold" to upgrade.goldCostOfUpgrade,
                    "resources" to upgrade.newResourceRequirements.entries.filter { it.value > 0 }
                        .map { dto("name" to it.key, "amount" to it.value) },
                    "enabled" to enabled, "reason" to reason)
            }.toList()

    /** 升级目标过滤：核武器没有常规单位升级路径，一并排除以防未来规则；空军升级已开放。 */
    private fun isPlannedUpgradeTarget(target: BaseUnit): Boolean =
        !target.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals)

    /** 复刻 [UpgradeUnitAction] 的 takeIf 条件，逐项解释当前不可升级的原因。 */
    private fun upgradeRejectReason(unit: MapUnit, upgrade: UpgradeUnitAction): String = when {
        player.gold < upgrade.goldCostOfUpgrade -> "金币不足：需 ${upgrade.goldCostOfUpgrade}"
        !unit.hasMovement() -> "本回合行动力已耗尽"
        unit.currentTile.getOwner() != player -> "升级只能在己方领土内进行"
        unit.isEmbarked() -> "登船单位不能升级"
        else -> "当前不满足升级到此目标的条件"
    }

    /** 校验并返回单个晋升动作；执行只调用原生 addPromotion，一次性效果与前置均由原生处理。 */
    fun preparePromote(id: Int, name: String): () -> Unit {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        ensurePlannedUnit(unit)
        val gate = promotionGate(unit)
        if (gate.isNotEmpty()) throw GatewayError("CANNOT_PROMOTE", gate)
        val promotion = game.ruleset.unitPromotions[name]
            ?: throw GatewayError("INVALID_ARGUMENT", "未知晋升：$name")
        unit.promotions.getAvailablePromotions().firstOrNull { it.name == name }
            ?: throw GatewayError("CANNOT_PROMOTE", "当前不能采用此晋升（前置、单位类型或互斥未满足）")
        val free = promotion.hasUnique(UniqueType.FreePromotion)
        if (!free && unit.promotions.XP < unit.promotions.xpForNextPromotion())
            throw GatewayError("CANNOT_PROMOTE", "经验不足：需 ${unit.promotions.xpForNextPromotion()}，当前 ${unit.promotions.XP}")
        return { unit.promotions.addPromotion(name) }
    }

    /** 校验并返回一次正常付费升级动作；闭包只执行一次，成功后单位由原生销毁并重建。 */
    fun prepareUpgrade(id: Int, targetUnit: String): () -> Unit {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        ensurePlannedUnit(unit)
        if (game.ruleset.units[targetUnit] == null)
            throw GatewayError("INVALID_ARGUMENT", "未知升级目标：$targetUnit")
        val upgrade = UnitActionsUpgrade.getUpgradeActions(unit)
            .filterIsInstance<UpgradeUnitAction>()
            .firstOrNull { it.unitToUpgradeTo.name == targetUnit }
            ?: throw GatewayError("CANNOT_UPGRADE", "当前没有到此目标的升级动作")
        if (!isPlannedUpgradeTarget(upgrade.unitToUpgradeTo))
            throw GatewayError("UNSUPPORTED", "此升级目标属于尚未接入的操作领域")
        val action = upgrade.action ?: throw GatewayError("CANNOT_UPGRADE", upgradeRejectReason(unit, upgrade))
        return { action.invoke() }
    }
}

internal fun contributeUnitProgression(registry: CommandRegistry, session: GameSession) {
    registry.effect("unitPromote") {
        GameSession.validateGame(session.requireGame())
        UnitProgressionCommands(session.requireGame()).preparePromote(it.request.integer("unitId"), it.request.text("promotion"))
    }
    registry.effect("unitUpgrade") {
        GameSession.validateGame(session.requireGame())
        UnitProgressionCommands(session.requireGame()).prepareUpgrade(it.request.integer("unitId"), it.request.text("targetUnit"))
    }
}
