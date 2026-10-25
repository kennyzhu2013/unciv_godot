package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import kotlinx.serialization.json.JsonObject

/**
 * 多回合移动与持续单位指令。
 * - `unitRoute` 只读预览：目的地、本回合详细行进路线、后续回合落点（原生估算）与预计回合。
 * - `moveTo` / `unitOrder` 写命令：复用原生确认移动流程与指令字段，绝不在适配层重写寻路或跨回合推进。
 * 保留 [GameSession] 旧 `path` / `move` 的单回合语义不动，避免破坏既有海军与基础移动调用。
 */
internal class UnitOrders(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val view = GameView(game, player)

    private fun ownedUnit(id: Int): MapUnitView = view.civView.getUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")

    private fun tile(x: Int, y: Int): TileView {
        val raw = game.tileMap.getOrNull(x, y) ?: throw GatewayError("TARGET", "目标不在地图中")
        return view.getTile(raw)
    }

    /** 可下令移动的单位：陆军、海军与合法登船；空军、空降、核武器尚未接入。 */
    private fun ensureOrderable(unit: MapUnitView) {
        ensure(!unit.isAirUnit() && !unit.isNuclearWeapon() && !unit.isPreparingParadrop(),
            "UNSUPPORTED", "空军、空降和核武器的持续移动尚未接入")
        ensure(unit.hasMovement() && !unit.cannotMove(), "CANNOT_MOVE", "该单位当前不能移动")
    }

    /**
     * 本回合落点：直接复用原生 [MapUnitView.getTileToMoveToThisTurn]。
     * 目标即当前格、无法抵达或本回合前进一步都失败时按明确失败处理，绝不伪报已抵达。
     */
    private fun landingThisTurn(unit: MapUnitView, target: TileView): TileView {
        ensure(target.position() != unit.getTile().position(), "CANNOT_MOVE", "目标就是当前地块")
        return try {
            unit.getTileToMoveToThisTurn(target)
        } catch (ex: UnitMovement.UnreachableDestinationException) {
            throw GatewayError("CANNOT_MOVE", "无法抵达该地块")
        }
    }

    /**
     * `unitRoute` 只读预览。后续回合落点取自原生 `getShortestPath` 的每回合端点（首元素为本回合落点），
     * 预计回合即原生 `turnsToGetThere = getShortestPath(target).size`，标明为估算，未在适配层二次寻路。
     * 未探索地块只回传坐标，不含地形、资源或隐藏单位。
     */
    fun route(id: Int, x: Int, y: Int): JsonObject {
        val unit = ownedUnit(id)
        val target = tile(x, y)
        ensureOrderable(unit)
        // 先取完整多回合路径（每回合一个端点），非空即等价于原生 canReach，避免重复寻路。
        val fullPath = unit.getShortestPath(target)
        ensure(fullPath.isNotEmpty(), "CANNOT_MOVE", "无法抵达该地块")
        val landing = landingThisTurn(unit, target)
        return dto(
            "unitId" to id,
            "destination" to target.position().dto(),
            "thisTurn" to unit.getPathToTile(landing).map { it.position().dto() },
            "future" to fullPath.drop(1).map { it.position().dto() },
            "turns" to fullPath.size,
            "arrivesThisTurn" to (landing.position() == target.position())
        )
    }

    /**
     * `moveTo` 写命令：复刻 `WorldMapHolder` 的确认移动流程——先移动到本回合落点，
     * 未抵达目的地时设置原生 `moveTo` 指令；后续行进仅由 `nextTurn` 与单位原生 `doAction` 推进，
     * 适配层不额外执行第二次自动移动。单回合可达时端状态与旧 `move` 完全一致（目标格、action=null）。
     */
    fun prepareMove(id: Int, x: Int, y: Int): () -> Unit {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        val target = tile(x, y)
        ensureOrderable(unit)
        val landing = landingThisTurn(unit, target)
        val from = unit.getTile().position()
        ensure(landing.position() != from, "CANNOT_MOVE", "本回合无法向该地块前进")
        val destination = target.position()
        val thunk: () -> Unit = {
            unit.tryMoveToTile(landing)
            if (unit.getTile().position() == from) throw GatewayError("MOVE_FAILED", "移动没有完成")
            if (unit.isExploring() || unit.isMoving()) unit.tryResetAction()
            if (unit.getTile().position() != destination) unit.trySetMoveToAction(target)
        }
        return thunk
    }

    /**
     * `unitOrder` 写命令：持续指令的启停。`stopMovement` 复用原生"停止移动"（清空 action）；
     * `explore`/`stopExploration`/`automateWorker`/`stopAutomation` 仅复用原生指令字段赋值与
     * [UnitAutomation] 公开内核调用，绝不重写探索寻路、工人选地或施工优先级。
     */
    fun prepareOrder(id: Int, type: String): () -> Unit {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        val unit = ownedUnit(id)
        return when (type) {
            "stopMovement" -> {
                ensure(unit.isMoving(), "UNIT_ACTION", "该单位没有正在进行的移动")
                val thunk: () -> Unit = { unit.tryResetAction() }
                thunk
            }
            "explore" -> {
                ensure(!unit.isAirUnit(), "UNSUPPORTED", "空军不支持自动探索")
                ensure(!unit.isExploring(), "UNIT_ACTION", "该单位已在自动探索")
                val native = unit.getUnit()
                val thunk: () -> Unit = {
                    native.action = UnitActionType.Explore.value
                    if (native.hasMovement()) UnitAutomation.automatedExplore(native)
                }
                thunk
            }
            "stopExploration" -> {
                ensure(unit.isExploring(), "UNIT_ACTION", "该单位没有正在进行的自动探索")
                val thunk: () -> Unit = { unit.tryResetAction() }
                thunk
            }
            "automateWorker" -> {
                ensure(isLandWorker(unit), "UNSUPPORTED", "仅陆地施工单位支持自动工人")
                ensure(!unit.isAutomated(), "UNIT_ACTION", "该单位已处于自动化")
                ensure(unit.hasMovement(), "CANNOT_MOVE", "该单位当前没有行动力，无法启动自动化")
                val native = unit.getUnit()
                val thunk: () -> Unit = {
                    native.automated = true
                    UnitAutomation.automateUnitMoves(native)
                }
                thunk
            }
            "stopAutomation" -> {
                ensure(unit.isAutomated(), "UNIT_ACTION", "该单位没有正在进行的自动化")
                val native = unit.getUnit()
                val thunk: () -> Unit = {
                    native.action = null
                    native.automated = false
                }
                thunk
            }
            else -> throw GatewayError("INVALID_ARGUMENT", "未知单位指令：$type")
        }
    }
    
    /** 陆地施工单位：平民 + 具备建造改良能力 + 陆地域；排除工作船（水上）、空军与军事托管。 */
    private fun isLandWorker(unit: MapUnitView): Boolean =
        unit.isCivilian() && unit.hasUnique(UniqueType.BuildImprovements) && unit.getBaseUnit().isLandUnit

    private fun ensure(condition: Boolean, code: String, message: String) {
        if (!condition) throw GatewayError(code, message)
    }
}

internal fun contributeUnitOrders(registry: CommandRegistry, session: GameSession) {
    registry.query("unitRoute") {
        val current = session.requireGame()
        GameSession.validateGame(current)
        UnitOrders(current).route(it.request.integer("unitId"), it.request.integer("x"), it.request.integer("y"))
    }
    registry.effect("moveTo") { ctx ->
        val current = session.requireGame()
        GameSession.validateGame(current)
        UnitOrders(current).prepareMove(ctx.request.integer("unitId"), ctx.request.integer("x"), ctx.request.integer("y"))
    }
    registry.effect("unitOrder") { ctx ->
        val current = session.requireGame()
        GameSession.validateGame(current)
        UnitOrders(current).prepareOrder(ctx.request.integer("unitId"), ctx.request.text("type"))
    }
}
