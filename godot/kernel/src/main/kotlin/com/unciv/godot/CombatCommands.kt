package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.*
import kotlinx.serialization.json.JsonObject

/** 只适配玩家陆战入口；校验与预览不执行移动、战斗或随机模拟。 */
internal class CombatCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val view = GameView(game, player)

    private val actionTypes = linkedMapOf(
        "skip" to UnitActionType.Skip,
        "fortify" to UnitActionType.Fortify,
        "fortifyUntilHealed" to UnitActionType.FortifyUntilHealed,
        "sleep" to UnitActionType.Sleep,
        "sleepUntilHealed" to UnitActionType.SleepUntilHealed,
        "setUp" to UnitActionType.SetUp
    )

    private fun ownedUnit(id: Int): MapUnitView = view.civView.getUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")

    private fun supported(unit: MapUnitView) = unit.getBaseUnit().isLandUnit && !unit.isEmbarked()
        && !unit.isAirUnit() && !unit.isNuclearWeapon()

    fun actions(unit: MapUnitView): List<JsonObject> {
        if (!supported(unit)) return emptyList()
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        return UnitActions.getBasicActions(raw).mapNotNull { action ->
            val id = actionTypes.entries.firstOrNull { it.value == action.type }?.key ?: return@mapNotNull null
            dto("id" to id, "enabled" to (action.action != null), "current" to action.isCurrentAction,
                "reason" to if (action.action != null) "" else "动作已生效或行动力不足")
        }.toList()
    }

    fun prepareAction(id: Int, type: String): () -> Unit {
        ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        val mapped = actionTypes[type] ?: throw GatewayError("UNIT_ACTION", "未接入此单位动作")
        if (!supported(unit)) throw GatewayError("UNSUPPORTED", "本轮只接入非登船陆军动作")
        val raw = player.units.getCivUnits().first { it.id == id }
        return UnitActions.getBasicActions(raw).firstOrNull { it.type == mapped }?.action
            ?: throw GatewayError("UNIT_ACTION", "当前不能执行此单位动作")
    }

    fun ensureNoBlockingBattleDecision() {
        if (player.popupAlerts.any { it.type == AlertType.CityConquered || it.type !in GameSession.informationalAlerts })
            throw GatewayError("PENDING_DECISION", "请先处理待决事件；未接入的事件可保存后用原客户端处理")
    }

    /** 必须在读取战斗对象前检查当前视野；已探索的城市并不等于可见。 */
    private fun visibleEnemy(tile: TileView): CombatantView? {
        if (!tile.isVisible()) return null
        val defender = tile.getCombatant() ?: return null
        val enemy = when (defender) {
            is CityCombatantView -> defender.getCityView().getCity().civ
            is MapUnitCombatantView -> {
                val unit = defender.getUnitView()
                if (!unit.isMilitary() || !unit.getBaseUnit().isLandUnit || unit.isEmbarked()) return null
                unit.getUnit().civ
            }
        }
        return defender.takeIf { player.isAtWarWith(enemy) }
    }

    private fun candidates(unit: MapUnitView): List<AttackableTileView> {
        if (!supported(unit) || !unit.isMilitary() || !unit.canAttack()) return emptyList()
        return unit.getAttackableEnemies(unit.getDistanceToTiles()).filter { visibleEnemy(it.getTileToAttack()) != null }
    }

    fun attackTargets(unit: MapUnitView): List<JsonObject> = candidates(unit)
        .map { it.getTileToAttack().position() }.distinct().map { it.dto() }

    fun prepareAttack(id: Int, target: HexCoord): PreparedAttack {
        val unit = ownedUnit(id)
        val rawTile = game.tileMap.getOrNull(target.x, target.y) ?: cannotAttack()
        val tile = view.getTile(rawTile)
        val defender = visibleEnemy(tile) ?: cannotAttack()
        val attack = candidates(unit).firstOrNull { it.getTileToAttack().position() == target } ?: cannotAttack()
        return PreparedAttack(unit, attack, defender)
    }

    private fun cannotAttack(): Nothing = throw GatewayError("CANNOT_ATTACK", "当前不能攻击此目标")

    class PreparedAttack(private val unit: MapUnitView, private val target: AttackableTileView,
                         private val defender: CombatantView) {
        fun preview(): JsonObject {
            val attacker = unit.asCombatant()
            val from = target.getTileToAttackFrom()
            var minDefender = attacker.calculateDamageToDefender(defender, from, 0f)
            var maxDefender = attacker.calculateDamageToDefender(defender, from, 1f)
            if (!defender.isCity() && attacker.hasUnique(UniqueType.ExtraRangedAttack)) {
                val (extraMax, extraMin) = attacker.getExtraRangedAttackBonusDamage(defender, from)
                minDefender += extraMin
                maxDefender += extraMax
            }
            fun damage(min: Int, max: Int, health: Int) = dto("min" to min.coerceIn(0, health), "max" to max.coerceIn(0, health))
            fun modifiers(values: Map<String, Int>) = values.map { dto("name" to it.key, "percent" to it.value) }
            return dto("unitId" to unit.id, "target" to target.getTileToAttack().position().dto(),
                "attackFrom" to from.position().dto(), "path" to unit.getPathToTile(from).map { it.position().dto() },
                "canCaptureCity" to (defender.isCity() && !attacker.isRanged() && !attacker.hasUnique(UniqueType.CannotCaptureCities)),
                "attacker" to dto("name" to attacker.getCombatantName(), "health" to attacker.getHealth(), "maxHealth" to attacker.getMaxHealth(),
                    "strength" to attacker.getAttackingStrength(defender), "finalStrength" to attacker.getFinalAttackingStrength(defender, from),
                    "modifiers" to modifiers(attacker.getAttackModifiers(defender, from))),
                "defender" to dto("name" to defender.getCombatantName(), "health" to defender.getHealth(), "maxHealth" to defender.getMaxHealth(),
                    "strength" to defender.getDefendingStrength(attacker), "finalStrength" to defender.getFinalDefendingStrength(attacker, from),
                    "modifiers" to modifiers(defender.getDefenceModifiers(attacker, from))),
                "damageToDefender" to damage(minDefender, maxDefender, defender.getHealth()),
                "damageToAttacker" to damage(attacker.calculateDamageToAttacker(defender, from, 0f),
                    attacker.calculateDamageToAttacker(defender, from, 1f), attacker.getHealth()))
        }

        fun execute(): JsonObject {
            // false 也可能已移动或完成架设，属于成功提交，不能回滚或强行补攻击。
            if (!unit.tryMovePreparingAttack(target, tryHealPillage = false))
                return dto("outcome" to "movedOnly", "damageToDefender" to 0, "damageToAttacker" to 0)
            val damage = unit.attackOrNuke(target)
            return dto("outcome" to "attacked", "damageToDefender" to damage.attackerDealt, "damageToAttacker" to damage.defenderDealt)
        }
    }
}
