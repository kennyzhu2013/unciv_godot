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
        // 核武同样是空军单位，必须先于空军判定：目标合法性由原生 Nuke.mayUseNuke 在候选与预览中给出。
        unit.isNuclearWeapon() -> ""
        // 空军已接入：空袭／空中扫荡与陆海共用原生 AttackableTile 与 Battle 入口。
        unit.isAirUnit() -> if (unit.isMilitary()) "" else "空军单位不能主动攻击"
        unit.isPreparingParadrop() -> "空降准备中：不能主动攻击，请确认落点或先取消空降"
        !supportsBasicActions(unit) -> "该单位当前不能执行陆海主动操作"
        !unit.isMilitary() -> "平民单位不能主动攻击"
        else -> ""
    }

    fun actions(unit: MapUnitView): List<JsonObject> {
        if (unit.isNuclearWeapon()) return emptyList()
        if (unit.isAirUnit()) return airActions(unit)
        // 空降开关来自原生 MayParadrop；该工厂同时重建落点过滤器，不能省略或改写其规则。
        val paradrop = paradropActions(unit)
        // 准备态下原生只保留空降本身（用于取消），普通动作与攻击都不再可选。
        if (unit.isPreparingParadrop()) return paradrop
        if (!supportsBasicActions(unit)) return paradrop
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        val basics = UnitActions.getBasicActions(raw).mapNotNull { action ->
            val id = actionTypes.entries.firstOrNull { it.value == action.type }?.key ?: return@mapNotNull null
            dto("id" to id, "enabled" to (action.action != null), "current" to action.isCurrentAction,
                "reason" to if (action.action != null) "" else "原生规则在当前单位状态或地块上不允许执行")
        }.toList()
        // 解散：原生仅要求单位尚有行动力；本方领土返还黄金、运输单位撤离与击破检查均在 MapUnit.disband 内。
        val canDisband = raw.hasMovement()
        return basics + paradrop + dto("id" to "disband", "enabled" to canDisband, "current" to false,
            "reason" to if (canDisband) "" else "行动力已耗尽，当前不能解散该单位")
    }

    fun prepareAction(id: Int, type: String): () -> Unit {
        ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        if (type == "paradrop") {
            // 与空中扫荡相同：准备与取消都是原生 UnitAction 闭包，只要求当前能拿到该动作。
            if (unit.isAirUnit() || unit.isNuclearWeapon()) throw GatewayError("UNSUPPORTED", "只有陆海单位可以空降")
            val raw = player.units.getCivUnits().first { it.id == id }
            return UnitActions.getUnitActions(raw, UnitActionType.Paradrop)
                .firstOrNull { it.action != null }?.action
                ?: throw GatewayError("UNIT_ACTION", "当前无法开始或取消空降（无该能力或本回合已移动）")
        }
        if (type == "airSweep") {
            // 空中扫荡是原生“准备态”切换：只执行 UnitActions 的 action 闭包，不在这里复制规则。
            if (!unit.isAirUnit()) throw GatewayError("UNSUPPORTED", "只有空军可以执行空中扫荡")
            val raw = player.units.getCivUnits().first { it.id == id }
            return UnitActions.getUnitActions(raw, UnitActionType.AirSweep)
                .firstOrNull { it.action != null }?.action
                ?: throw GatewayError("UNIT_ACTION", "当前无法执行空中扫荡（无该能力或已用完攻击次数）")
        }
        if (type == "disband") {
            if (!supportsBasicActions(unit)) throw GatewayError("UNSUPPORTED", combatReason(unit))
            val disbanding = player.units.getCivUnits().first { it.id == id }
            if (!disbanding.hasMovement()) throw GatewayError("UNIT_ACTION", "行动力已耗尽，当前不能解散该单位")
            // 只执行一次原生解散逻辑，不复制黄金返还／撤离／溃灭判定。
            return { disbanding.disband(); disbanding.civ.updateStatsForNextTurn() }
        }
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
    private fun visibleEnemy(unit: MapUnitView, tile: TileView): CombatantView? {
        if (!tile.isVisible()) return null
        val defender = tile.getCombatant() ?: return null
        val enemy = when (defender) {
            is CityCombatantView -> defender.getCityView().getCity().civ
            is MapUnitCombatantView -> {
                val target = defender.getUnitView()
                if (target.isAirUnit() || target.getUnit().isNuclearWeapon()) return null
                // 海上只允许登船俘获与海军目标，与既有已验收路径一致。
                if (!target.isMilitary() && !tile.isLand && !target.isEmbarked() && !target.getBaseUnit().isWaterUnit) return null
                // 陆上平民由原生近战“移动进入即俘获／攻击即俘获”结算，交由 TargetHelper 判定可达性与可攻击性。
                target.getUnit().civ
            }
        }
        return defender.takeIf { player.isAtWarWith(enemy) }
    }

    /** 空降动作：原生 MayParadrop 存在时才有此项；current 表示已进入落点选择，可再次点击取消。 */
    private fun paradropActions(unit: MapUnitView): List<JsonObject> {
        if (unit.isAirUnit() || unit.isNuclearWeapon()) return emptyList()
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        return UnitActions.getUnitActions(raw, UnitActionType.Paradrop).map { action ->
            dto("id" to "paradrop", "enabled" to (action.action != null), "current" to action.isCurrentAction,
                "reason" to if (action.action != null) "" else "本回合已移动，无法开始或取消空降")
        }.toList()
    }

    /** 空军动作：只有原生 AirSweep 准备态开关（空袭目标由 attackTargets 给出）。 */
    private fun airActions(unit: MapUnitView): List<JsonObject> {
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        return UnitActions.getUnitActions(raw, UnitActionType.AirSweep).map { action ->
            dto("id" to "airSweep", "enabled" to (action.action != null), "current" to action.isCurrentAction,
                "reason" to if (action.action != null) "" else "当前无法执行空中扫荡（无该能力或已用完攻击次数）")
        }.toList()
    }

    private fun candidates(unit: MapUnitView): List<AttackableTileView> {
        if (!unit.isAirUnit() && combatReason(unit).isNotEmpty()) return emptyList()
        if (!unit.canAttack()) return emptyList()
        val attackable = unit.getAttackableEnemies(unit.getDistanceToTiles())
        return when {
            // 核武目标已由原生 TargetHelper → Nuke.mayUseNuke 限定为“已探索且射程内、不会被迫攻击无法攻击的文明”，
            // 不要求可见（与原生 WorldMapTileUpdater 的高亮一致），也不能再按单位视野过滤。
            unit.isNuclearWeapon() -> attackable
            // 空军目标由原生 TargetHelper 按空中射程与可攻击性给出（可含城市内飞机），只需再筛掉未可见地块。
            unit.isAirUnit() -> attackable.filter { it.getTileToAttack().isVisible() }
            else -> attackable.filter { visibleEnemy(unit, it.getTileToAttack()) != null }
        }
    }

    fun attackTargets(unit: MapUnitView): List<JsonObject> = candidates(unit)
        .map { it.getTileToAttack().position() }.distinct().map { it.dto() }

    /** 空军只读摘要：射程、转场航程、攻击次数、扫荡与拦截资格全部取自原生字段。 */
    fun airInfo(unit: MapUnitView): JsonObject {
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        val canSweep = raw.hasUnique(UniqueType.CanAirsweep)
        val interceptChance = raw.interceptChance()
        return dto(
            "basedAt" to unit.getTile().position().dto(),
            "basedInCity" to unit.getTile().isCityCenter(),
            "range" to unit.getRange(),
            "rebaseRange" to raw.getMaxMovementForAirUnits(),
            "attacksThisTurn" to raw.attacksThisTurn,
            "maxAttacks" to raw.maxAttacksPerTurn(),
            "canAttack" to unit.canAttack(),
            "canRebase" to (unit.hasMovement() && !unit.cannotMove()),
            "sweep" to dto("available" to canSweep, "active" to unit.isPreparingAirSweep(),
                "enabled" to (canSweep && unit.canAttack()), "range" to unit.getRange()),
            "intercept" to dto("available" to (interceptChance > 0), "enabled" to raw.canIntercept(),
                "range" to raw.getInterceptionRange(), "chance" to interceptChance)
        )
    }

    /**
     * 核武只读摘要：射程、爆炸半径、攻击次数、自毁与外交风险均取自原生字段。
     * 合法目标仍由 [attackTargets] 给出（原生 `Nuke.mayUseNuke`），前端不自行推算范围。
     */
    fun nukeInfo(unit: MapUnitView): JsonObject? {
        if (!unit.isNuclearWeapon()) return null
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        return dto(
            "range" to unit.getRange(),
            "blastRadius" to unit.getNukeBlastRadius(),
            "attacksThisTurn" to raw.attacksThisTurn,
            "maxAttacks" to raw.maxAttacksPerTurn(),
            "canAttack" to unit.canAttack(),
            "selfDestructs" to raw.hasUnique(UniqueType.SelfDestructs),
            "interceptable" to !raw.hasUnique(UniqueType.CannotBeIntercepted),
            // 原生 Nuke.NUKE 对每个已知文明施加使用核武器的外交惩罚；数量只用于风险提示。
            "knownCivs" to raw.civ.getKnownCivs().count { it != raw.civ }
        )
    }

    /**
     * 空军转场候选：与原生 [UnitMovement.getReachableTilesInCurrentTurn] 的空军分支一致，
     * 取航程内且 [MapUnitView.canMoveTo] 允许降落（己方有空位城市或可搭载的运输单位）的地块。
     */
    fun rebaseTargets(unit: MapUnitView): List<JsonObject> {
        // 核武同样以空军运行：搬运／布署就是转场到己方空位城市或可搭载运输单位，规则仍由原生 canMoveTo 判定。
        if (!unit.isAirUnit() || !unit.hasMovement() || unit.cannotMove()) return emptyList()
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        val origin = unit.getTile().position()
        return unit.getTile().getVisibleTilesInDistance(raw.getMaxMovementForAirUnits())
            .filter { it.position() != origin && it.isVisible() && unit.canMoveTo(it) }
            .map { it.position() }.distinct().map { it.dto() }.toList()
    }

    /** 空中扫荡候选：仅在原生“准备扫荡”状态下给出射程内可见地块，执行仍走原生 [MapUnitView.tryAirSweep]。 */
    fun sweepTargets(unit: MapUnitView): List<JsonObject> {
        if (!unit.isAirUnit() || unit.isNuclearWeapon() || !unit.isPreparingAirSweep() || !unit.canAttack()) return emptyList()
        return unit.getTilesInAttackRange().filter { it.isVisible() }
            .map { it.position() }.distinct().map { it.dto() }
    }

    /** 空袭防守方：空军的合法目标包含敌方城内飞机，不能复用陆海的“仅可俘获平民”过滤。 */
    private fun airDefender(tile: TileView): CombatantView? {
        if (!tile.isVisible()) return null
        val defender = tile.getCombatant() ?: return null
        val enemy = when (defender) {
            is CityCombatantView -> defender.getCityView().getCity().civ
            is MapUnitCombatantView -> defender.getUnitView().getUnit().civ
        }
        return defender.takeIf { player.isAtWarWith(enemy) }
    }

    fun prepareAttack(id: Int, target: HexCoord): PreparedAttack {
        val unit = ownedUnit(id)
        val rawTile = game.tileMap.getOrNull(target.x, target.y) ?: cannotAttack()
        val tile = view.getTile(rawTile)
        if (unit.isNuclearWeapon()) {
            // 核打击不需要目标地块上有可见敌人：合法性完全来自原生 mayUseNuke（已探索、射程、宣战可行性）。
            if (!unit.canAttack()) cannotAttack()
            val attack = candidates(unit).firstOrNull { it.getTileToAttack().position() == target } ?: cannotAttack()
            return PreparedAttack.nuke(unit, attack, tile)
        }
        if (unit.isAirUnit()) {
            // 准备扫荡时，射程内任意可见地块都是合法目标；拦截与伤害全由原生 AirInterception 结算。
            if (unit.isPreparingAirSweep()) {
                val inRange = unit.canAttack() && tile.isVisible() &&
                    unit.getTilesInAttackRange().any { it.position() == target }
                if (!inRange) cannotAttack()
                return PreparedAttack.sweep(unit, tile)
            }
            val attack = candidates(unit).firstOrNull { it.getTileToAttack().position() == target } ?: cannotAttack()
            return PreparedAttack(unit, attack, airDefender(tile) ?: cannotAttack())
        }
        if (!supportsBasicActions(unit)) throw GatewayError("UNSUPPORTED", combatReason(unit))
        val defender = visibleEnemy(unit, tile) ?: cannotAttack()
        val attack = candidates(unit).firstOrNull { it.getTileToAttack().position() == target } ?: cannotAttack()
        return PreparedAttack(unit, attack, defender)
    }

    /** 空降能力只读摘要：可用性、准备态与最大投送距离全部取自原生 unique 与缓存。 */
    fun paradropInfo(unit: MapUnitView): JsonObject? {
        if (unit.isAirUnit() || unit.isNuclearWeapon()) return null
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        // 原生动作工厂的副作用就是重建过滤器；没有 MayParadrop 时返回空序列。
        val action = UnitActions.getUnitActions(raw, UnitActionType.Paradrop).firstOrNull() ?: return null
        return dto("available" to true, "active" to unit.isPreparingParadrop(),
            "enabled" to (action.action != null),
            "range" to (raw.cache.paradropDestinationTileFilters.values.maxOrNull() ?: 0))
    }

    /**
     * 空降落点候选：只在准备态给出，完全复用原生 [UnitMovement.getReachableTilesInCurrentTurn]
     * 的空降分支（距离上限、可见性、可通行与 MayParadrop 过滤器），不在网关重算规则。
     */
    fun paradropTargets(unit: MapUnitView): List<JsonObject> {
        if (!unit.isPreparingParadrop() || unit.isAirUnit() || unit.isNuclearWeapon()) return emptyList()
        val raw = player.units.getCivUnits().first { it.id == unit.id }
        // 落点过滤器不随存档持久化；读档后的准备态必须先由原生工厂重建，否则原生可达计算无法工作。
        UnitActions.getUnitActions(raw, UnitActionType.Paradrop).toList()
        val origin = raw.currentTile.position
        return raw.movement.getReachableTilesInCurrentTurn()
            .filter { it.position != origin }
            .map { view.getTile(it).position() }.distinct().map { it.dto() }.toList()
    }

    /** 空降写入：合法落点由原生判定，移动本身交给原生 [MapUnitView.tryMoveToTile]；不做多回合追踪。 */
    fun prepareParadrop(id: Int, x: Int, y: Int): () -> Unit {
        ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        if (unit.isNuclearWeapon()) throw GatewayError("UNSUPPORTED", combatReason(unit))
        if (unit.isAirUnit()) throw GatewayError("UNSUPPORTED", "空军请使用转场")
        if (!unit.isPreparingParadrop()) throw GatewayError("UNIT_ACTION", "请先为空降单位开启落点选择")
        val raw = player.units.getCivUnits().first { it.id == id }
        // 读档后的过滤器同样需要重建，否则 canReachInCurrentTurn 会因空过滤器失败。
        UnitActions.getUnitActions(raw, UnitActionType.Paradrop).toList()
        val rawTile = game.tileMap.getOrNull(x, y) ?: throw GatewayError("TARGET", "目标不在地图中")
        val tile = view.getTile(rawTile)
        if (tile.position() == unit.getTile().position()) throw GatewayError("CANNOT_MOVE", "目标就是当前地块")
        if (!raw.movement.canReachInCurrentTurn(rawTile))
            throw GatewayError("CANNOT_MOVE", "该地块不在空降范围内，或不可见／不可降落")
        // 与世界界面一致：由原生把目标折叠为本回合落点；不相等说明只能先走一段陆路，空降命令拒绝。
        val destination = try {
            raw.movement.getTileToMoveToThisTurn(rawTile)
        } catch (ex: Exception) {
            throw GatewayError("CANNOT_MOVE", "该地块当前不可达")
        }
        if (destination != rawTile) throw GatewayError("CANNOT_MOVE", "空降必须直接落在目标地块")
        val thunk: () -> Unit = {
            unit.tryMoveToTile(tile)
            if (unit.getTile().position() != tile.position()) throw GatewayError("MOVE_FAILED", "空降没有完成")
        }
        return thunk
    }

    /** 空军转场写入：只校验航程与降落资格，移动本身完全交给原生 [MapUnitView.tryMoveToTile]。 */
    fun prepareRebase(id: Int, x: Int, y: Int): () -> Unit {
        ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        if (!unit.isAirUnit()) throw GatewayError("UNSUPPORTED", "只有空军可以转场")
        val raw = player.units.getCivUnits().first { it.id == id }
        val rawTile = game.tileMap.getOrNull(x, y) ?: throw GatewayError("TARGET", "目标不在地图中")
        val tile = view.getTile(rawTile)
        if (tile.position() == unit.getTile().position()) throw GatewayError("CANNOT_MOVE", "目标就是当前地块")
        if (!unit.hasMovement() || unit.cannotMove()) throw GatewayError("CANNOT_MOVE", "该空军当前没有行动力转场")
        if (unit.getTile().aerialDistanceTo(tile) > raw.getMaxMovementForAirUnits())
            throw GatewayError("CANNOT_MOVE", "超出空军航程")
        if (!unit.canMoveTo(tile)) throw GatewayError("CANNOT_MOVE", "目标没有可降落的己方空位城市或运输单位")
        val thunk: () -> Unit = {
            unit.tryMoveToTile(tile)
            if (unit.getTile().position() != tile.position()) throw GatewayError("MOVE_FAILED", "转场没有完成")
        }
        return thunk
    }

    private fun cannotAttack(): Nothing = throw GatewayError("CANNOT_ATTACK", "当前不能攻击此目标")

    class PreparedAttack private constructor(
        private val unit: MapUnitView,
        private val target: AttackableTileView?,
        private val defender: CombatantView?,
        private val sweepTile: TileView?,
        private val nukeTile: TileView?
    ) {
        constructor(unit: MapUnitView, target: AttackableTileView, defender: CombatantView) :
            this(unit, target, defender, null, null)

        companion object {
            fun sweep(unit: MapUnitView, tile: TileView) = PreparedAttack(unit, null, null, tile, null)
            /** 核武目标可以没有任何可见防守方，因此不要求 defender。 */
            fun nuke(unit: MapUnitView, target: AttackableTileView, tile: TileView) =
                PreparedAttack(unit, target, null, null, tile)
        }

        fun preview(): JsonObject {
            val nuke = nukeTile
            if (nuke != null) return nukePreview(nuke)
            val sweep = sweepTile
            if (sweep != null) return sweepPreview(sweep)
            val target = requireTarget()
            val defender = requireDefender()
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
                "civilianOutcome" to (defender as? MapUnitCombatantView)?.getUnitView()?.let { captureOutcome(it) },
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

        /** 空中扫荡无防守方、无路径，伤害与拦截均为原生随机结算，预览只给出攻方与修正。 */
        private fun sweepPreview(tile: TileView): JsonObject {
            val attacker = unit.asCombatant()
            val modifiers = unit.getAirSweepAttackModifiers().map { dto("name" to it.key, "percent" to it.value) }
            return dto("unitId" to unit.id, "kind" to "airSweep",
                "target" to tile.position().dto(),
                "attackFrom" to unit.getTile().position().dto(), "path" to emptyList<JsonObject>(),
                "canCaptureCity" to false, "unknownOutcome" to true,
                "attacker" to dto("name" to attacker.getCombatantName(), "health" to attacker.getHealth(),
                    "maxHealth" to attacker.getMaxHealth(), "strength" to attacker.getAttackingStrength(),
                    "finalStrength" to attacker.getAttackingStrength(), "modifiers" to modifiers),
                "defender" to dto("name" to "未知目标", "health" to 0, "maxHealth" to 0, "strength" to 0,
                    "finalStrength" to 0, "modifiers" to emptyList<JsonObject>()),
                "damageToDefender" to dto("min" to 0, "max" to 0),
                "damageToAttacker" to dto("min" to 0, "max" to 0))
        }

        /**
         * 核打击预览：与原生 `BattleTable.simulateNuke` 一致，只在已探索地块上枚举可见城市与单位，
         * 不泄漏隐藏单位；爆炸半径、自毁、拦截与外交风险取自原生字段，伤害与拦截结果不在预览结算。
         */
        private fun nukePreview(tile: TileView): JsonObject {
            val attacker = unit.asCombatant()
            val raw = unit.getUnit()
            val selfCiv = raw.civ
            val blastRadius = unit.getNukeBlastRadius()
            val blastTiles = tile.getVisibleTilesInDistance(blastRadius).toList()
            val civs = LinkedHashSet<String>()
            val cities = ArrayList<JsonObject>()
            val hitUnits = ArrayList<JsonObject>()
            for (blastTile in blastTiles) {
                when (val combatant = blastTile.getCombatant()) {
                    is CityCombatantView -> {
                        val city = combatant.getCityView().getCity()
                        if (city.civ != selfCiv) civs += city.civ.civName
                        cities += dto("name" to city.name, "civ" to city.civ.civName,
                            "position" to blastTile.position().dto(),
                            "population" to city.population.population, "health" to city.health)
                    }
                    is MapUnitCombatantView -> {
                        val targetUnit = combatant.getUnitView()
                        if (targetUnit.getUnit().civ != selfCiv) civs += targetUnit.getUnit().civ.civName
                        hitUnits += dto("name" to targetUnit.getUnit().name,
                            "civ" to targetUnit.getUnit().civ.civName,
                            "position" to blastTile.position().dto(),
                            "military" to targetUnit.isMilitary())
                    }
                    else -> {}
                }
            }
            val defender = tile.getCombatant()
            return dto("unitId" to unit.id, "kind" to "nuke",
                "target" to tile.position().dto(),
                "attackFrom" to unit.getTile().position().dto(), "path" to emptyList<JsonObject>(),
                "canCaptureCity" to false, "unknownOutcome" to true,
                "blastRadius" to blastRadius,
                "blast" to blastTiles.map { it.position().dto() },
                // 外交风险只列已可见／已探索的涉及文明，不把未接触文明提前暴露给玩家。
                "risk" to dto("civs" to civs.toList(), "cities" to cities, "units" to hitUnits,
                    "knownCivs" to raw.civ.getKnownCivs().count { it != raw.civ },
                    "selfDestructs" to raw.hasUnique(UniqueType.SelfDestructs),
                    "interceptable" to !raw.hasUnique(UniqueType.CannotBeIntercepted)),
                "attacker" to dto("name" to attacker.getCombatantName(), "health" to attacker.getHealth(),
                    "maxHealth" to attacker.getMaxHealth(), "strength" to attacker.getAttackingStrength(),
                    "finalStrength" to attacker.getAttackingStrength(), "modifiers" to emptyList<JsonObject>()),
                "defender" to dto("name" to (defender?.getCombatantName() ?: "无人目标"),
                    "health" to (defender?.getHealth() ?: 0), "maxHealth" to (defender?.getMaxHealth() ?: 0),
                    "strength" to 0, "finalStrength" to 0, "modifiers" to emptyList<JsonObject>()),
                "damageToDefender" to dto("min" to 0, "max" to 0),
                "damageToAttacker" to dto("min" to 0, "max" to 0))
        }

        fun execute(): JsonObject {
            val nuke = nukeTile
            if (nuke != null) {
                requireTarget()
                // 核打击只调原生 Nuke.NUKE：不应走 movePreparingAttack（目标可以没有任何防守方）。
                unit.tryNuke(nuke)
                return dto("outcome" to "nuked", "kind" to "nuke",
                    "damageToDefender" to 0, "damageToAttacker" to 0,
                    // 自毁或被拦截时单位会在原生结算中消失；不在网关补写。
                    "unitLost" to unit.hasDisappeared())
            }
            val sweep = sweepTile
            if (sweep != null) {
                unit.tryAirSweep(sweep)
                return dto("outcome" to "swept", "kind" to "airSweep",
                    "damageToDefender" to 0, "damageToAttacker" to 0)
            }
            val target = requireTarget()
            val defender = requireDefender()
            // false 也可能已移动或完成架设，属于成功提交，不能回滚或强行补攻击。
            if (!unit.tryMovePreparingAttack(target, tryHealPillage = false))
                return dto("outcome" to "movedOnly", "damageToDefender" to 0, "damageToAttacker" to 0)
            val civilianTarget = (defender as? MapUnitCombatantView)?.getUnitView()?.isCivilian() == true && !unit.asCombatant().isRanged()
            val damage = unit.attackOrNuke(target)
            return dto("outcome" to "attacked", "kind" to if (civilianTarget) "captureCivilian" else "combat",
                "civilianOutcome" to (defender as? MapUnitCombatantView)?.getUnitView()?.let { captureOutcome(it) },
                "damageToDefender" to damage.attackerDealt, "damageToAttacker" to damage.defenderDealt)
        }

        private fun requireTarget() = target ?: throw GatewayError("CANNOT_ATTACK", "当前不能攻击此目标")
        private fun requireDefender() = defender ?: throw GatewayError("CANNOT_ATTACK", "当前不能攻击此目标")
    }
}

/** 平民目标是否可被俘获：只用于预览措辞；实际俘获、转工、归还原主或销毁一律由原生 BattleUnitCapture 决定。 */
private fun captureOutcome(defender: ForeignMapUnitView): String =
    if (defender.getUnit().isNuclearWeapon() || defender.hasUnique(UniqueType.Uncapturable)) "destroy" else "capture"

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
    registry.effect("paradrop") { ctx ->
        val current = session.requireGame()
        GameSession.validateGame(current)
        CombatCommands(current).prepareParadrop(ctx.request.integer("unitId"),
            ctx.request.integer("x"), ctx.request.integer("y"))
    }
    registry.effect("airRebase") { ctx ->
        val current = session.requireGame()
        GameSession.validateGame(current)
        CombatCommands(current).prepareRebase(ctx.request.integer("unitId"),
            ctx.request.integer("x"), ctx.request.integer("y"))
    }
    registry.effect("unitAction") {
        GameSession.validateGame(session.requireGame())
        CombatCommands(session.requireGame()).prepareAction(it.request.integer("unitId"), it.request.text("type"))
    }
}
