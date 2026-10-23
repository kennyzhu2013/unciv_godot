package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.enemy
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NavalCommandsTest {
    private fun load(game: GameInfo) = BattleFixtures.load(NavalFixtures.export(game, "test"))
    private fun state(game: GameInfo) = UncivFiles.gameInfoToString(game, false)
    private fun reject(session: GameSession, command: JsonObject, code: String) {
        val game = session.game!!
        val before = state(game)
        val revision = session.revision
        val result = session.handle(command)
        assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
        assertSame("拒绝不得触发回滚重建", game, session.game)
        assertEquals(before, state(game))
        assertEquals(revision, session.revision)
    }
    private fun compareAttack(initial: GameInfo, id: Int, x: Int, y: Int,
                              configure: (GameInfo) -> Unit = {}): Pair<GameSession, JsonObject> {
        val session = load(initial)
        val expected = NavalFixtures.copy(initial)
        for (game in listOf(expected, session.game!!)) native(game) { configure(game) }
        val before = state(session.game!!)
        val revision = session.revision
        val preview = run(session, "combatPreview", "unitId" to id, "x" to x, "y" to y)["data"]!!.jsonObject
        run(session, "unitOptions", "unitId" to id)
        assertEquals(before, state(session.game!!))
        assertEquals(revision, session.revision)
        native(expected) {
            val raw = NavalFixtures.unit(expected, id)
            val target = TargetHelper.getAttackableEnemies(raw, raw.movement.getDistanceToTiles())
                .first { it.tileToAttack.position == HexCoord(x, y) }
            val attacker = MapUnitCombatant(raw)
            val defender = Battle.getMapCombatantOfTile(target.tileToAttack)!!
            if (!defender.isCivilian() || attacker.isRanged()) {
                for ((key, factor) in listOf("min" to 0f, "max" to 1f)) {
                    assertEquals(BattleDamage.calculateDamageToDefender(attacker, defender, target.tileToAttackFrom, factor)
                        .coerceIn(0, defender.getHealth()), preview["damageToDefender"]!!.jsonObject.integer(key))
                    assertEquals(BattleDamage.calculateDamageToAttacker(attacker, defender, target.tileToAttackFrom, factor)
                        .coerceIn(0, attacker.getHealth()), preview["damageToAttacker"]!!.jsonObject.integer(key))
                }
            } else assertEquals("captureCivilian", preview.text("kind"))
        }
        val damage = NavalFixtures.attack(expected, id, x, y)
        VictoryFixtures.screenUpdate(expected)
        val command = request(session, "attack", "unitId" to id, "x" to x, "y" to y)
        val result = session.handle(command)
        assertTrue(result.toString(), result["ok"]!!.jsonPrimitive.boolean)
        assertEquals(damage.attackerDealt, result["battleResult"]!!.jsonObject.integer("damageToDefender"))
        assertEquals(damage.defenderDealt, result["battleResult"]!!.jsonObject.integer("damageToAttacker"))
        assertGameplayEquals("海战", expected, session.game!!)
        assertEquals(result, session.handle(command))
        assertEquals(revision + 1, session.revision)
        reject(session, JsonObject(command + ("x" to JsonPrimitive(x + 1))), "REQUEST_REUSED")
        run(session, "save", "name" to "naval-battle-roundtrip")
        val saved = state(session.game!!)
        run(session, "load", "path" to "${NavalFixtures.root}/godot/.local/saves/naval-battle-roundtrip.json")
        assertGameplayEquals("海战重载", NavalFixtures.copy(expected), session.game!!)
        assertTrue(saved.isNotEmpty())
        return session to preview
    }

    @Test fun seaMeleeRangedAndEmbarkedDefendersMatchNative() {
        for (rules in BaseRuleset.entries) for ((attacker, defender) in listOf(
            "Trireme" to "Trireme", "Frigate" to "Trireme", "Trireme" to "Warrior", "Frigate" to "Worker")) {
            val game = NavalFixtures.duel(attacker, defender, rules = rules)
            compareAttack(game, game.currentPlayerCiv.units.getCivUnits().first().id, 1, -1)
        }
        val killed = NavalFixtures.duel(health = 1)
        val (session, _) = compareAttack(killed, killed.currentPlayerCiv.units.getCivUnits().first().id, 1, -1)
        assertFalse(enemy(session.game!!).units.getCivUnits().any())
    }

    @Test fun seaCivilianCaptureAndLandCivilianBoundary() {
        val game = NavalFixtures.duel(defender = "Worker")
        val (session, preview) = compareAttack(game, game.currentPlayerCiv.units.getCivUnits().first().id, 1, -1)
        assertEquals("captureCivilian", preview.text("kind"))
        assertTrue(session.game!!.currentPlayerCiv.units.getCivUnits().any { it.name == "Worker" })
        val land = NavalFixtures.game().also { native(it) { add(it, "Warrior", 1, 0); add(it, "Worker", 2, 0, enemy(it)); NavalFixtures.ready(it) } }
        val landSession = load(land)
        reject(landSession, request(landSession, "combatPreview", "unitId" to land.currentPlayerCiv.units.getCivUnits().first().id,
            "x" to 2, "y" to 0), "CANNOT_ATTACK")
    }

    @Test fun amphibiousAttackAndRangedEmbarkRestrictionUseNativeCandidates() {
        for (name in listOf("Warrior", "Archer")) {
            val game = NavalFixtures.game().also { native(it) { add(it, name, 1, -1); add(it, "Warrior", 1, 0, enemy(it)); NavalFixtures.ready(it) } }
            val raw = game.currentPlayerCiv.units.getCivUnits().first()
            val candidates = native(game) { TargetHelper.getAttackableEnemies(raw, raw.movement.getDistanceToTiles()) }
            val session = load(game)
            if (candidates.any { it.tileToAttack.position == HexCoord(1, 0) }) compareAttack(game, raw.id, 1, 0)
            else reject(session, request(session, "attack", "unitId" to raw.id, "x" to 1, "y" to 0), "CANNOT_ATTACK")
        }
    }

    @Test fun coastalCaptureAndVictoryReuseExistingDecisions() {
        val game = NavalFixtures.conquest()
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val (session, preview) = compareAttack(game, id, -6, 0)
        assertTrue(preview["canCaptureCity"]!!.jsonPrimitive.boolean)
        val pending = PlayerSnapshot(session.game!!).pending().first { it.text("kind") == "cityCapture" }
        var expected = NavalFixtures.copy(session.game!!)
        val params = dto("cityId" to pending.text("target"), "choice" to "puppet")
        expected = NavalFixtures.apply(expected, "cityDecision", params)
        run(session, "cityDecision", "cityId" to pending.text("target"), "choice" to "puppet")
        assertGameplayEquals("海军占城处置", expected, session.game!!)
        assertNotNull(session.game!!.victoryData)
        reject(session, request(session, "move", "unitId" to id, "x" to -6, "y" to -1), "PENDING_DECISION")
        val options = run(session, "victoryOptions")["data"]!!.jsonObject
        run(session, "victoryContinue", "resultToken" to options["continue"]!!.jsonObject.text("token"))
        expected = NavalFixtures.apply(expected, "victoryContinue")
        assertGameplayEquals("海军征服后继续", expected, session.game!!)
        assertEquals(com.unciv.logic.civilization.AlertType.Defeated, expected.currentPlayerCiv.popupAlerts.first().type)
        reject(session, request(session, "nextTurn"), "PENDING_DECISION")
        expected = NavalFixtures.apply(expected, "acknowledge")
        run(session, "acknowledge")
        assertGameplayEquals("海军征服后确认消息", expected, session.game!!)
        expected = NavalFixtures.apply(expected, "nextTurn")
        run(session, "nextTurn")
        assertGameplayEquals("海军征服后回合", expected, session.game!!)
    }

    @Test fun navalAndEmbarkedBasicActionsRemainNative() {
        for (name in listOf("Trireme", "Worker", "Warrior")) {
            val game = NavalFixtures.game().also { native(it) { add(it, name, 1, -1); NavalFixtures.ready(it) } }
            val session = load(game)
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            val options = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject
            assertTrue(options["actions"]!!.jsonArray.any { it.jsonObject.text("id") == "skip" })
            val expected = NavalFixtures.apply(NavalFixtures.copy(game), "unitAction", dto("unitId" to id, "type" to "skip"))
            run(session, "unitAction", "unitId" to id, "type" to "skip")
            assertGameplayEquals("水上基础动作 $name", expected, session.game!!)
        }
    }

    @Test fun embarkDisembarkAndOceanMovementMatchNative() {
        for (rules in BaseRuleset.entries) for (name in listOf("Warrior", "Worker")) {
            val game = NavalFixtures.game(rules, "Astronomy").also { native(it) { add(it, name, 2, 0); NavalFixtures.ready(it) } }
            val session = load(game)
            var expected = NavalFixtures.copy(game)
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            for ((x, y) in listOf(2 to -1, 2 to -2, 2 to -1, 2 to 0)) {
                if (!NavalFixtures.unit(session.game!!, id).movement.canReachInCurrentTurn(session.game!!.tileMap[x, y])) {
                    expected = NavalFixtures.apply(expected, "nextTurn")
                    run(session, "nextTurn")
                    assertGameplayEquals("登船回合", expected, session.game!!)
                }
                val before = state(session.game!!)
                run(session, "path", "unitId" to id, "x" to x, "y" to y)
                assertEquals(before, state(session.game!!))
                expected = NavalFixtures.apply(expected, "move", dto("unitId" to id, "x" to x, "y" to y))
                run(session, "move", "unitId" to id, "x" to x, "y" to y)
                assertGameplayEquals("登船移动 $name ($x,$y)", expected, session.game!!)
                val unitDto = PlayerSnapshot(session.game!!).build()["units"]!!.jsonArray.first { it.jsonObject.integer("id") == id }.jsonObject
                assertEquals("land", unitDto.text("domain"))
                assertEquals(y < 0, unitDto["embarked"]!!.jsonPrimitive.boolean)
            }
        }
    }

    @Test fun illegalMovesAreRejectedBeforeBackupAndDoNotAlterState() {
        for (tech in listOf(null, "Optics")) {
            val game = NavalFixtures.game(technology = tech).also { native(it) { add(it, "Warrior", 2, if (tech == null) 0 else -1); NavalFixtures.ready(it) } }
            val session = load(game)
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            val y = if (tech == null) -1 else -2
            for (action in listOf("path", "move")) reject(session, request(session, action, "unitId" to id, "x" to 2, "y" to y), "CANNOT_MOVE")
            reject(session, request(session, "move", "unitId" to -1, "x" to 0, "y" to 0), "NOT_OWNED")
            reject(session, request(session, "move", "unitId" to id, "x" to 999, "y" to 0), "TARGET")
            reject(session, request(session, "move", "unitId" to id, "x" to 2.5, "y" to 0), "INVALID_ARGUMENT")
        }
    }

    @Test fun seaMovementAndStackingUseNativeLegality() {
        val game = NavalFixtures.game().also { native(it) { add(it, "Trireme", 0, -1); add(it, "Trireme", 1, -1); NavalFixtures.ready(it) } }
        val session = load(game)
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        for ((x, y) in listOf(1 to -1, 1 to 0, 0 to -2))
            reject(session, request(session, "move", "unitId" to id, "x" to x, "y" to y), "CANNOT_MOVE")
        val expected = NavalFixtures.apply(NavalFixtures.copy(game), "move", dto("unitId" to id, "x" to -1, "y" to -1))
        run(session, "move", "unitId" to id, "x" to -1, "y" to -1)
        assertGameplayEquals("海军移动", expected, session.game!!)
    }

    @Test fun invisibleSubmarineAndFogNeverLeak() {
        val game = NavalFixtures.game().also { native(it) { add(it, "Frigate", 0, -1); add(it, "Submarine", 2, -1, enemy(it)); NavalFixtures.ready(it) } }
        val session = load(game)
        val own = session.game!!.currentPlayerCiv.units.getCivUnits().first()
        val target = enemy(session.game!!).units.getCivUnits().first()
        val options = run(session, "unitOptions", "unitId" to own.id)["data"]!!.jsonObject
        assertTrue(options["attackTargets"]!!.jsonArray.isEmpty())
        assertFalse(PlayerSnapshot(session.game!!).build()["units"]!!.jsonArray.any { it.jsonObject.integer("id") == target.id })
        reject(session, request(session, "combatPreview", "unitId" to own.id, "x" to 2, "y" to -1), "CANNOT_ATTACK")
        val visible = NavalFixtures.duel()
        val visibleSession = load(visible)
        val raw = visibleSession.game!!
        val ship = raw.currentPlayerCiv.units.getCivUnits().first()
        raw.currentPlayerCiv.viewableTiles = raw.currentPlayerCiv.viewableTiles - raw.tileMap[1, -1]
        reject(visibleSession, request(visibleSession, "attack", "unitId" to ship.id, "x" to 1, "y" to -1), "CANNOT_ATTACK")
    }

    @Test fun airAndNuclearActiveOperationsStayUnsupported() {
        for (name in listOf("Fighter", "Atomic Bomb")) {
            val game = NavalFixtures.game().also { native(it) { add(it, name, 0, 0); NavalFixtures.ready(it) } }
            val session = load(game)
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            for (action in listOf("path", "move", "attack", "combatPreview"))
                reject(session, request(session, action, "unitId" to id, "x" to 1, "y" to -1), "UNSUPPORTED")
            assertFalse(run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject["combatSupported"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun coastalProductionAndDepartureMatchNative() {
        for (rules in BaseRuleset.entries) {
            val game = NavalFixtures.game(rules)
            val city = game.currentPlayerCiv.cities.first()
            assertTrue(city.isCoastal())
            val session = load(game)
            var expected = NavalFixtures.copy(game)
            native(expected) {
                val raw = expected.currentPlayerCiv.cities.first()
                raw.cityConstructions.addToQueue("Trireme")
                raw.reassignPopulation()
                raw.cityStats.update()
            }
            VictoryFixtures.screenUpdate(expected)
            run(session, "cityQueue", "cityId" to city.id, "type" to "add", "name" to "Trireme")
            assertGameplayEquals("沿海舰船生产入队", expected, session.game!!)
            var turns = 0
            while (session.game!!.currentPlayerCiv.units.getCivUnits().none() && turns++ < 40) {
                expected = NavalFixtures.apply(expected, "nextTurn")
                run(session, "nextTurn")
                assertGameplayEquals("舰船生产回合 $turns", expected, session.game!!)
            }
            val ship = session.game!!.currentPlayerCiv.units.getCivUnits().first()
            assertEquals("Trireme", ship.name)
            val params = dto("unitId" to ship.id, "x" to 0, "y" to -1)
            expected = NavalFixtures.apply(expected, "move", params)
            run(session, "move", "unitId" to ship.id, "x" to 0, "y" to -1)
            assertGameplayEquals("舰船出港", expected, session.game!!)
        }
    }

    @Test fun sleepAndHealAtSeaSurviveNextTurn() {
        for (name in listOf("Trireme", "Worker", "Warrior")) for (action in listOf("sleep", "sleepUntilHealed")) {
            val game = NavalFixtures.game().also { native(it) { add(it, name, 0, -1).health = 60; NavalFixtures.ready(it) } }
            val session = load(game)
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            val type = if (action == "sleep") UnitActionType.Sleep else UnitActionType.SleepUntilHealed
            val enabled = native(game) { UnitActions.getBasicActions(NavalFixtures.unit(game, id)).first { it.type == type }.action != null }
            assertEquals("原生登船单位不能原地治疗：$name $action", action == "sleep" || name == "Trireme", enabled)
            val option = run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject["actions"]!!.jsonArray
                .first { it.jsonObject.text("id") == action }.jsonObject
            assertEquals(enabled, option["enabled"]!!.jsonPrimitive.boolean)
            if (!enabled) {
                reject(session, request(session, "unitAction", "unitId" to id, "type" to action), "UNIT_ACTION")
                continue
            }
            var expected = NavalFixtures.apply(NavalFixtures.copy(game), "unitAction", dto("unitId" to id, "type" to action))
            run(session, "unitAction", "unitId" to id, "type" to action)
            assertGameplayEquals("海上 $name $action", expected, session.game!!)
            expected = NavalFixtures.apply(expected, "nextTurn")
            run(session, "nextTurn")
            assertGameplayEquals("海上休眠跨回合", expected, session.game!!)
        }
    }

    @Test fun detectedSubmarineIsAttackableButPrivateFieldsStayHidden() {
        val game = NavalFixtures.duel("Destroyer", "Submarine")
        val session = load(game)
        val defender = enemy(game).units.getCivUnits().first()
        val visible = PlayerSnapshot(session.game!!).build()["units"]!!.jsonArray.map { it.jsonObject }.first { it.integer("id") == defender.id }
        assertEquals("water", visible.text("domain"))
        for (field in listOf("movement", "action", "due", "attacksThisTurn")) assertEquals(JsonNull, visible[field])
        compareAttack(game, game.currentPlayerCiv.units.getCivUnits().first().id, 1, -1)
    }

    @Test fun peaceForeignOwnershipAndStaleRevisionRejectWithoutWrites() {
        val game = NavalFixtures.duel()
        val session = load(game)
        val own = session.game!!.currentPlayerCiv.units.getCivUnits().first()
        val foreign = enemy(session.game!!).units.getCivUnits().first()
        for (action in listOf("move", "path", "attack", "combatPreview", "unitOptions"))
            reject(session, request(session, action, "unitId" to foreign.id, "x" to 1, "y" to -1), "NOT_OWNED")
        for (action in listOf("move", "attack")) reject(session,
            JsonObject(request(session, action, "unitId" to own.id, "x" to 1, "y" to -1) + ("revision" to JsonPrimitive(0))), "STALE_STATE")
        val current = session.game!!
        for (civ in listOf(current.currentPlayerCiv, enemy(current)))
            civ.getDiplomacyManager(if (civ == current.currentPlayerCiv) enemy(current) else current.currentPlayerCiv)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
        reject(session, request(session, "attack", "unitId" to own.id, "x" to 1, "y" to -1), "CANNOT_ATTACK")
    }

    @Test fun polynesianOceanExceptionUsesNativeMovement() {
        val game = NavalFixtures.game(technology = null, nation = "Polynesia").also { native(it) { add(it, "Worker", 2, 0); NavalFixtures.ready(it) } }
        val session = load(game)
        var expected = NavalFixtures.copy(game)
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        for (y in listOf(-1, -2)) {
            if (!NavalFixtures.unit(expected, id).movement.canReachInCurrentTurn(expected.tileMap[2, y])) {
                expected = NavalFixtures.apply(expected, "nextTurn")
                run(session, "nextTurn")
            }
            expected = NavalFixtures.apply(expected, "move", dto("unitId" to id, "x" to 2, "y" to y))
            run(session, "move", "unitId" to id, "x" to 2, "y" to y)
            assertGameplayEquals("无登船科技文明例外", expected, session.game!!)
        }
    }

    @Test fun rangedCoastalAttackCannotOccupyCity() {
        for (rules in BaseRuleset.entries) {
            val game = NavalFixtures.game(rules).also { native(it) { add(it, "Frigate", -6, -1); enemy(it).cities.first().health = 1; NavalFixtures.ready(it) } }
            val (session, preview) = compareAttack(game, game.currentPlayerCiv.units.getCivUnits().first().id, -6, 0)
            assertFalse(preview["canCaptureCity"]!!.jsonPrimitive.boolean)
            assertFalse(enemy(session.game!!).cities.first().hasJustBeenConquered)
        }
    }

    @Test fun recapturedEmbarkedCivilianReusesAssetDecision() {
        for (choice in listOf("keep", "return")) {
            val game = NavalFixtures.game().also { game -> native(game) {
                val player = game.currentPlayerCiv
                val original = enemy(game)
                player.getDiplomacyManager(original)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
                original.getDiplomacyManager(player)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
                val barb = com.unciv.logic.civilization.Civilization(game.ruleset.nations["Barbarians"]!!)
                barb.gameInfo = game
                game.civilizations.add(barb)
                barb.setTransients()
                NavalFixtures.research(game, "Optics", barb)
                add(game, "Trireme", 0, -1)
                add(game, "Worker", 1, -1, original).capturedBy(barb)
                NavalFixtures.ready(game)
            } }
            val (session, _) = compareAttack(game, game.currentPlayerCiv.units.getCivUnits().first().id, 1, -1)
            assertEquals(1, session.game!!.currentPlayerCiv.popupAlerts.count { it.type == com.unciv.logic.civilization.AlertType.RecapturedCivilian })
            val expected = NavalFixtures.copy(session.game!!)
            AssetDecisionFixtures.decide(expected, choice)
            VictoryFixtures.screenUpdate(expected)
            val decision = run(session, "assetDecisionOptions")["data"]!!.jsonObject["decision"]!!.jsonObject
            run(session, "assetDecision", "decisionToken" to decision.text("token"), "choice" to choice)
            assertGameplayEquals("海上平民处置 $choice", expected, session.game!!)
        }
    }

    @Test fun disembarkPreparationCanCommitMovementWithoutAttack() {
        val game = NavalFixtures.game().also { native(it) { add(it, "Warrior", 1, -1); add(it, "Warrior", 1, 1, enemy(it)); NavalFixtures.ready(it) } }
        val id = game.currentPlayerCiv.units.getCivUnits().first().id
        val (session, _) = compareAttack(game, id, 1, 1) { current ->
            val hill = current.tileMap[1, 0]
            hill.baseTerrain = "Hill"
            hill.setTerrainTransients()
            hill.setExplored(current.currentPlayerCiv, false)
            current.currentPlayerCiv.viewableTiles = current.currentPlayerCiv.viewableTiles - hill + current.tileMap[1, 1]
        }
        assertEquals(0, NavalFixtures.unit(session.game!!, id).attacksThisTurn)
        assertEquals(0f, NavalFixtures.unit(session.game!!, id).currentMovement)
    }

    @Test fun coastalUnitCombatWorksInBothDirections() {
        for (rules in BaseRuleset.entries) for (fromSea in listOf(true, false)) {
            val game = NavalFixtures.game(rules).also { game -> native(game) {
                add(game, if (fromSea) "Frigate" else "Archer", 2, if (fromSea) -1 else 0)
                add(game, if (fromSea) "Warrior" else "Trireme", 3, if (fromSea) 0 else -1, enemy(game))
                NavalFixtures.ready(game)
            } }
            compareAttack(game, game.currentPlayerCiv.units.getCivUnits().first().id, 3, if (fromSea) 0 else -1)
        }
    }

    @Test fun exhaustedSeaUnitsRejectMovementBeforeWrites() {
        for (name in listOf("Trireme", "Worker", "Warrior")) {
            val game = NavalFixtures.game().also { native(it) { add(it, name, 2, -1).currentMovement = 0f; NavalFixtures.ready(it) } }
            val session = load(game)
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            for (action in listOf("path", "move"))
                reject(session, request(session, action, "unitId" to id, "x" to 3, "y" to -1), "CANNOT_MOVE")
            assertTrue(run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject["reachable"]!!.jsonArray.isEmpty())
        }
    }

    @Test fun seaPassageRespectsNativeOpenBorders() {
        for (name in listOf("Trireme", "Worker", "Warrior")) for (open in listOf(false, true)) {
            val game = NavalFixtures.game().also { game -> native(game) {
                val player = game.currentPlayerCiv
                val other = enemy(game)
                player.getDiplomacyManager(other)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
                other.getDiplomacyManager(player)!!.diplomaticStatus = com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Peace
                if (open) com.unciv.logic.trade.TradeLogic(player, other).apply {
                    currentTrade.theirOffers.add(com.unciv.logic.trade.TradeOffer(com.unciv.Constants.openBorders,
                        com.unciv.logic.trade.TradeOfferType.Agreement, speed = game.speed))
                }.acceptTrade()
                add(game, name, -5, -1)
                NavalFixtures.ready(game)
            } }
            assertEquals(enemy(game), game.tileMap[-6, -1].getOwner())
            val id = game.currentPlayerCiv.units.getCivUnits().first().id
            assertEquals(open, native(game) { NavalFixtures.unit(game, id).movement.canMoveTo(game.tileMap[-6, -1]) })
            val session = load(game)
            if (!open) {
                reject(session, request(session, "move", "unitId" to id, "x" to -6, "y" to -1), "CANNOT_MOVE")
                continue
            }
            val expected = NavalFixtures.apply(NavalFixtures.copy(game), "move", dto("unitId" to id, "x" to -6, "y" to -1))
            run(session, "move", "unitId" to id, "x" to -6, "y" to -1)
            assertGameplayEquals("海上通行权 $name", expected, session.game!!)
        }
    }

    @Test fun emitIndependentNavalSmokeFixtures() {
        NavalFixtures.emit()
        val exported = Json.parseToJsonElement(java.io.File(NavalFixtures.root,
            "godot/.local/tests/naval-expected.json").readText()).jsonObject
        for (scenario in exported["scenarios"]!!.jsonArray.map { it.jsonObject }) {
            val steps = scenario["steps"]!!.jsonArray.map { it.jsonObject }
            println("海军窗口步骤 ${scenario.text("name")}: $steps")
            if (scenario.text("name") == "embarkation") assertEquals(
                listOf("move", "move", "nextTurn", "move", "move", "reload", "nextTurn"),
                steps.map { it.text("action") })
        }
        for (scenario in exported["scenarios"]!!.jsonArray.map { it.jsonObject }) {
            val steps = scenario["steps"]!!.jsonArray.map { it.jsonObject }
            val session = GameSession(NavalFixtures.root)
            run(session, "load", "path" to scenario.text("fixture"))
            for (step in steps) {
                if (step.text("action") == "reload") {
                    val saved = run(session, "save", "name" to "naval-export-replay")
                    run(session, "load", "path" to saved.text("savedPath"))
                } else if (step.text("action") == "victoryContinue") {
                    val options = run(session, "victoryOptions")["data"]!!.jsonObject
                    run(session, "victoryContinue", "resultToken" to options["continue"]!!.jsonObject.text("token"))
                } else run(session, step.text("action"), *step["params"]!!.jsonObject.toList().toTypedArray())
                assertEquals("导出窗口序列 ${scenario.text("name")}/${step.text("step")}",
                    scenario["states"]!!.jsonObject[step.text("step")], GameplayAssertions.gameplay(session.game!!))
            }
        }
    }
}
