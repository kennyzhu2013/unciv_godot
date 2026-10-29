package com.unciv.godot

import com.unciv.godot.BattleFixtures.add
import com.unciv.godot.BattleFixtures.file
import com.unciv.godot.BattleFixtures.load
import com.unciv.godot.BattleFixtures.native
import com.unciv.godot.BattleFixtures.request
import com.unciv.godot.BattleFixtures.run
import com.unciv.godot.BattleFixtures.unit
import com.unciv.godot.GameplayAssertions.assertGameplayEquals
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 第 7 批 7C：核武搬运、布署、原生打击与风险预览的独立原生差分。
 * 期望端只调用 core 的 `Nuke.mayUseNuke`／`Nuke.NUKE`／`TargetHelper.getAttackableEnemies` 与
 * `UnitMovement.moveToTile`，不复用网关 DTO 自证。
 */
class NukeCommandsTest {
    /** 核弹与飞机一样停靠己方城市，因此夹具直接把它放在首都。 */
    private fun field(name: String, unitName: String, configure: (GameInfo) -> Unit = {}): File =
        file("nuke-$name") { game ->
            add(game, unitName, -6, 0)
            configure(game)
        }

    private fun options(session: GameSession, id: Int) =
        run(session, "unitOptions", "unitId" to id)["data"]!!.jsonObject

    /** 原生合法目标集合：只跑 TargetHelper，不经过网关的候选过滤。 */
    private fun nativeTargets(game: GameInfo, unitName: String): Set<Pair<Int, Int>> = native(game) {
        val raw = unit(game, unitName)
        TargetHelper.getAttackableEnemies(raw, raw.movement.getDistanceToTiles())
            .map { it.tileToAttack.position.x to it.tileToAttack.position.y }.toSet()
    }

    private fun targets(options: JsonObject): Set<Pair<Int, Int>> =
        options["attackTargets"]!!.jsonArray.map { it.jsonObject.integer("x") to it.jsonObject.integer("y") }.toSet()

    private fun rejected(session: GameSession, command: JsonObject, code: String = "CANNOT_ATTACK") {
        val game = session.game!!
        val before = UncivFiles.gameInfoToString(game, false)
        val revision = session.revision
        val result = session.handle(command)
        assertEquals(result.toString(), code, result["error"]!!.jsonObject.text("code"))
        assertEquals(revision, session.revision)
        assertSame("拒绝不应触发回滚重建", game, session.game)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
    }

    /**
     * 原子弹空爆落点：(-2,-6) 附近全为无主水域时不会留下 Fallout（`applyPillageAndFallout` 对水域直接返回），
     * 核爆内的单位伤害也只有地面零点与外圈 Strength 2 才固定，因此原子弹（Strength 1）需要在这里全量比较。
     */
    private val strikeAt = HexCoord(0, -4)

    /**
     * 把爆心半径 2 内全部改成无主水域并整图标记已探索：
     * 原子弹对非地面零点单位的伤害与 Fallout 都走状态随机，水域可让这次核击的整局差分完全可复现。
     */
    private fun crater(game: GameInfo) {
        val coast = game.ruleset.terrains["Coast"]!!
        for (tile in game.tileMap[strikeAt.x, strikeAt.y].getTilesInDistance(2)) {
            tile.setBaseTerrain(coast)
            tile.removeTerrainFeatures()
        }
        game.tileMap.tileList.forEach { it.setExplored(game.currentPlayerCiv, true) }
    }

