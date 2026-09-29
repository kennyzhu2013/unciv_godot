package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * 触发式事件（AlertType.Event）适配：value 解析与选项触发严格复刻 AlertPopup.addEvent()/RenderEvent，
 * 规则效果一律委托 EventChoice.triggerChoice，网关不复制 uniques 语义。
 * 无效事件（找不到定义或无满足条件的选项）按原生 AlertPopup 的行为处理——原生 shouldOpen==false 时直接
 * 从 popupAlerts 移除，因此这里不把它当作阻塞待决，并在回合推进后由 [drainInvalidEventAlerts] 清理。
 */
internal class EventCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv

    /** choices==null 表示无效事件（原生直接移除）；空列表表示仅文本事件，关闭即移除。 */
    private data class Resolved(val event: Event?, val unit: MapUnit?, val choices: List<EventChoice>?, val problem: String) {
        val invalid: Boolean get() = event == null || choices == null
    }

    /** 复刻 AlertPopup.addEvent：value 形如 "eventName" + (stringSplitCharacter + "unitId=1234")? */
    private fun resolve(alert: PopupAlert): Resolved {
        if (alert.type != AlertType.Event) return Resolved(null, null, null, "不是事件弹窗")
        val parts = alert.value.split(Constants.stringSplitCharacter)
        val eventName = parts[0]
        var unit: MapUnit? = null
        for (i in 1 until parts.size) {
            if (parts[i].startsWith("unitId=")) {
                val id = parts[i].substringAfter("unitId=").toIntOrNull()
                    ?: return Resolved(null, null, null, "事件单位引用格式无效")
                unit = player.units.getUnitById(id)
            }
        }
        val event = game.ruleset.events[eventName]
            ?: return Resolved(null, unit, null, "找不到事件定义：$eventName")
        // getMatchingChoices 返回 null 即原生 RenderEvent.isValid==false：不渲染弹窗、直接移除该 alert。
        val choices = event.getMatchingChoices(GameContext(player, unit = unit))
            ?: return Resolved(event, unit, null, "事件当前没有满足条件的选项")
        return Resolved(event, unit, choices.toList(), "")
    }

    private fun detail(choice: EventChoice): List<String> =
        choice.uniqueObjects.filter { it.isTriggerable || it.type == UniqueType.Comment }
            .filterNot { it.isHiddenToUsers() }.map { it.getDisplayText() }

    /** 除版本外还绑定事件定义与选项文本，防止事件变化后复用旧确认。 */
    private fun token(resolved: Resolved): String {
        val identity = dto("session" to session, "revision" to revision, "gameId" to game.gameId,
            "player" to player.civID, "event" to resolved.event?.name, "unitId" to resolved.unit?.id,
            "choices" to (resolved.choices?.mapIndexed { index, choice -> "$index:${choice.text}" }))
        return MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun decision(resolved: Resolved, withToken: Boolean): JsonObject {
        val choices = resolved.choices ?: emptyList()
        val event = resolved.event
        val fields = dto("kind" to "event", "supported" to true, "enabled" to true,
            "eventName" to (event?.name ?: ""),
            "message" to (event?.text?.takeIf { it.isNotEmpty() } ?: "触发事件：${event?.name ?: ""}"),
            "target" to (resolved.unit?.id?.toString() ?: ""),
            "choices" to choices.mapIndexed { index, choice ->
                dto("index" to index, "text" to choice.text, "enabled" to true, "detail" to detail(choice))
            },
            // 仅文本事件（无选项）：与原生 RenderEvent 一致，只显示正文，关闭即移除。
            "dismissable" to choices.isEmpty())
        return if (withToken) JsonObject(fields + ("token" to JsonPrimitive(token(resolved)))) else fields
    }

    /** 快照待决：有效事件返回结构化摘要（不含票据）；无效事件返回 null（不阻塞结束回合，由回合推进后清理）。 */
    fun pending(alert: PopupAlert): JsonObject? {
        val resolved = resolve(alert)
        if (resolved.invalid) return null
        return decision(resolved, false)
    }

    /** 打开事件对话框时按需取带票据的完整决策，供 eventChoose 提交时比对。 */
    fun options(request: JsonObject): JsonObject {
        checkKeys(request, emptySet())
        GameSession.validateGame(game)
        val resolved = player.popupAlerts.firstOrNull()?.takeIf { it.type == AlertType.Event }?.let { resolve(it) }
        return dto("decision" to resolved?.takeIf { !it.invalid }?.let { decision(it, true) })
    }

    fun prepare(request: JsonObject): () -> Unit {
        checkKeys(request, setOf("eventToken", "index"))
        val suppliedToken = text(request, "eventToken")
        GameSession.validateGame(game)
        val alert = player.popupAlerts.firstOrNull()?.takeIf { it.type == AlertType.Event }
            ?: throw GatewayError("EVENT", "没有对应的队首事件弹窗")
        val resolved = resolve(alert)
        if (resolved.invalid) throw GatewayError("EVENT_INVALID", "此事件已失效，将在结束回合后自动清理")
        if (suppliedToken != token(resolved)) throw GatewayError("EVENT", "事件已变化，请刷新后重试")
        val choices = resolved.choices!!
        // 仅文本事件没有可触发选项：关闭即移除（复刻 RenderEvent 无按钮 + AlertPopup.close）。
        val index = if (choices.isEmpty()) -1 else request.integer("index")
        if (index >= choices.size) throw GatewayError("INVALID_ARGUMENT", "事件选项序号超出范围")
        val unit = resolved.unit
        return {
            if (index >= 0) choices[index].triggerChoice(player, unit)
            player.popupAlerts.remove(alert)
        }
    }

    /**
     * 复刻 AlertPopup：shouldOpen==false（无效事件）时原生在 update 中直接移除队首 alert。
     * 仅清理队首连续的无效事件，遇到首个有效事件或非事件弹窗即停止（与原生逐帧只处理队首一致）。
     */
    fun drainInvalidEventAlerts() {
        while (true) {
            val alert = player.popupAlerts.firstOrNull() ?: return
            if (alert.type != AlertType.Event) return
            if (!resolve(alert).invalid) return
            player.popupAlerts.removeAt(0)
        }
    }

    /**
     * 仅供冒烟测试（由 GameSession 在 UNCIV_SMOKE 环境变量门控下调用）：向实时对局注入一个合成 Alert 事件。
     * 基础规则集不含 Alert 型事件、且 ruleset 不随存档序列化，故端到端前端验收只能由内核在实时会话中注入。
     * ruleset 为每局独立实例，注入不污染缓存；构造方式与 EventFixtures 一致（先清空 popupAlerts 再压入队首，
     * 复刻原生队首遮蔽约束）。kind：gainGold=两选项（0 触发原生 Gain [10] [Gold]）／textOnly=无选项可关闭／invalid=引用不存在事件。
     */
    fun injectSmokeEvent(kind: String) {
        val eventName = "SmokeEvent"
        player.popupAlerts.clear()
        when (kind) {
            "gainGold" -> {
                game.ruleset.events[eventName] = Event().apply {
                    name = eventName; text = "冒烟事件：选择一项"
                    choices.add(EventChoice().apply { text = "获得金币"; uniques = ArrayList(listOf("Gain [10] [Gold]")) })
                    choices.add(EventChoice().apply { text = "什么都不做"; uniques = ArrayList() })
                }
                player.popupAlerts.add(PopupAlert(AlertType.Event, eventName))
            }
            "textOnly" -> {
                game.ruleset.events[eventName] = Event().apply { name = eventName; text = "冒烟仅文本事件" }
                player.popupAlerts.add(PopupAlert(AlertType.Event, eventName))
            }
            "invalid" -> player.popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchSmokeEvent"))
            else -> throw GatewayError("INVALID_ARGUMENT", "未知冒烟事件类型：$kind")
        }
    }

    private fun checkKeys(request: JsonObject, keys: Set<String>) {
        if ((request.keys - envelope - keys).isNotEmpty()) throw GatewayError("INVALID_ARGUMENT", "含未支持的事件参数")
    }
    private fun text(request: JsonObject, key: String): String = (request[key] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        ?: throw GatewayError("INVALID_ARGUMENT", "缺少非空字符串参数或类型错误：$key")

    companion object {
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
    }
}
