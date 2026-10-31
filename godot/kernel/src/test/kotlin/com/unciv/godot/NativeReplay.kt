package com.unciv.godot

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.WorkerImprovementActions
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import com.unciv.view.GameView
import kotlinx.serialization.json.*

/**
 * 第 8B 批完整长局的独立原生重放执行器：把网关命令参数施加到从持久化存档独立加载的原生
 * [GameInfo] 上，只调用 core 的原生入口（Battle／TargetHelper／UnitActions／WorkerImprovementActions／
 * 各 View 委托方法与既有测试夹具），不经过 [GameSession] 或任何网关命令域的执行代码。
 *
 * 每个分支与网关 execute 闭包内的原生调用逐一对应（含调用顺序）；出现未映射的命令立即抛
 * [AssertionError]，由完整长局测试按“对应批次未完成”处理，绝不允许静默跳过。
 */
internal class NativeReplay private constructor(var game: GameInfo) {

    companion object {
        /** 从存档文本独立建立重放端；与网关读到的同一份持久化状态互不共享对象。 */
        fun fromSaveText(text: String): NativeReplay =
            // gameInfoFromString 与网关 GameSaves.load 相同，内部已 setTransients()。
            NativeReplay(UncivFiles.gameInfoFromString(text))
    }

    private fun native(operation: () -> Unit) = BattleFixtures.native(game) { operation() }

    /** 施加一条已记录的命令，并在其后执行与网关一致的胜利检查时机。 */
    fun apply(action: String, args: JsonObject) {
        native { applyNative(action, args) }
        VictoryFixtures.screenUpdate(game)
    }

