package com.unciv.godot

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.*
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal object InformationFixtures {
    val root get() = BattleFixtures.root
    fun file(name: String = "information", free: Boolean = false): File = BattleFixtures.file(name) { game ->
        val civ = game.currentPlayerCiv
        civ.policies.storedCulture = 1000
        civ.policies.shouldOpenPolicyPicker = true
        if (free) { civ.tech.freeTechs = 1; civ.policies.freePolicies = 1 }
        val city = civ.cities.first()
        val unit = civ.units.getCivUnits().first()
        civ.notifications = arrayListOf(
            Notification("城市通知：é 中文 长名称 " + "展示内容 ".repeat(12), emptyArray(),
                CityAction.withLocation(city), NotificationCategory.Cities),
            Notification("单位通知", emptyArray(), listOf(MapUnitAction(unit)), NotificationCategory.Units),
            Notification("科技通知", emptyArray(), listOf(TechAction("Mining")), NotificationCategory.General),
            Notification("政策通知", emptyArray(), listOf(PolicyAction("Tradition")), NotificationCategory.General),
            Notification("未知跳转内容", emptyArray(), listOf(LinkAction("https://example.invalid/private-target")), NotificationCategory.General)
        )
        civ.notificationsLog = arrayListOf(Civilization.NotificationsLog(-1).apply {
            notifications.add(Notification("历史通知", emptyArray(), listOf(LocationAction(city.location)), NotificationCategory.Cities))
        })
    }

    fun nativeStep(game: GameInfo, action: String) = BattleFixtures.native(game) {
        val civ = game.currentPlayerCiv
        when (action) {
            "research" -> if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology("Mining") else civ.tech.techsToResearch = arrayListOf("Mining")
            "policy" -> civ.policies.adopt(game.ruleset.policies["Tradition"]!!)
            "deferPolicy" -> GameView(game, civ).civView.tryDismissPolicyPicker()
        }
    }

    fun emit() {
        val scenarios = listOf(false, true).map { free ->
            val name = if (free) "information-free" else "information"
            val source = file(name, free)
            val native = UncivFiles.gameInfoFromString(source.readText())
            val states = linkedMapOf<String, Any?>("initial" to GameplayAssertions.gameplay(native))
            val raw = linkedMapOf<String, Any?>("initial" to UncivFiles.gameInfoToString(native, false))
            for (action in listOf("research", "policy", "deferPolicy")) {
                nativeStep(native, action)
                states[action] = GameplayAssertions.gameplay(native)
                raw[action] = UncivFiles.gameInfoToString(native, false)
            }
            dto("name" to name, "fixture" to source.absolutePath, "states" to dto(*states.toList().toTypedArray()),
                "raw" to dto(*raw.toList().toTypedArray()))
        }
        File(root, "godot/.local/tests/information-expected.json").writeText(dto("scenarios" to scenarios,
            "setFields" to GameplayAssertions.setFields.sorted(), "mapOfSetFields" to GameplayAssertions.mapOfSetFields.sorted()).toString())
    }
}

class EmpireInformationTest {
    private val queries = listOf("empireOverview", "notificationOptions", "researchOptions", "policyOptions")
    private fun loaded() = BattleFixtures.load(InformationFixtures.file("information-test"))
    private fun run(session: GameSession, action: String, vararg args: Pair<String, Any?>) = BattleFixtures.run(session, action, *args)
    private fun data(session: GameSession, action: String) = run(session, action)["data"]!!.jsonObject
    private fun state(game: GameInfo) = json().apply { setUsePrototypes(false) }.toJson(game)
    private fun entries(data: JsonObject) = data["entries"]!!.jsonArray.map { it.jsonObject }

    @Test fun noGameQueriesAndForgedViewerAreRejectedWithoutMutation() {
        val session = GameSession(BattleFixtures.root)
        for (action in queries) {
            assertEquals("NO_GAME", session.handle(BattleFixtures.request(session, action))["error"]!!.jsonObject.text("code"))
            assertEquals("INVALID_ARGUMENT", session.handle(BattleFixtures.request(session, action, "player" to "Greece"))["error"]!!.jsonObject.text("code"))
        }
        assertNull(session.game)
        assertEquals(0, session.revision)
    }

    @Test fun allInformationQueriesAreRepeatableAndStrictlyReadOnly() {
        val session = loaded()
        val game = session.game!!
        val before = state(game)
        val revision = session.revision
        for (action in queries) {
            val request = BattleFixtures.request(session, action)
            val first = session.handle(request)
            assertTrue(first.toString(), first.boolean("ok"))
            assertEquals(first, session.handle(request))
            assertEquals(before, state(game))
            assertSame(game, session.game)
            assertEquals(revision, session.revision)
        }
    }