    @Test fun atomicBombStrikeMatchesNative() {
        val fixture = field("strike", "Atomic Bomb") { game -> crater(game) }
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val bomb = unit(session.game!!, "Atomic Bomb")
        val id = bomb.id

        // 只读：摘要与目标候选都不改存档、不推进 revision。
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        val options = options(session, id)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertEquals(revision, session.revision)

        val nuke = options["nuke"]!!.jsonObject
        assertEquals(10, nuke.integer("range"))
        assertEquals(2, nuke.integer("blastRadius"))
        assertEquals(0, nuke.integer("attacksThisTurn"))
        assertEquals(1, nuke.integer("maxAttacks"))
        assertTrue(nuke["canAttack"]!!.jsonPrimitive.boolean)
        assertTrue("原子弹自毁", nuke["selfDestructs"]!!.jsonPrimitive.boolean)
        assertTrue("原子弹可被拦截", nuke["interceptable"]!!.jsonPrimitive.boolean)
        assertEquals(1, nuke.integer("knownCivs"))
        assertTrue("核武不使用空军投影", options["air"] is JsonNull)
        assertTrue("核武没有空军扫荡动作", options["actions"]!!.jsonArray.isEmpty())

        // 目标是已探索的空水域：不要求上面有可见敌人。
        assertEquals(native(expected) { nativeTargets(expected, "Atomic Bomb") }, targets(options))
        assertTrue("空水域也必须是合法目标", strikeAt.x to strikeAt.y in targets(options))

        val preview = run(session, "combatPreview", "unitId" to id, "x" to strikeAt.x, "y" to strikeAt.y)["data"]!!.jsonObject
        assertEquals("nuke", preview.text("kind"))
        assertTrue(preview["unknownOutcome"]!!.jsonPrimitive.boolean)
        assertFalse(preview["canCaptureCity"]!!.jsonPrimitive.boolean)
        assertEquals(2, preview.integer("blastRadius"))
        assertEquals("爆炸圈按已探索地块列出", 19, preview["blast"]!!.jsonArray.size)
        val risk = preview["risk"]!!.jsonObject
        assertTrue("无主水域没有外交风险", risk["civs"]!!.jsonArray.isEmpty())
        assertTrue(risk["cities"]!!.jsonArray.isEmpty())
        assertTrue(risk["units"]!!.jsonArray.isEmpty())
        assertEquals(1, risk.integer("knownCivs"))
        assertTrue(risk["selfDestructs"]!!.jsonPrimitive.boolean)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))

        native(expected) { Nuke.NUKE(MapUnitCombatant(unit(expected, "Atomic Bomb")), expected.tileMap[strikeAt.x, strikeAt.y]) }
        val response = session.handle(request(session, "attack", "unitId" to id, "x" to strikeAt.x, "y" to strikeAt.y))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        val result = response["battleResult"]!!.jsonObject
        assertEquals("nuked", result.text("outcome"))
        assertEquals("nuke", result.text("kind"))
        assertTrue("自毁后单位消失", result["unitLost"]!!.jsonPrimitive.boolean)
        assertGameplayEquals("原子弹空爆", expected, session.game!!)
        assertFalse("自毁核弹不再属于己方", session.game!!.currentPlayerCiv.units.getCivUnits().any { it.name == "Atomic Bomb" })
    }

    @Test fun nuclearMissileDestroysCityLikeNative() {
        // 核导弹消耗 2 铀：铀必须供得上，否则原生会把 100 点核爆伤害减半，圈内单位就不会阵亡。
        val outpost = HexCoord(1, 1)
        val fixture = field("city", "Nuclear Missile") { game ->
            DiplomacyFixtures.resource(game, game.currentPlayerCiv, "Uranium", 2)
            // 城边再放一支希腊陆军：它既在爆炸圈内，也在己方弓手的视野里，可被风险预览列出。
            add(game, "Warrior", outpost.x, outpost.y, BattleFixtures.enemy(game))
            game.tileMap.tileList.forEach { it.setExplored(game.currentPlayerCiv, true) }
        }
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val missile = unit(session.game!!, "Nuclear Missile")
        val city = HexCoord(1, 0)

        val options = options(session, missile.id)
        val nuke = options["nuke"]!!.jsonObject
        assertEquals(12, nuke.integer("range"))
        assertEquals(2, nuke.integer("blastRadius"))
        assertTrue(nuke["selfDestructs"]!!.jsonPrimitive.boolean)
        assertFalse("核导弹不可被拦截", nuke["interceptable"]!!.jsonPrimitive.boolean)
        assertEquals(native(expected) { nativeTargets(expected, "Nuclear Missile") }, targets(options))

        // 预览：城市、城内与爆炸圈内的敌方单位都要在风险清单里出现，伤害与拦截结果不在预览结算。
        val preview = run(session, "combatPreview", "unitId" to missile.id, "x" to city.x, "y" to city.y)["data"]!!.jsonObject
        assertEquals("nuke", preview.text("kind"))
        assertEquals(19, preview["blast"]!!.jsonArray.size)
        val risk = preview["risk"]!!.jsonObject
        assertEquals(listOf("Greece"), risk["civs"]!!.jsonArray.map { it.jsonPrimitive.content })
        val cityRisk = risk["cities"]!!.jsonArray.single().jsonObject
        assertEquals("陆战测试城", cityRisk.text("name"))
        assertEquals(3, cityRisk.integer("population"))
        assertTrue(risk["units"]!!.jsonArray.map { it.jsonObject.text("name") }.contains("Warrior"))
        assertTrue("爆炸圈内的己方单位也要列出", risk["units"]!!.jsonArray.map { it.jsonObject.text("name") }.contains("Archer"))
        val hitTiles = risk["units"]!!.jsonArray.map {
            val position = it.jsonObject["position"]!!.jsonObject
            position.integer("x") to position.integer("y")
        }
        assertTrue(hitTiles.toString(), outpost.x to outpost.y in hitTiles)
        assertEquals(0, preview["damageToDefender"]!!.jsonObject.integer("max"))

        native(expected) { Nuke.NUKE(MapUnitCombatant(unit(expected, "Nuclear Missile")), expected.tileMap[city.x, city.y]) }
        val response = session.handle(request(session, "attack", "unitId" to missile.id, "x" to city.x, "y" to city.y))
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        assertEquals("nuked", response["battleResult"]!!.jsonObject.text("outcome"))
        assertGameplayEquals("核导弹摧毁城市", expected, session.game!!)
        // Strength 2 对人口少于 5 的非首都城市直接摧毁；地面零点与外圈单位固定 100 伤害阵亡。
        assertNull("城市被摧毁", session.game!!.tileMap[city.x, city.y].getCity())
        assertEquals(1, BattleFixtures.enemy(session.game!!).cities.size)
        assertTrue("半径外的希腊陆军不受影响", session.game!!.tileMap[0, 2].getUnits().any())
        // 爆炸半径内的敌我单位（含 (1,1) 新加的希腊陆军）全部阵亡。
        assertFalse(session.game!!.tileMap[outpost.x, outpost.y].getUnits().any())
        assertFalse(session.game!!.tileMap[0, 1].getUnits().any())
        assertFalse(session.game!!.tileMap[0, 0].getUnits().any())
    }

    @Test fun illegalNukeTargetsLeaveStateUntouched() {
        val fixture = field("illegal", "Atomic Bomb") { game -> crater(game) }
        val session = load(fixture)
        val game = session.game!!
        val bomb = unit(game, "Atomic Bomb")

        // 目标就是发射井所在城市。
        rejected(session, request(session, "combatPreview", "unitId" to bomb.id, "x" to -6, "y" to 0))
        // 未探索地块：原生 mayUseNuke 拒绝，且不因“看不见”而泄漏情报。
        val target = game.tileMap[strikeAt.x, strikeAt.y]
        target.setExplored(game.currentPlayerCiv, false)
        rejected(session, request(session, "attack", "unitId" to bomb.id, "x" to strikeAt.x, "y" to strikeAt.y))
        target.setExplored(game.currentPlayerCiv, true)
        // 超出射程。
        val far = game.tileMap.tileList.first { bomb.currentTile.aerialDistanceTo(it) > bomb.getRange() }
        far.setExplored(game.currentPlayerCiv, true)
        rejected(session, request(session, "combatPreview", "unitId" to bomb.id,
            "x" to far.position.x, "y" to far.position.y))
        // 没有行动力。
        val movement = bomb.currentMovement
        bomb.currentMovement = 0f
        rejected(session, request(session, "attack", "unitId" to bomb.id, "x" to strikeAt.x, "y" to strikeAt.y))
        bomb.currentMovement = movement
        // 本回合攻击次数已用完。
        bomb.attacksThisTurn = 1
        rejected(session, request(session, "attack", "unitId" to bomb.id, "x" to strikeAt.x, "y" to strikeAt.y))
        bomb.attacksThisTurn = 0
        // 核武仍然只能由核打击路径提交；普通单位对核武目标没有候选。
        rejected(session, request(session, "combatPreview", "unitId" to unit(game, "Warrior").id,
            "x" to strikeAt.x, "y" to strikeAt.y))
    }

    @Test fun nuclearWeaponRebaseMatchesNative() {
        val fixture = field("rebase", "Atomic Bomb") { game ->
            game.currentPlayerCiv.addCity(HexCoord(0, 4)).name = "前进机场"
        }
        val session = load(fixture)
        val expected = UncivFiles.gameInfoFromString(fixture.readText())
        val id = native(expected) { unit(expected, "Atomic Bomb").id }

        // 搬运／布署复用空军转场：目标必须是航程内的己方空位城市（或可搭载运输单位）。
        val options = options(session, id)
        val bases = options["airBases"]!!.jsonArray.map {
            it.jsonObject.integer("x") to it.jsonObject.integer("y")
        }
        assertTrue(bases.toString(), HexCoord(0, 4).let { it.x to it.y } in bases)
        assertFalse("当前地块不是转场候选", HexCoord(-6, 0).let { it.x to it.y } in bases)
        assertTrue(options["nuke"] is JsonObject)

        native(expected) { unit(expected, "Atomic Bomb").movement.moveToTile(expected.tileMap[0, 4]) }
        run(session, "airRebase", "unitId" to id, "x" to 0, "y" to 4)
        assertGameplayEquals("核武布署", expected, session.game!!)
        val moved = unit(session.game!!, "Atomic Bomb")
        assertEquals(HexCoord(0, 4), moved.currentTile.position)
        assertEquals(0f, moved.currentMovement)
    }
}
