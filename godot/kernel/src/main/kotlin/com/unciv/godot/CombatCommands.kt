package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.*
import kotlinx.serialization.json.JsonObject

/** 适配陆海军与登船战斗；校验与预览不执行移动、战斗或随机模拟。 */
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

    private fun supportsBasicActions(unit: MapUnitView) = !unit.isAirUnit() && !unit.isNuclearWeapon()
        && !unit.isPreparingParadrop() && (unit.getBaseUnit().isLandUnit || unit.getBaseUnit().isWaterUnit)

    fun combatReason(unit: MapUnitView): String = when {
        !supportsBasicActions(unit) -> "空军、空降和核武器主动操作尚未接入"
        !unit.isMilitary() -> "平民单位不能主动攻击"
        else -> ""
    }

    fun actions(unit: MapUnitView): List<JsonObject> {
        if (!supportsBasicActions(unit)) return emptyList()
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        return UnitActions.getBasicActions(raw).mapNotNull { action ->
            val id = actionTypes.entries.firstOrNull { it.value == action.type }?.key ?: return@mapNotNull null
            dto("id" to id, "enabled" to (action.action != null), "current" to action.isCurrentAction,
                "reason" to if (action.action != null) "" else "原生规则在当前单位状态或地块上不允许执行")
        }.toList()
    }

    fun prepareAction(id: Int, type: String): () -> Unit {
        ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        val mapped = actionTypes[type] ?: throw GatewayError("UNIT_ACTION", "未接入此单位动作")
        if (!supportsBasicActions(unit)) throw GatewayError("UNSUPPORTED", combatReason(unit))
        val raw = player.units.getCivUnits().first { it.id == id }
        return UnitActions.getBasicActions(raw).firstOrNull { it.type == mapped }?.action
            ?: throw GatewayError("UNIT_ACTION", "当前不能执行此单位动作")
    }

    fun ensureNoBlockingBattleDecision() {
        // 原生会在打开下一个弹窗前移除队首连续失效事件，它们不构成阻塞；其余弹窗都会遮住地图。
        val queue = PendingQueue(game)
        val blocking = player.popupAlerts.filter(queue::isOpenable)
        if (blocking.any { it.type == AlertType.CityConquered || routeOf(it.type) != AlertRoute.Informational })
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
                if (unit.isAirUnit() || unit.getUnit().isNuclearWeapon()) return null
                if (!unit.isMilitary() && !tile.isLand && !unit.isEmbarked() && !unit.getBaseUnit().isWaterUnit) return null
                if (!unit.isMilitary() && tile.isLand) return null
                unit.getUnit().civ
            }
        }
        return defender.takeIf { player.isAtWarWith(enemy) }
    }

    private fun candidates(unit: MapUnitView): List<AttackableTileView> {
        if (combatReason(unit).isNotEmpty() || !unit.canAttack()) return emptyList()
        return unit.getAttackableEnemies(unit.getDistanceToTiles()).filter { visibleEnemy(it.getTileToAttack()) != null }
    }

    fun attackTargets(unit: MapUnitView): List<JsonObject> = candidates(unit)
        .map { it.getTileToAttack().position() }.distinct().map { it.dto() }

    fun prepareAttack(id: Int, target: HexCoord): PreparedAttack {
        val unit = ownedUnit(id)
        if (!supportsBasicActions(unit)) throw GatewayError("UNSUPPORTED", combatReason(unit))
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
            val captureCivilian = (defender as? MapUnitCombatantView)?.getUnitView()?.isCivilian() == true && !attacker.isRanged()
            return dto("unitId" to unit.id, "kind" to if (captureCivilian) "captureCivilian" else "combat",
                "target" to target.getTileToAttack().position().dto(),
                "attackFrom" to from.position().dto(), "path" to unit.getPathToTile(from).map { it.position().dto() },
                "canCaptureCity" to (defender.isCity() && !attacker.isRanged() && !attacker.hasUnique(UniqueType.CannotCaptureCities)),
                "attacker" to dto("name" to attacker.getCombatantName(), "health" to attacker.getHealth(), "maxHealth" to attacker.getMaxHealth(),
                    "strength" to attacker.getAttackingStrength(defender), "finalStrength" to attacker.getFinalAttackingStrength(defender, from),
                    "modifiers" to modifiers(attacker.getAttackModifiers(defender, from))),
                "defender" to dto("name" to defender.getCombatantName(), "health" to defender.getHealth(), "maxHealth" to defender.getMaxHealth(),
                    "strength" to defender.getDefendingStrength(attacker), "finalStrength" to defender.getFinalDefendingStrength(attacker, from),
                    "modifiers" to modifiers(defender.getDefenceModifiers(attacker, from))),
                "damageToDefender" to if (captureCivilian) dto("min" to 0, "max" to 0)
                    else damage(minDefender, maxDefender, defender.getHealth()),
                "damageToAttacker" to damage(attacker.calculateDamageToAttacker(defender, from, 0f),
                    attacker.calculateDamageToAttacker(defender, from, 1f), attacker.getHealth()))
        }

        fun execute(): JsonObject {
            // false 也可能已移动或完成架设，属于成功提交，不能回滚或强行补攻击。
            if (!unit.tryMovePreparingAttack(target, tryHealPillage = false))
                return dto("outcome" to "movedOnly", "damageToDefender" to 0, "damageToAttacker" to 0)
            val civilianTarget = (defender as? MapUnitCombatantView)?.getUnitView()?.isCivilian() == true && !unit.asCombatant().isRanged()
            val damage = unit.attackOrNuke(target)
            return dto("outcome" to "attacked", "kind" to if (civilianTarget) "captureCivilian" else "combat",
                "damageToDefender" to damage.attackerDealt, "damageToAttacker" to damage.defenderDealt)
        }
    }
}

internal fun contributeCombat(registry: CommandRegistry, session: GameSession) {
    registry.query("combatPreview") {
        val current = session.requireGame()
        GameSession.validateGame(current)
        CombatCommands(current).prepareAttack(it.request.integer("unitId"), HexCoord(it.request.integer("x"), it.request.integer("y"))).preview()
    }
    registry.effect("attack") { ctx ->
        val current = session.requireGame()
        GameSession.validateGame(current)
        val combat = CombatCommands(current)
        combat.ensureNoBlockingBattleDecision()
        val preparedAttack = combat.prepareAttack(ctx.request.integer("unitId"), HexCoord(ctx.request.integer("x"), ctx.request.integer("y")))
        val thunk: () -> Unit = { ctx.battleResult = preparedAttack.execute() }
        thunk
    }
    registry.effect("unitAction") {
        GameSession.validateGame(session.requireGame())
        CombatCommands(session.requireGame()).prepareAction(it.request.integer("unitId"), it.request.text("type"))
    }
}