    @Test fun overviewMatchesNativeStatsAndOnlyContainsOwnedAssets() {
        val session = loaded()
        val game = session.game!!
        val civ = game.currentPlayerCiv
        val overview = data(session, "empireOverview")
        assertEquals(civ.gold, overview.integer("gold"))
        assertEquals(civ.getHappiness(), overview.integer("happiness"))
        assertEquals(civ.stats.getUnitSupply(), overview.integer("unitSupply"))
        val rates = overview["rates"]!!.jsonObject
        for ((stat, amount) in civ.stats.statsForNextTurn)
            assertEquals(amount, rates[stat.name]?.jsonPrimitive?.float ?: 0f, 0f)
        val cities = overview["cities"]!!.jsonArray.map { it.jsonObject }
        assertEquals(civ.cities.map { it.id }.toSet(), cities.map { it.text("id") }.toSet())
        assertEquals(civ.units.getCivUnits().map { it.id }.toSet(), overview["units"]!!.jsonArray.map { it.jsonObject.integer("id") }.toSet())
        val resources = overview["resources"]!!.jsonArray.map { it.jsonObject }
        assertFalse(resources.any { it.text("name") == "Uranium" })
        for (resource in resources) assertEquals(civ.getResourceAmount(resource.text("name")), resource.integer("amount"))
        for (city in cities) assertEquals(civ.cities.single { it.id == city.text("id") }.population.population, city.integer("population"))
        assertFalse(overview.toString().contains("陆战测试城"))
    }

