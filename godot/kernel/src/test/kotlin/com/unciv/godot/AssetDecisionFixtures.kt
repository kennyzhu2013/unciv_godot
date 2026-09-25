package com.unciv.godot

import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import java.io.File

/** 场景生成调用原生俘获／交易／联姻；期望端独立镜像 AlertPopup，不调用生产适配器。 */
internal object AssetDecisionFixtures {
    val cases = linkedMapOf(
        "civilian-worker" to listOf("return", "keep"),
        "civilian-settler" to listOf("return", "keep"),
        "civilian-city-state" to listOf("return", "keep"),
        "traded" to listOf("liberate", "keep"),
        "marriage" to listOf("annex", "puppet"),
        "marriage-occ" to listOf("destroy"),
        "expired" to listOf("dismiss"))

    fun recapture(game: GameInfo, name: String = "Worker", owner: Civilization = DiplomacyFixtures.enemy(game)) = native(game) {
        val barbarians = game.civilizations.firstOrNull { it.isBarbarian } ?: Civilization(game.ruleset.nations["Barbarians"]!!).also {
            it.gameInfo = game
            game.civilizations.add(it)
            it.setTransients()
        }
        val unit = BattleFixtures.add(game, name, -3, -1, owner)
        unit.health = 63
        unit.capturedBy(barbarians)
        BattleUnitCapture.captureCivilianUnit(MapUnitCombatant(BattleFixtures.unit(game, "Warrior")), MapUnitCombatant(unit))
        check(game.currentPlayerCiv.popupAlerts.last().type == AlertType.RecapturedCivilian)
        unit
    }

    fun tradeCity(game: GameInfo) = native(game) {
        val founder = game.civilizations.firstOrNull { it.civName == "Egypt" }
            ?: DiplomacyFixtures.addCiv(game, "Egypt", -6, -6)
        val city = game.getCities().first { it.name == "陆战测试城" }
        city.foundingCivObject = founder
        val logic = TradeLogic(DiplomacyFixtures.enemy(game), game.currentPlayerCiv)
        logic.currentTrade.ourOffers.add(TradeOffer(city.id, TradeOfferType.City, duration = 0))
        logic.acceptTrade()
        check(game.currentPlayerCiv.popupAlerts.last().type == AlertType.CityTraded)
        city
    }

    fun marriage(game: GameInfo) = native(game) {
        val player = game.currentPlayerCiv
        val minor = DiplomacyFixtures.addCiv(game, "Geneva", -2, 5)
        val cities = minor.cities.toList()
        minor.getDiplomacyManager(player)!!.setInfluence(100f)
        player.getDiplomacyManager(minor)!!.removeFlag(DiplomacyFlags.MarriageCooldown)
        val nation = player.nation
        try {
            // 仅场景生成时提供原生联姻能力；实际待决存档无需再支付费用或满足购买资格。
            player.nation = game.ruleset.nations["Austria"]!!
            check(minor.cityStateFunctions.canBeMarriedBy(player))
            minor.cityStateFunctions.diplomaticMarriage(player)
        } finally { player.nation = nation }
        // 原客户端 CityStateDiplomacyTable 在联姻完成后逐城添加提示。
        cities.forEach { player.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, it.id)) }
        cities.first()
    }

    fun file(name: String): File = export(game(name), "test-$name")
    fun export(game: GameInfo, name: String): File = File(BattleFixtures.root, "godot/.local/tests/asset-$name.json").apply {
        parentFile.mkdirs()
        writeText(UncivFiles.gameInfoToString(game, true))
    }

    fun game(name: String): GameInfo = DiplomacyFixtures.game().also { game -> native(game) {
        when (name) {
            "civilian-worker" -> recapture(game)
            "civilian-settler" -> recapture(game, "Settler")
            "civilian-city-state" -> recapture(game, owner = DiplomacyFixtures.addCiv(game, "Geneva", -2, 5))
            "traded" -> tradeCity(game)
            "marriage", "marriage-occ" -> {
                marriage(game)
                if (name == "marriage-occ") game.gameParameters.oneCityChallenge = true
            }
            "expired" -> recapture(game).destroy()
            "queue" -> { recapture(game); tradeCity(game); marriage(game) }
            else -> error(name)
        }
        // 只在初始场景清理建关系产生的首次接触；不清除任何资产处置或中途待决。
        game.currentPlayerCiv.popupAlerts.removeAll { it.type == AlertType.FirstContact }
    } }

    fun decide(game: GameInfo, choice: String) = native(game) {
        val player = game.currentPlayerCiv
        val alert = player.popupAlerts.first()
        when (alert.type) {
            AlertType.RecapturedCivilian -> if (choice != "dismiss") {
                val tile = game.tileMap[HexCoord.fromString(alert.value)]
                val unit = tile.civilianUnit!!
                if (choice == "keep") BattleUnitCapture.captureOrConvertToWorker(unit, player)
                else {
                    check(choice == "return")
                    val original = unit.originalOwningCiv!!
                    val name = unit.baseUnit.name
                    unit.destroy()
                    val closest = original.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
                    if (closest != null) original.units.placeUnitNearTile(closest.location.toHexCoord(), name)
                    if (original.isCityState) original.getDiplomacyManagerOrMeet(player).addInfluence(45f)
                    else if (original.isMajorCiv()) original.getDiplomacyManagerOrMeet(player).setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
                    val actions = sequence {
                        yield(LocationAction(tile.position))
                        if (closest != null) yield(LocationAction(closest.location))
                        yield(DiplomacyAction(player))
                        yield(CivilopediaAction("Tutorial/Barbarians"))
                    }
                    original.addNotification("Your captured [${name}] has been returned by [${player.civName}]", actions,
                        NotificationCategory.Diplomacy, NotificationIcon.Trade, name, player.civName)
                }
            }
            AlertType.CityTraded -> if (choice == "liberate") game.getCities().first { it.id == alert.value }.liberateCity(player)
            AlertType.DiplomaticMarriage -> {
                val city = game.getCities().first { it.id == alert.value }
                when (choice) {
                    "annex" -> city.annexCity()
                    "puppet" -> { city.isPuppet = true; city.cityStats.update() }
                    "destroy" -> city.destroyCity(overrideSafeties = true)
                    else -> error(choice)
                }
            }
            else -> error(alert.type)
        }
        player.popupAlerts.remove(alert)
    }

    fun emit() {
        val states = linkedMapOf<String, Any?>()
        for ((name, choices) in cases) {
            val source = export(game(name), name)
            for (choice in choices) {
                val game = UncivFiles.gameInfoFromString(source.readText())
                states["$name-initial"] = GameplayAssertions.gameplay(game)
                decide(game, choice)
                states["$name-$choice"] = GameplayAssertions.gameplay(game)
                val reload = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(game, true))
                states["$name-$choice-reload"] = GameplayAssertions.gameplay(reload)
            }
        }
        val queue = UncivFiles.gameInfoFromString(export(game("queue"), "queue").readText())
        states["queue-initial"] = GameplayAssertions.gameplay(queue)
        for ((index, choice) in listOf("keep", "keep", "puppet").withIndex()) {
            decide(queue, choice)
            states["queue-${index + 1}"] = GameplayAssertions.gameplay(queue)
        }
        File(BattleFixtures.root, "godot/.local/tests/asset-expected.json").writeText(dto(
            "cases" to dto(*cases.toList().toTypedArray()), "states" to dto(*states.toList().toTypedArray()),
            "setFields" to GameplayAssertions.setFields.toList(),
            "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString())
    }
}
