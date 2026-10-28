package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeRequest
import kotlinx.serialization.json.JsonObject

/**
 * AlertType 的网关处置域。每个枚举必须落在唯一域里：新增类型时这里的 when 无法编译，
 * 不会静默落入“尚未接入”兜底。
 */
internal enum class AlertRoute { CityCapture, AssetDecision, Diplomacy, Event, Informational }

internal fun routeOf(type: AlertType): AlertRoute = when (type) {
    AlertType.CityConquered -> AlertRoute.CityCapture
    AlertType.CityTraded, AlertType.DiplomaticMarriage, AlertType.RecapturedCivilian -> AlertRoute.AssetDecision
    AlertType.DeclarationOfFriendship, AlertType.DemandToStopSettlingCitiesNear,
    AlertType.DemandToStopSpreadingReligion, AlertType.DemandToStopSpyingOnUs,
    AlertType.SpyingOnUsDespiteOurPromise, AlertType.DemandToNotAttackUs, AlertType.Denounced,
    AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> AlertRoute.Diplomacy
    AlertType.Event -> AlertRoute.Event
    AlertType.Defeated, AlertType.WonderBuilt, AlertType.TechResearched, AlertType.WarDeclaration,
    AlertType.FirstContact, AlertType.BorderConflict, AlertType.TilesStolen,
    AlertType.CitySettledNearOtherCivDespiteOurPromise, AlertType.ReligionSpreadDespiteOurPromise,
    AlertType.AttackedUsDespitePromise, AlertType.AcceptingDemand, AlertType.RejectingDemand,
    AlertType.GoldenAge, AlertType.StartIntro, AlertType.GameHasBeenWon -> AlertRoute.Informational
}

/** 每个路由在网关中只对应一个当前可执行入口；与 AlertRoute 同源，避免事项页出现无按钮的待办。 */
internal fun alertAction(route: AlertRoute): String = when (route) {
    AlertRoute.CityCapture -> "cityDecision"
    AlertRoute.AssetDecision -> "assetDecisionOptions"
    AlertRoute.Diplomacy -> "diplomacyAlertDecision"
    AlertRoute.Event -> "eventOptions"
    AlertRoute.Informational -> "acknowledge"
}

/**
 * 复刻 WorldScreen.update 的待决排队语义：只看队首，AlertPopup 构造时 shouldOpen==false 会被直接移除，
 * 弹窗全部清空后才检查 tradeRequests 的有效性。
 *
 * 查询只计算、不改状态；真正的移除必须经 pendingCleanup 效果事务提交，且不产生拒绝冷却或资产转移。
 */
internal class PendingQueue(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val events = EventCommands(game)

    /** 非事件弹窗由各自领域判断可用性，原生都会尝试打开。 */
    fun isOpenable(alert: PopupAlert): Boolean = alert.type != AlertType.Event || events.isOpenable(alert)

    /** 原生会打开的第一个弹窗；之前的连续失效事件由 [invalidEventHead] 描述。 */
    fun openableHead(): PopupAlert? = player.popupAlerts.firstOrNull(::isOpenable)

    /** 队首连续、原生不会打开的事件；遇到第一个会打开的弹窗或非事件即停止。 */
    fun invalidEventHead(): List<PopupAlert> {
        val head = ArrayList<PopupAlert>()
        for (alert in player.popupAlerts) {
            if (isOpenable(alert)) break
            head.add(alert)
        }
        return head
    }

    /** 原生在 popupAlerts 为空后才核对交易；无法解析的外交对象不在此处删除。 */
    fun invalidTrades(): List<TradeRequest> {
        if (player.popupAlerts.isNotEmpty()) return emptyList()
        return player.tradeRequests.filter(::verifiedInvalid)
    }

    private fun verifiedInvalid(request: TradeRequest): Boolean {
        val other = game.getCivilizationOrNull(request.requestingCiv) ?: return false
        return try {
            !TradeEvaluation().isTradeValid(request.trade, player, other)
        } catch (_: Exception) {
            // 原生在此同样会中断；不能凭异常删除玩家仍可见的提案。
            false
        }
    }

    fun cleanupAvailable(): Boolean = invalidEventHead().isNotEmpty() || invalidTrades().isNotEmpty()

    /** 与原生 update 的单帧行为一致：先清队首失效事件；只有弹窗清空后才清理失效交易。 */
    fun cleanup(): Int {
        val invalidEvents = invalidEventHead()
        invalidEvents.forEach { player.popupAlerts.remove(it) }
        if (player.popupAlerts.isNotEmpty()) return invalidEvents.size
        val invalidTrades = invalidTrades()
        invalidTrades.forEach { player.tradeRequests.remove(it) }
        return invalidEvents.size + invalidTrades.size
    }

    /** 快照摘要只描述可清理项；前端据此显示显式清理入口。 */
    fun cleanupSummary(): JsonObject {
        val invalidEvents = invalidEventHead().size
        val invalidTrades = invalidTrades().size
        return dto("available" to (invalidEvents + invalidTrades > 0),
            "invalidEvents" to invalidEvents, "invalidTrades" to invalidTrades,
            "reason" to when {
                invalidEvents > 0 -> "队首有 $invalidEvents 个原生不会打开的事件，可安全移除"
                invalidTrades > 0 -> "有 $invalidTrades 份已失效的交易提案，可安全清理"
                else -> "没有可安全清理的失效待决"
            })
    }

    companion object {
        /** 清理入口先在 prepare 里给出可预判的失败原因，避免无意义地复制对局。 */
        fun problem(game: GameInfo): GatewayError? {
            val queue = PendingQueue(game)
            if (queue.cleanupAvailable()) return null
            if (game.currentPlayerCiv.popupAlerts.isNotEmpty())
                return GatewayError("NOTHING_TO_CLEAN", "队首弹窗仍会正常打开，请用对应入口处理")
            if (game.currentPlayerCiv.tradeRequests.isNotEmpty()) {
                if (game.currentPlayerCiv.tradeRequests.any { game.getCivilizationOrNull(it.requestingCiv) == null })
                    return GatewayError("UNSUPPORTED", "存在无法解析的外交对象提案，请保存后用原客户端处理")
                return GatewayError("NOTHING_TO_CLEAN", "交易提案仍然有效，请接受或拒绝")
            }
            return GatewayError("NOTHING_TO_CLEAN", "没有待决事项")
        }
    }
}