    @Test fun researchShowsAllPrerequisitesCostsProgressAndNativeDescriptions() {
        val session = loaded()
        val game = session.game!!
        val civ = game.currentPlayerCiv
        val options = data(session, "researchOptions")
        assertEquals(game.ruleset.technologies.size, entries(options).size)
        for (entry in entries(options)) {
            val name = entry.text("name")
            val tech = game.ruleset.technologies[name]!!
            assertEquals(civ.tech.canBeResearched(name), entry.boolean("enabled"))
            assertEquals(civ.tech.costOfTech(name), entry.integer("cost"))
            assertEquals(civ.tech.researchOfTech(name), entry.integer("progress"))
            assertEquals(tech.getDescription(civ), entry.text("description"))
            assertEquals(tech.prerequisites, entry["prerequisites"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
        assertTrue(entries(options).single { it.text("name") == "Mining" }.boolean("enabled"))
        assertFalse(entries(options).single { it.text("name") == "Future Tech" }.boolean("enabled"))
        assertTrue(entries(options).single { it.text("name") == "Agriculture" }.boolean("researched"))
    }

    @Test fun policyCostPrerequisitesEraAndAutomaticCompletionStayNative() {
        val session = loaded()
        val game = session.game!!
        val civ = game.currentPlayerCiv
        val options = data(session, "policyOptions")
        assertEquals(civ.policies.getCultureNeededForNextPolicy(), options.integer("cost"))
        assertEquals(game.ruleset.policies.size, entries(options).size)
        for (entry in entries(options)) {
            val policy = game.ruleset.policies[entry.text("name")]!!
            assertEquals(civ.policies.canAdoptPolicy() && civ.policies.isAdoptable(policy), entry.boolean("enabled"))
            assertEquals(policy.getDescription(), entry.text("description"))
            if (policy.policyBranchType == PolicyBranchType.BranchComplete) assertFalse(entry.boolean("enabled"))
        }
        civ.policies.storedCulture = 0
        assertTrue(entries(data(session, "policyOptions")).none { it.boolean("enabled") })
        civ.policies.freePolicies = 1
        assertTrue(entries(data(session, "policyOptions")).any { it.boolean("enabled") })
        assertFalse(data(session, "policyOptions").boolean("canDefer"))
    }

    @Test fun malformedOrUnavailableDecisionsNeverRebuildTheGame() {
        val session = loaded()
        val game = session.game!!
        val before = state(game)
        val revision = session.revision
        val bad = mutableListOf<JsonObject>()
        for (action in listOf("research", "policy")) {
            val base = BattleFixtures.request(session, action)
            bad += base
            for (name in listOf(JsonNull, JsonPrimitive(12), JsonPrimitive(false), JsonPrimitive(""), dto(), JsonArray(emptyList())))
                bad += JsonObject(base + ("name" to name))
        }
        bad += BattleFixtures.request(session, "research", "name" to "Future Tech")
        bad += BattleFixtures.request(session, "policy", "name" to "Tradition Complete")
        bad += BattleFixtures.request(session, "deferPolicy", "forged" to true)
        for (request in bad) {
            assertFalse(session.handle(request).boolean("ok"))
            assertSame(game, session.game)
            assertEquals(before, state(game))
            assertEquals(revision, session.revision)
        }
    }

    @Test fun decisionsFreeAwardsIdempotencyAndReloadMatchNative() {
        for (free in listOf(false, true)) {
            val source = InformationFixtures.file("information-differential-$free", free)
            val session = BattleFixtures.load(source)
            val expected = UncivFiles.gameInfoFromString(source.readText())
            for (action in listOf("research", "policy", "deferPolicy")) {
                val args = when (action) { "research" -> arrayOf("name" to "Mining"); "policy" -> arrayOf("name" to "Tradition"); else -> emptyArray() }
                val command = BattleFixtures.request(session, action, *args)
                val revision = session.revision
                val result = session.handle(command)
                assertTrue(result.toString(), result.boolean("ok"))
                assertEquals(result, session.handle(command))
                assertEquals(revision + 1, session.revision)
                InformationFixtures.nativeStep(expected, action)
                GameplayAssertions.assertGameplayEquals("$free / $action", expected, session.game!!)
            }
            val saved = run(session, "save", "name" to "information-diff-$free").text("savedPath")
            run(session, "load", "path" to saved)
            GameplayAssertions.assertGameplayEquals("信息决策原格式重载", UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(expected, true)), session.game!!)
        }
    }

    @Test fun staleDecisionAndCrossSessionQueriesAreRejected() {
        val session = loaded()
        val stale = BattleFixtures.request(session, "policy", "name" to "Tradition")
        run(session, "research", "name" to "Mining")
        assertEquals("STALE_STATE", session.handle(stale)["error"]!!.jsonObject.text("code"))
        val foreign = JsonObject(BattleFixtures.request(session, "empireOverview") + ("session" to JsonPrimitive("old")))
        assertEquals("SESSION", session.handle(foreign)["error"]!!.jsonObject.text("code"))
        assertFalse(session.game!!.currentPlayerCiv.policies.isAdopted("Tradition"))
    }

    @Test fun notificationTargetsRespectExplorationOwnershipAndUnsupportedActions() {
        val session = loaded()
        val game = session.game!!
        val civ = game.currentPlayerCiv
        val hidden = game.tileMap.tileList.first { !GameView(game, civ).getTile(it).isExplored() }
        val foreign = BattleFixtures.enemy(game).cities.first()
        civ.notifications.add(Notification("权限测试", emptyArray(), listOf(
            LocationAction(hidden.position), CityAction(foreign.location),
            MapUnitAction(HexCoord(-6, 0), 999999), LocationAction(HexCoord(0, 0))), NotificationCategory.General))
        val before = state(game)
        val result = data(session, "notificationOptions")
        val ownCity = civ.cities.first()
        assertEquals("夹具覆盖序列化会省略的零坐标", 0, ownCity.location.y)
        val cityTargets = entries(result).single { it.text("text").startsWith("城市通知") }["targets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("location", "city"), cityTargets.map { it.text("kind") })
        assertTrue(cityTargets.all { it.boolean("enabled") })
        assertEquals(ownCity.id, cityTargets.last().text("id"))
        val permission = entries(result).single { it.text("text") == "权限测试" }["targets"]!!.jsonArray.map { it.jsonObject }
        assertFalse(permission[0].boolean("enabled"))
        assertFalse(permission[0].containsKey("x"))
        assertFalse(permission[1].boolean("enabled"))
        assertEquals("location", permission[2].text("kind"))
        assertEquals("location", permission[3].text("kind"))
        assertEquals(0, permission[3].integer("x"))
        assertEquals(0, permission[3].integer("y"))
        assertFalse(result.toString().contains("private-target"))
        assertTrue(entries(result).any { !it.boolean("current") && it.text("text") == "历史通知" })
        assertEquals(before, state(game))
    }

    @Test fun zeroScienceAndRepeatedResearchAreRepresentedWithoutInvalidNumbers() {
        val session = loaded()
        val civ = session.game!!.currentPlayerCiv
        civ.stats.statsForNextTurn.science = 0f
        val mining = entries(data(session, "researchOptions")).single { it.text("name") == "Mining" }
        assertEquals(civ.tech.turnsToTech("Mining"), mining.text("turns"))
        BattleFixtures.native(session.game!!) {
            for (name in session.game!!.ruleset.technologies.keys) civ.tech.addTechnology(name)
        }
        val future = entries(data(session, "researchOptions")).single { it.text("name") == "Future Tech" }
        assertTrue(future.boolean("repeatable"))
        assertTrue(future.boolean("researched"))
        assertTrue(future.boolean("enabled"))
    }

    @Test fun emitsIndependentInformationSmokeFixtures() {
        InformationFixtures.emit()
    }
}