    private fun applyNative(action: String, args: JsonObject) {
        val player = game.currentPlayerCiv
        when (action) {
            "move" -> {
                val view = GameView(game, player)
                val unit = view.civView.getUnits().first { it.id == args.integer("unitId") }
                val tile = view.getTile(game.tileMap[HexCoord(args.integer("x"), args.integer("y"))])
                // 与 GameSession move 闭包一致：先清空持续指令，再原生寻路移动。
                unit.tryResetAction()
                check(unit.tryMoveToTile(tile)) { "原生重放移动失败" }
            }
            "foundCity" -> {
                val unit = player.units.getCivUnits().first { it.id == args.integer("unitId") }
                UnitActionsFromUniques.getFoundCityAction(unit, unit.currentTile) { _, commit -> commit() }
                    ?.action?.invoke() ?: error("原生重放不能建城")
            }
            "production" -> {
                val view = GameView(game, player)
                val city = view.civView.cities().first { it.id == args.text("cityId") }
                val name = args.text("name")
                val construction = game.ruleset.units[name] ?: game.ruleset.buildings[name] ?: error("未知生产项目 $name")
                val existing = city.constructions.constructionQueue.indexOf(name)
                if (existing >= 0) city.tryMoveEntryToTop(existing)
                else city.tryAddToQueueConstruction(construction, addToTop = true)
                city.tryReassignPopulation()
                city.updateCityStats()
            }
            "research" -> {
                val name = args.text("name")
                if (player.tech.freeTechs > 0) player.tech.getFreeTechnology(name)
                else player.tech.techsToResearch = arrayListOf(name)
            }
            "policy" -> player.policies.adopt(game.ruleset.policies[args.text("name")]!!)
            "deferPolicy" -> GameView(game, player).civView.tryDismissPolicyPicker()
            "acknowledge" -> {
                val alert = openableHead(player) ?: error("原生重放没有可打开的弹窗")
                player.popupAlerts.remove(alert)
            }
            "declineTrade" -> {
                // 与 TradePopup 的“Not this time.”一致（同 GameSessionTest.pairedCommand 镜像）。
                val tradeRequest = player.tradeRequests.first()
                val requestingCiv = game.getCivilization(tradeRequest.requestingCiv)
                tradeRequest.decline(player)
                player.tradeRequests.remove(tradeRequest)
                requestingCiv.addNotification("[${player.civName}] has denied your trade request",
                    NotificationCategory.Trade, player.civName, NotificationIcon.Trade)
            }
            "diplomacyDeclareWar" -> {
                val other = game.civilizations.first { it.civID == args.text("civId") }
                player.getDiplomacyManager(other)!!.declareWar()
            }
            "diplomacyAlertDecision" -> DiplomacyFixtures.alertDecision(game, args.text("choice"))
            "cityDecision" -> {
                val alert = player.popupAlerts.first { it.type == AlertType.CityConquered && it.value == args.text("cityId") }
                val city = game.getCities().first { it.id == alert.value && it.hasJustBeenConquered }
                when (args.text("choice")) {
                    "puppet" -> city.puppetCity(player)
                    "annex" -> { city.puppetCity(player); city.annexCity() }
                    "raze" -> {
                        city.puppetCity(player)
                        if (!player.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayNotAnnexCities)) city.annexCity()
                        city.isBeingRazed = true
                    }
                    "liberate" -> city.liberateCity(player)
                    else -> error("未知占城处置 ${args.text("choice")}")
                }
                player.popupAlerts.remove(alert)
            }
            "assetDecision" -> AssetDecisionFixtures.decide(game, args.text("choice"))
            "eventChoose" -> {
                // 复刻 AlertPopup.addEvent 的 value 解析与 EventChoice.triggerChoice 触发。
                val alert = openableHead(player)?.takeIf { it.type == AlertType.Event }
                    ?: error("原生重放队首不是可打开的事件弹窗")
                val parts = alert.value.split(Constants.stringSplitCharacter)
                var unit: com.unciv.logic.map.mapunit.MapUnit? = null
                for (part in parts.drop(1)) if (part.startsWith("unitId="))
                    unit = player.units.getUnitById(part.substringAfter("unitId=").toInt())
                val event = game.ruleset.events[parts[0]] ?: error("找不到事件定义 ${parts[0]}")
                val choices = event.getMatchingChoices(GameContext(player, unit = unit))?.toList()
                    ?: error("事件没有满足条件的选项 ${parts[0]}")
                val index = args["index"]?.jsonPrimitive?.int ?: -1
                if (index >= 0) choices[index].triggerChoice(player, unit)
                player.popupAlerts.remove(alert)
            }
            "greatPersonChoose" -> player.greatPeople.chooseFreeGreatPerson(args.text("unitName"))
            "religionChooseBeliefs" -> ReligionFixtures.chooseBeliefs(game,
                args["beliefs"]!!.jsonArray.map { it.jsonPrimitive.content }, player.religionManager.usingFreeBeliefs())
            "religionFound" -> ReligionFixtures.foundReligion(game, args.text("displayName"),
                args.text("religionId"), args["beliefs"]!!.jsonArray.map { it.jsonPrimitive.content })
            "cityPurchase" -> {
                val view = GameView(game, player)
                val city = view.civView.cities().first { it.id == args.text("cityId") }
                val name = args.text("name")
                val construction: INonPerpetualConstruction =
                    (game.ruleset.buildings[name] ?: game.ruleset.units[name]) as INonPerpetualConstruction
                check(city.constructions.purchaseConstruction(construction, args.integer("queueIndex"),
                    Stat.valueOf(args.text("stat")), null)) { "原生重放购买失败 $name" }
                city.updateCityStats()
            }
            "workerOrder" -> {
                val unit = player.units.getCivUnits().first { it.id == args.integer("unitId") }
                val name = args.text("name")
                when (args.text("type")) {
                    "start" -> check(WorkerImprovementActions.accept(unit.currentTile, unit,
                        game.ruleset.tileImprovements[name]!!)) { "原生重放施工未提交 $name" }
                    "createWaterImprovement" -> {
                        val water = UnitActions.getUnitActions(unit, UnitActionType.CreateImprovement)
                            .first { it.title == "Create [$name]" }
                        water.action!!.invoke()
                    }
                    else -> error("未映射的施工类型 ${args.text("type")}")
                }
            }
            "attack" -> {
                val unit = player.units.getCivUnits().first { it.id == args.integer("unitId") }
                val target = HexCoord(args.integer("x"), args.integer("y"))
                val attack = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
                    .first { it.tileToAttack.position == target }
                // false 也可能已移动或完成架设，与网关一致不再补攻击。
                if (Battle.movePreparingAttack(MapUnitCombatant(unit), attack, tryHealPillage = false))
                    Battle.attackOrNuke(MapUnitCombatant(unit), attack)
            }
            "victoryContinue" -> game.oneMoreTurnMode = true
            "unitAbility" -> {
                val unit = player.units.getCivUnits().first { it.id == args.integer("unitId") }
                val nativeType = UnitActionType.entries.first { it.name == args.text("type") }
                UnitActions.getUnitActions(unit, nativeType).firstNotNullOf { it.action }()
            }
            "nextTurn" -> {
                // 与 WorldScreen.nextTurn 一致：在 clone()+setTransients() 的副本上推进，再复刻
                // 网关回合后的无效事件清理；胜利检查由 apply 末尾的 screenUpdate 承担。
                val clone = game.clone()
                clone.setTransients()
                UncivGame.Current.gameInfo = clone
                clone.nextTurn()
                game = clone
                EventCommands(game).drainInvalidEventAlerts()
            }
            else -> throw AssertionError("NativeReplay 未映射的命令：$action $args")
        }
    }

    /**
     * 独立复刻原生 AlertPopup 的“可打开队首”选择：仅事件弹窗可能 shouldOpen==false
     * （定义缺失或 getMatchingChoices 为 null），其余类型原生都会尝试打开。
     */
    private fun openableHead(player: com.unciv.logic.civilization.Civilization) =
        player.popupAlerts.firstOrNull { alert ->
            if (alert.type != AlertType.Event) return@firstOrNull true
            val parts = alert.value.split(Constants.stringSplitCharacter)
            var unit: com.unciv.logic.map.mapunit.MapUnit? = null
            for (part in parts.drop(1)) if (part.startsWith("unitId="))
                unit = player.units.getUnitById(part.substringAfter("unitId=").toIntOrNull() ?: return@firstOrNull false)
            game.ruleset.events[parts[0]]?.getMatchingChoices(GameContext(player, unit = unit)) != null
        }
}
