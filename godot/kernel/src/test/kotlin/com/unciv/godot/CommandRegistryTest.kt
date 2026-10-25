package com.unciv.godot

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 注册表是唯一分发点：命令名、候选来源和是否就地备份都来自同一次登记。 */
class CommandRegistryTest {
    @Test fun duplicateNameFailsAtRegistration() {
        val registry = CommandRegistry()
        registry.query("path") { _ -> dto() }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            registry.query("path") { _ -> dto() }
        }
        assertTrue(failure.message!!.contains("path"))
    }

    @Test fun catalogIsTheOnlyDispatchTable() {
        val root = File(System.getProperty("java.io.tmpdir"), "unciv-command-registry")
        val session = GameSession(root)
        val catalog = session.catalog()

        assertFalse(catalog.containsKey("hello"))
        assertFalse(catalog.containsKey("snapshot"))

        val queries = catalog.filterValues { it is QueryCommand }.keys
        assertEquals(setOf(
            "victoryOptions", "eventOptions", "greatPersonOptions", "assetDecisionOptions", "diplomaticVoteOptions",
            "religionOptions", "combatPreview", "diplomacyOptions", "unitOptions", "cityOptions", "cityBombardPreview", "path", "unitRoute", "unitAbilityOptions", "newGameOptions", "listSaves",
            "empireOverview", "notificationOptions", "researchOptions", "policyOptions"),
            queries)

        fun effect(name: String) = catalog[name] as EffectCommand
        assertEquals(setOf("load", "demo", "newGame", "resume"), catalog.filterValues { it is EffectCommand && it.source == CandidateSource.LOADED }.keys)
        assertEquals(setOf("nextTurn"), catalog.filterValues { it is EffectCommand && it.source == CandidateSource.CLONE_TURN }.keys)
        assertEquals(setOf("load", "demo", "newGame", "resume", "save", "nextTurn"), catalog.filterValues { it is EffectCommand && !it.inPlace }.keys)
        assertEquals(CandidateSource.CURRENT, effect("save").source)
        assertEquals(CandidateSource.CURRENT, effect("acknowledge").source)
        assertTrue(effect("acknowledge").inPlace)
        assertTrue(effect("debugInjectEvent").inPlace)
        assertTrue(effect("move").inPlace)
        assertTrue(effect("attack").inPlace)
        // 海军复用原命令和同一胜利事务门禁，不引入平行协议。
        for (name in listOf("move", "attack", "unitAction", "cityDecision")) {
            assertEquals(CandidateSource.CURRENT, effect(name).source)
            assertTrue(effect(name).checksVictory)
            assertFalse(effect(name).allowedDuringVictory)
        }
        assertFalse(catalog.keys.any { it.startsWith("naval") || it.startsWith("embark") })
        // 单位成长、城市轰击、工人与多回合移动指令复用同一 CURRENT／inPlace 事务与写后胜利门禁，不引入平行协议。
        for (name in listOf("unitPromote", "unitUpgrade", "cityBombard", "workerOrder", "moveTo", "unitOrder", "unitAbility")) {
            assertEquals(CandidateSource.CURRENT, effect(name).source)
            assertTrue(effect(name).inPlace)
            assertTrue(effect(name).checksVictory)
            assertFalse(effect(name).allowedDuringVictory)
        }
        assertEquals(setOf("load", "demo", "newGame", "resume", "save", "diplomaticVoteAcknowledge", "victoryContinue"),
            catalog.filterValues { it is EffectCommand && it.allowedDuringVictory }.keys)
        assertEquals(setOf("save"), catalog.filterValues { it is EffectCommand && !it.checksVictory }.keys)

        val domainWires = CityStateWire.entries.map { it.wireName } +
            DiplomacyWire.entries.map { it.wireName } +
            ReligionWire.entries.map { it.wireName } +
            DiplomaticVoteWire.entries.map { it.wireName }
        assertEquals(domainWires.size, domainWires.toSet().size)
        for (name in domainWires) {
            val command = effect(name)
            assertEquals(CandidateSource.CURRENT, command.source)
            assertTrue(command.inPlace)
        }

        val response = session.handle(dto(
            "protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
            "requestId" to "registry-unknown", "action" to "notACommand"))
        assertEquals(false, response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("UNKNOWN_COMMAND", response["error"]!!.jsonObject.text("code"))
        assertEquals(0, session.revision)
    }
}
