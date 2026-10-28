package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 第 1 批待决队列收口：覆盖矩阵、队首遮蔽、失效清理与恢复路径。
 *
 * 矩阵说明（AlertType × 原生是否打开 × 网关入口 × 是否阻断）：
 * - CityConquered：原生总是打开（城市不存在时原生会抛错）；入口 cityDecision；阻断。
 * - CityTraded / DiplomaticMarriage / RecapturedCivilian：对象或原主失效时原生不打开；入口 assetDecision；阻断。
 * - Diplomacy 域 10 项：对方灭亡/已开战时原生不打开，其余打开；入口 diplomacyAlertDecision；阻断。
 * - Event：找不到定义或无满足条件选项时不打开；入口 eventOptions；有效时阻断。
 * - Informational 15 项：只有对方灭亡等少数分支不打开，其余为纯提示；入口 acknowledge；不阻断玩法。
 * 无法解析的异常对象继续显式报错，不提供“忽略所有事件”。
 */
internal class PendingQueueTest {
    private fun player(game: GameInfo) = game.currentPlayerCiv
    private fun enemyId(game: GameInfo) = BattleFixtures.enemy(game).civID

    @Test fun alertRoutesCoverEveryEnumExactlyOnce() {
        val routes = AlertType.entries.groupBy(::routeOf)
        assertEquals(AlertType.entries.toSet(), routes.values.flatten().toSet())
        assertEquals(setOf(AlertType.CityConquered), routes.getValue(AlertRoute.CityCapture).toSet())
        assertEquals(AssetDecisionCommands.alertTypes, routes.getValue(AlertRoute.AssetDecision).toSet())
        assertEquals(DiplomacyCommands.alertTypes, routes.getValue(AlertRoute.Diplomacy).toSet())
        assertEquals(GameSession.informationalAlerts, routes.getValue(AlertRoute.Informational).toSet())
        assertEquals(listOf(AlertType.Event), routes.getValue(AlertRoute.Event))
        // 每条路由都必须在快照中有可执行入口，不能只显示一串不能操作的待办。
        AlertRoute.entries.forEach { assertTrue("$it 缺少可执行入口", alertAction(it).isNotEmpty()) }
    }

    @Test fun invalidEventAtHeadDoesNotHideLaterAlert() {
        val game = EventFixtures.game()
        EventFixtures.native(game) {
            player(game).popupAlerts.clear()
            player(game).popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
            player(game).popupAlerts.add(PopupAlert(AlertType.FirstContact, enemyId(game)))
        }
        val before = player(game).popupAlerts.map { it.type to it.value }
        val snapshot = PlayerSnapshot(game)
        val alert = snapshot.pending().single { it.text("kind") == "alert" }
        // 查询只计算：不删除失效队首。
        assertEquals(before, player(game).popupAlerts.map { it.type to it.value })
        assertTrue(alert.text("message"), alert.text("message").contains("FirstContact"))
        assertEquals("acknowledge", alert.text("action"))
        assertTrue(alert.boolean("supported"))
        // 被跳过的失效事件单独呈现，供显式清理。
        val cleanup = snapshot.build()["pendingCleanup"]!!.jsonObject
        assertTrue(cleanup.boolean("available"))
        assertEquals(1, cleanup.integer("invalidEvents"))
        assertEquals(0, cleanup.integer("invalidTrades"))
        assertEquals(AlertType.FirstContact, PendingQueue(game).openableHead()!!.type)
        assertEquals(1, PendingQueue(game).invalidEventHead().size)
    }

    @Test fun cleanupOnlyRemovesLeadingInvalidEvents() {
        val game = EventFixtures.game()
        EventFixtures.native(game) {
            player(game).popupAlerts.clear()
            game.ruleset.events["TestEvent"] = EventFixtures.gainGoldEvent()
            player(game).popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
            player(game).popupAlerts.add(PopupAlert(AlertType.Event, "TestEvent"))
            player(game).popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
        }
        val queue = PendingQueue(game)
        assertEquals(1, queue.invalidEventHead().size)
        assertEquals("TestEvent", queue.openableHead()!!.value)
        assertEquals(1, queue.cleanup())
        // 有效事件之后的失效事件仍被保留：原生只逐帧处理队首。
        assertEquals(listOf("TestEvent", "NoSuchEvent"), player(game).popupAlerts.map { it.value })
        assertEquals(0, queue.invalidEventHead().size)
    }

