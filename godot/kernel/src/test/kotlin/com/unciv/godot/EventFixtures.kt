package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import java.io.File

/**
 * 事件测试专用状态构造。基础规则集只含浮空教程事件、没有带选项的 Alert 事件，
 * 因此这里合成最小事件定义注入到「单个游戏实例」的 ruleset（ruleset 为每局独立实例，不污染缓存），
 * 期望状态一律由原生 EventChoice.triggerChoice 独立执行产出，不复用网关 DTO 构造器。
 */
internal object EventFixtures {
    val root get() = BattleFixtures.root
    fun <T> native(game: GameInfo, action: () -> T): T = BattleFixtures.native(game, action)
    fun copy(game: GameInfo): GameInfo = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(game, true))

    private fun choice(text: String, uniques: List<String>): EventChoice =
        EventChoice().apply { this.text = text; this.uniques = ArrayList(uniques) }

    /** 两个选项：一个触发原生「Gain [10] [Gold]」，一个无副作用；无可用性限制，总是可选。 */
    fun gainGoldEvent(): Event = Event().apply {
        name = "TestEvent"; text = "测试事件：选择一项"
        choices.add(choice("获得金币", listOf("Gain [10] [Gold]")))
        choices.add(choice("什么都不做", emptyList()))
    }

    /** 无选项的仅文本事件：getMatchingChoices 返回空列表（有效、可关闭）。 */
    fun textOnlyEvent(): Event = Event().apply { name = "TextOnlyEvent"; text = "仅文本事件" }

    /** 选项被条件过滤殆尽：getMatchingChoices 返回 null（无效，原生直接移除）。 */
    fun gatedEvent(): Event = Event().apply {
        name = "GatedEvent"; text = "门控事件"
        choices.add(choice("不可用选项", listOf("Only available <if tutorials are enabled>")))
    }

    /** 在内存游戏中注入合成事件定义并压入队首事件弹窗（复刻原生 popupAlerts 顺序约束：先清空再放目标）。 */
    fun inject(game: GameInfo, event: Event?, alertValue: String) = native(game) {
        game.currentPlayerCiv.popupAlerts.clear()
        game.currentPlayerCiv.notifications.clear()
        if (event != null) game.ruleset.events[event.name] = event
        game.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Event, alertValue))
    }

    /** 干净基础局：清空待决弹窗，避免其它 pending 干扰事件断言。 */
    fun game(): GameInfo = BattleFixtures.game().also { game ->
        native(game) {
            game.currentPlayerCiv.popupAlerts.clear()
            game.currentPlayerCiv.notifications.clear()
        }
    }

    fun export(game: GameInfo, name: String): File = File(root, "godot/.local/tests/$name.json").apply {
        parentFile.mkdirs(); writeText(UncivFiles.gameInfoToString(game, true))
    }

    /** 在原生副本上按与网关相同的入口独立执行选择，产出期望状态（不复用网关代码）。 */
    fun nativeChoose(expected: GameInfo, eventName: String, event: Event, index: Int): GameInfo {
        native(expected) {
            expected.ruleset.events[eventName] = event
            val alert = expected.currentPlayerCiv.popupAlerts.first()
            val choices = expected.ruleset.events[eventName]!!
                .getMatchingChoices(com.unciv.models.ruleset.unique.GameContext(expected.currentPlayerCiv))!!.toList()
            if (index >= 0) choices[index].triggerChoice(expected.currentPlayerCiv, null)
            expected.currentPlayerCiv.popupAlerts.remove(alert)
        }
        return expected
    }
}
