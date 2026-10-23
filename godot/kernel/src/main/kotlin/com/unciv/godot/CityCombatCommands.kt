package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.view.CityCombatantView
import com.unciv.view.CityView
import com.unciv.view.CombatantView
import com.unciv.view.GameView
import com.unciv.view.TileView
import kotlinx.serialization.json.JsonObject

/**
 * 己方城市主动轰击：资格由 [City.canBombard][com.unciv.logic.city.City.canBombard] 与
 * [TargetHelper.getBombardableTiles][com.unciv.logic.battle.TargetHelper.getBombardableTiles] 决定，
 * 并复刻 [BattleTable][com.unciv.ui.screens.worldscreen.bottombar.BattleTable] 对敌方城市的排除。
 * 目标集直接取原生可轰击地块（已含可见性、战争关系、范围与登船限制），不套用单位攻击的“陆地平民未接入”过滤，
 * 以免误删城市合法目标。预览用只读战斗计算，不执行、不改 HP 或 attackedThisTurn。
 */
internal class CityCombatCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val view = GameView(game, player)

    private fun ownedCity(id: String): CityView = view.civView.cities().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方城市")

    private fun bombardBlockReason(city: CityView): String {
        val raw = city.getCity()
        return when {
            raw.getBombardRange() <= 0 -> "此城市没有轰击能力"
            raw.attackedThisTurn -> "此城市本回合已攻击"
            raw.isInResistance() -> "此城市处于抵抗状态，不能轰击"
            else -> ""
        }
    }

    /** 原生可轰击且非敌方城市的目标地块；敌方城市由 BattleTable 排除，这里复刻。 */
    private fun bombardTargets(city: CityView): List<TileView> =
        if (!city.canBombard()) emptyList()
        else city.getBombardableTiles().filter { it.getCombatant() !is CityCombatantView }

    /** cityOptions.bombard：资格、射程、本回合是否已攻击与可轰击目标。 */
    fun bombardDto(city: CityView): JsonObject {
        val canBombard = city.canBombard()
        return dto(
            "supported" to true,
            "enabled" to canBombard,
            "reason" to if (canBombard) "" else bombardBlockReason(city),
            "range" to city.getCity().getBombardRange(),
            "attackedThisTurn" to city.getCity().attackedThisTurn,
            "attackTargets" to bombardTargets(city).map { it.position().dto() }
        )
    }

    fun prepareBombard(cityId: String, target: HexCoord): PreparedBombard {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val city = ownedCity(cityId)
        if (!city.canBombard()) throw GatewayError("CANNOT_ATTACK", bombardBlockReason(city))
        val tile = bombardTargets(city).firstOrNull { it.position() == target }
            ?: throw GatewayError("CANNOT_ATTACK", "当前不能轰击此目标")
        val defender = tile.getCombatant()
            ?: throw GatewayError("CANNOT_ATTACK", "当前不能轰击此目标")
        return PreparedBombard(city, tile, defender, player, view)
    }

    /** 一次城市轰击的准备结果：预览零副作用，执行只调用原生 tryBombard 一次。 */
    class PreparedBombard(private val city: CityView, private val tile: TileView, private val defender: CombatantView,
                          private val player: Civilization, private val view: GameView) {
        fun preview(): JsonObject {
            val attacker = city.asCombatant()
            val from = city.getCenterTile()
            val minDefender = attacker.calculateDamageToDefender(defender, from, 0f)
            val maxDefender = attacker.calculateDamageToDefender(defender, from, 1f)
            fun damage(min: Int, max: Int, health: Int) = dto("min" to min.coerceIn(0, health), "max" to max.coerceIn(0, health))
            fun modifiers(values: Map<String, Int>) = values.map { dto("name" to it.key, "percent" to it.value) }
            return dto("cityId" to city.id, "kind" to "bombard",
                "target" to tile.position().dto(),
                "attackFrom" to from.position().dto(), "path" to emptyList<JsonObject>(),
                "canCaptureCity" to false,
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
            val attacker = city.asCombatant()
            val attackableTile = attacker.buildBombardAttackableTile(tile, defender, player, false, view)
            val damage = city.tryBombard(attackableTile)
            return dto("outcome" to "bombarded", "kind" to "bombard",
                "damageToDefender" to damage.attackerDealt, "damageToAttacker" to damage.defenderDealt)
        }
    }
}

internal fun contributeCityCombat(registry: CommandRegistry, session: GameSession) {
    registry.query("cityBombardPreview") {
        val current = session.requireGame()
        GameSession.validateGame(current)
        CityCombatCommands(current).prepareBombard(it.request.text("cityId"),
            HexCoord(it.request.integer("x"), it.request.integer("y"))).preview()
    }
    registry.effect("cityBombard") { ctx ->
        val current = session.requireGame()
        GameSession.validateGame(current)
        val prepared = CityCombatCommands(current).prepareBombard(ctx.request.text("cityId"),
            HexCoord(ctx.request.integer("x"), ctx.request.integer("y")))
        val thunk: () -> Unit = { ctx.battleResult = prepared.execute() }
        thunk
    }
}