    @Test fun cleanupRejectsOpenableHeadWithoutMutating() {
        val game = EventFixtures.game()
        EventFixtures.native(game) {
            player(game).popupAlerts.clear()
            player(game).popupAlerts.add(PopupAlert(AlertType.FirstContact, enemyId(game)))
        }
        val problem = PendingQueue.problem(game)!!
        assertEquals("NOTHING_TO_CLEAN", problem.code)
        assertFalse(PendingQueue(game).cleanupAvailable())
        assertEquals(1, player(game).popupAlerts.size)

        val session = load(EventFixtures.export(game, "pending-cleanup-rejected"))
        val revision = session.revision
        val response = session.handle(request(session, "pendingCleanup"))
        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("NOTHING_TO_CLEAN", response["error"]!!.jsonObject.text("code"))
        assertEquals(revision, session.revision)
        assertEquals(1, session.game!!.currentPlayerCiv.popupAlerts.size)
    }

    @Test fun cleanupUnblocksTurnAndAcknowledgeActsOnOpenableHead() {
        val fixture = EventFixtures.game()
        EventFixtures.native(fixture) {
            player(fixture).popupAlerts.clear()
            player(fixture).popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
            player(fixture).popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
            player(fixture).popupAlerts.add(PopupAlert(AlertType.FirstContact, enemyId(fixture)))
        }
        val session = load(EventFixtures.export(fixture, "pending-cleanup-unblock"))
        val game = session.game!!
        // 队首失效事件不应让有效提示隐形：结束回合必须被真实提示阻塞。
        val blocked = session.handle(request(session, "nextTurn"))
        assertFalse(blocked["ok"]!!.jsonPrimitive.boolean)
        assertEquals("PENDING_DECISION", blocked["error"]!!.jsonObject.text("code"))
        assertTrue(blocked["error"]!!.jsonObject.text("message").contains("FirstContact"))

        val revision = session.revision
        run(session, "pendingCleanup")
        assertEquals(revision + 1, session.revision)
        assertEquals(1, player(game).popupAlerts.size)
        assertEquals(AlertType.FirstContact, player(game).popupAlerts.single().type)

        run(session, "acknowledge")
        assertTrue(player(game).popupAlerts.isEmpty())
        val revisionAfterAck = session.revision
        run(session, "nextTurn")
        assertEquals(revisionAfterAck + 1, session.revision)
    }

    @Test fun invalidTradeIsCleanedOnlyAfterPopupsClearAndWithoutCooldown() {
        val game = DiplomacyFixtures.game(war = true)
        val me = player(game)
        val enemy = DiplomacyFixtures.enemy(game)
        DiplomacyFixtures.incoming(game, theirGold = 100)
        // 对方余额不足：原生 isTradeValid 判定失效，但提案仍在队列中。
        enemy.addGold(-enemy.gold)
        EventFixtures.native(game) { me.popupAlerts.add(PopupAlert(AlertType.FirstContact, enemy.civID)) }
        assertTrue(PendingQueue(game).invalidTrades().isEmpty())
        assertFalse(PendingQueue(game).cleanupAvailable())

        val session = load(EventFixtures.export(game, "pending-cleanup-trade"))
        run(session, "acknowledge")
        val loaded = session.game!!
        assertTrue(PendingQueue(loaded).cleanupAvailable())
        val notifications = DiplomacyFixtures.enemy(loaded).notifications.size
        val revision = session.revision

        run(session, "pendingCleanup")
        assertEquals(revision + 1, session.revision)
        assertTrue(loaded.currentPlayerCiv.tradeRequests.isEmpty())
        // 清理不产生任何拒绝通知或外交冷却；拒绝仍必须走 declineTrade。
        assertEquals(notifications, DiplomacyFixtures.enemy(loaded).notifications.size)
    }

    @Test fun cleanupSurvivesSaveAndReload() {
        val fixture = EventFixtures.game()
        EventFixtures.native(fixture) {
            player(fixture).popupAlerts.clear()
            player(fixture).popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
            player(fixture).popupAlerts.add(PopupAlert(AlertType.FirstContact, enemyId(fixture)))
        }
        val session = load(EventFixtures.export(fixture, "pending-cleanup-reload"))
        val savedPath = run(session, "save", "name" to "pending-cleanup-reload")["savedPath"]!!.jsonPrimitive.content
        run(session, "load", "path" to savedPath)
        // 失效队首与有效提示都随存档恢复，清理不能依赖内存中的临时状态。
        assertEquals(2, session.game!!.currentPlayerCiv.popupAlerts.size)
        run(session, "pendingCleanup")
        assertEquals(1, session.game!!.currentPlayerCiv.popupAlerts.size)
        assertEquals(AlertType.FirstContact, session.game!!.currentPlayerCiv.popupAlerts.single().type)
    }
}
