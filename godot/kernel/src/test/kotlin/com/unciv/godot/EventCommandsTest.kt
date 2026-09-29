package com.unciv.godot

import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class EventCommandsTest {
    private fun cmd(game: GameInfo) = EventCommands(game, "s", 0)
    private fun token(game: GameInfo): String =
        cmd(game).options(dto())["decision"]!!.jsonObject.text("token")
    private fun alert(game: GameInfo) = game.currentPlayerCiv.popupAlerts.first()
    private fun assertFails(code: String, block: () -> Unit) {
        try { block(); fail("应抛出 $code") } catch (e: GatewayError) { assertEquals(code, e.code) }
    }

    @Test fun validEventExposesStructuredChoicesAndBlocksTurn() {
        val game = EventFixtures.game()
        EventFixtures.inject(game, EventFixtures.gainGoldEvent(), "TestEvent")
        val pending = cmd(game).pending(alert(game))!!
        assertEquals("event", pending.text("kind"))
        assertEquals("TestEvent", pending.text("eventName"))
        assertEquals("测试事件：选择一项", pending.text("message"))
        assertTrue(pending.boolean("supported"))
        assertFalse(pending.boolean("dismissable"))
        val choices = pending["choices"]!!.jsonArray
        assertEquals(2, choices.size)
        assertEquals("获得金币", choices[0].jsonObject.text("text"))
        assertEquals(0, choices[0].jsonObject.integer("index"))
        assertTrue(choices[0].jsonObject.boolean("enabled"))
        assertTrue(choices[0].jsonObject["detail"]!!.jsonArray.any { it.jsonPrimitive.content.contains("Gold") })
        // 快照层面：有效事件进入 pending（阻塞 nextTurn）
        assertTrue(PlayerSnapshot(game).pending().any { it.text("kind") == "event" })
    }

    @Test fun chooseTriggersNativeEffectAndMatchesNativeExpectation() {
        for (index in 0..1) {
            val game = EventFixtures.game()
            EventFixtures.inject(game, EventFixtures.gainGoldEvent(), "TestEvent")
            val expected = EventFixtures.nativeChoose(EventFixtures.copy(game), "TestEvent", EventFixtures.gainGoldEvent(), index)
            val ticket = token(game)
            cmd(game).prepare(dto("eventToken" to ticket, "index" to index)).invoke()
            assertGameplayEquals("事件选择 index=$index", expected, game)
            assertTrue(game.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event })
        }
    }

    @Test fun staleOrMismatchedTokenAndIndexRejectedWithoutSideEffect() {
        val game = EventFixtures.game()
        EventFixtures.inject(game, EventFixtures.gainGoldEvent(), "TestEvent")
        val gold = game.currentPlayerCiv.gold
        val ticket = token(game)
        assertFails("EVENT") { cmd(game).prepare(dto("eventToken" to "wrong", "index" to 0)) }
        assertFails("INVALID_ARGUMENT") { cmd(game).prepare(dto("eventToken" to ticket, "index" to 5)) }
        assertFails("INVALID_ARGUMENT") { cmd(game).prepare(dto("eventToken" to ticket, "index" to 0, "extra" to 1)) }
        // 票据与 revision 绑定：换 revision 后旧票据失效
        assertFails("EVENT") { EventCommands(game, "s", 1).prepare(dto("eventToken" to ticket, "index" to 0)) }
        assertEquals(gold, game.currentPlayerCiv.gold)
        assertTrue(game.currentPlayerCiv.popupAlerts.any { it.type == AlertType.Event })
    }

    @Test fun textOnlyEventIsDismissableWithoutSideEffect() {
        val game = EventFixtures.game()
        EventFixtures.inject(game, EventFixtures.textOnlyEvent(), "TextOnlyEvent")
        val pending = cmd(game).pending(alert(game))!!
        assertTrue(pending.boolean("dismissable"))
        assertEquals(0, pending["choices"]!!.jsonArray.size)
        val gold = game.currentPlayerCiv.gold
        cmd(game).prepare(dto("eventToken" to token(game))).invoke()
        assertEquals(gold, game.currentPlayerCiv.gold)
        assertTrue(game.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event })
    }

    @Test fun invalidEventsAreNonBlockingAndDrained() {
        // (a) 找不到事件定义
        val unknown = EventFixtures.game()
        EventFixtures.inject(unknown, null, "NoSuchEvent")
        assertNull(cmd(unknown).pending(alert(unknown)))
        assertTrue(PlayerSnapshot(unknown).pending().none { it.text("kind") == "event" })
        EventCommands(unknown).drainInvalidEventAlerts()
        assertTrue(unknown.currentPlayerCiv.popupAlerts.isEmpty())
        // (b) 选项被条件过滤殆尽：getMatchingChoices 返回 null
        val gated = EventFixtures.game()
        EventFixtures.inject(gated, EventFixtures.gatedEvent(), "GatedEvent")
        assertNull(cmd(gated).pending(alert(gated)))
        assertTrue(PlayerSnapshot(gated).pending().none { it.text("kind") == "event" })
        assertFails("EVENT_INVALID") { cmd(gated).prepare(dto("eventToken" to "x")) }
        EventCommands(gated).drainInvalidEventAlerts()
        assertTrue(gated.currentPlayerCiv.popupAlerts.isEmpty())
    }

    @Test fun drainStopsAtFirstValidOrNonEventAlert() {
        val game = EventFixtures.game()
        EventFixtures.native(game) {
            game.currentPlayerCiv.popupAlerts.clear()
            game.ruleset.events["TestEvent"] = EventFixtures.gainGoldEvent()
            game.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
            game.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Event, "TestEvent"))
        }
        EventCommands(game).drainInvalidEventAlerts()
        assertEquals(1, game.currentPlayerCiv.popupAlerts.size)
        assertEquals("TestEvent", game.currentPlayerCiv.popupAlerts.first().value)

        val other = EventFixtures.game()
        EventFixtures.native(other) {
            other.currentPlayerCiv.popupAlerts.clear()
            other.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.FirstContact, "Greece"))
            other.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent"))
        }
        EventCommands(other).drainInvalidEventAlerts()
        assertEquals(2, other.currentPlayerCiv.popupAlerts.size)
    }

    @Test fun sessionExecutesEventChooseAndBumpsRevision() {
        val fixture = EventFixtures.game()
        EventFixtures.native(fixture) { fixture.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Event, "TestEvent")) }
        val session = load(EventFixtures.export(fixture, "event-session"))
        val game = session.game!!
        // 载入后 ruleset 重建、不含合成事件；仅注入本局实例，不污染缓存
        EventFixtures.native(game) { game.ruleset.events["TestEvent"] = EventFixtures.gainGoldEvent() }
        assertTrue(PlayerSnapshot(game).pending().any { it.text("kind") == "event" })
        val decision = run(session, "eventOptions")["data"]!!.jsonObject["decision"]!!.jsonObject
        assertEquals("TestEvent", decision.text("eventName"))
        val revision = session.revision
        val gold = game.currentPlayerCiv.gold
        run(session, "eventChoose", "eventToken" to decision.text("token"), "index" to 0)
        assertEquals(revision + 1, session.revision)
        assertTrue(game.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event })
        assertTrue(game.currentPlayerCiv.gold > gold)
    }

    @Test fun nextTurnIsNotBlockedByInvalidEventAndDrainsIt() {
        val fixture = EventFixtures.game()
        EventFixtures.native(fixture) { fixture.currentPlayerCiv.popupAlerts.add(PopupAlert(AlertType.Event, "NoSuchEvent")) }
        val session = load(EventFixtures.export(fixture, "event-drain"))
        val game = session.game!!
        assertTrue(PlayerSnapshot(game).pending().none { it.text("kind") == "event" })
        assertTrue(game.currentPlayerCiv.popupAlerts.any { it.type == AlertType.Event && it.value == "NoSuchEvent" })
        val response = session.handle(request(session, "nextTurn"))
        if (response["ok"]!!.jsonPrimitive.boolean) {
            assertTrue(session.game!!.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event && it.value == "NoSuchEvent" })
        } else {
            val error = response["error"]!!.jsonObject
            assertEquals("PENDING_DECISION", error.text("code"))
            assertFalse(error.text("message").contains("NoSuchEvent"))
        }
    }

    @Test fun debugInjectEventIsGatedOffWithoutSmokeEnv() {
        // gradle 测试进程未设 UNCIV_SMOKE：门控命令等同不存在，且不注入任何事件。
        val fixture = EventFixtures.game()
        val session = load(EventFixtures.export(fixture, "event-gate"))
        val response = session.handle(request(session, "debugInjectEvent", "kind" to "gainGold"))
        assertFalse(response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("UNKNOWN_COMMAND", response["error"]!!.jsonObject.text("code"))
        assertTrue(session.game!!.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event })
    }

    @Test fun smokeInjectedEventFlowsThroughSessionCommands() {
        // 直接调用 injectSmokeEvent（绕过 env 门控）模拟冒烟注入，验证其经真实 session 命令产生原生效果。
        val fixture = EventFixtures.game()
        val session = load(EventFixtures.export(fixture, "event-smoke-inject"))
        val game = session.game!!
        EventFixtures.native(game) { EventCommands(game).injectSmokeEvent("gainGold") }
        assertTrue(PlayerSnapshot(game).pending().any { it.text("kind") == "event" && it.text("eventName") == "SmokeEvent" })
        val decision = run(session, "eventOptions")["data"]!!.jsonObject["decision"]!!.jsonObject
        assertEquals(2, decision["choices"]!!.jsonArray.size)
        val revision = session.revision
        val gold = game.currentPlayerCiv.gold
        run(session, "eventChoose", "eventToken" to decision.text("token"), "index" to 0)
        assertEquals(revision + 1, session.revision)
        assertTrue(game.currentPlayerCiv.gold >= gold + 10)
        assertTrue(game.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event })
    }

    @Test fun injectSmokeEventCoversTextOnlyInvalidAndUnknown() {
        val textOnly = EventFixtures.game()
        EventFixtures.native(textOnly) { EventCommands(textOnly).injectSmokeEvent("textOnly") }
        assertTrue(cmd(textOnly).pending(alert(textOnly))!!.boolean("dismissable"))

        val invalid = EventFixtures.game()
        EventFixtures.native(invalid) { EventCommands(invalid).injectSmokeEvent("invalid") }
        assertTrue(PlayerSnapshot(invalid).pending().none { it.text("kind") == "event" })
        EventCommands(invalid).drainInvalidEventAlerts()
        assertTrue(invalid.currentPlayerCiv.popupAlerts.none { it.type == AlertType.Event })

        val unknown = EventFixtures.game()
        assertFails("INVALID_ARGUMENT") { EventFixtures.native(unknown) { EventCommands(unknown).injectSmokeEvent("bogus") } }
    }
}
