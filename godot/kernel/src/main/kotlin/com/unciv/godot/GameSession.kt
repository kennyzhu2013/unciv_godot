package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import com.unciv.view.CityView
import com.unciv.view.MapUnitView
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class GatewayError(val code: String, override val message: String) : RuntimeException(message)

/** 所有入口（包括查询）串行化；失败的动作丢弃回合副本或换回命令前备份，不暴露半完成的状态。 */
internal class GameSession(private val root: File) {
    val sessionId: String = UUID.randomUUID().toString()
    var revision = 0
        private set
    var game: GameInfo? = null
        private set
    private val responses = LinkedHashMap<String, Pair<JsonObject, JsonObject>>()
    private val saveDirectory = File(root, "godot/.local/saves").apply { mkdirs() }

    @Synchronized
    fun handle(request: JsonObject): JsonObject {
        try {
            ensure(request.integer("protocol") == 1, "PROTOCOL", "不支持的协议版本")
            val action = request.text("action")
            if (action == "hello") return reply("data" to dto("saveDirectory" to saveDirectory.absolutePath))
            ensure(request.text("session") == sessionId, "SESSION", "会话已失效，请重新连接")
            if (action == "snapshot") return reply("snapshot" to game?.let { PlayerSnapshot(it).build() })
            val requestId = request.text("requestId")
            ensure(requestId.length in 1..100, "REQUEST_ID", "无效请求 ID")
            responses[requestId]?.let { (original, response) ->
                ensure(original == request, "REQUEST_REUSED", "同一请求 ID 不能使用不同参数")
                return response
            }
            ensure(request.integer("revision") == revision, "STALE_STATE", "状态已变化，请刷新后重试")
            if (action == "assetDecisionOptions") return reply("data" to AssetDecisionCommands(requireGame(), sessionId, revision).options(request))
            if (action == "diplomacyOptions") return reply("data" to DiplomacyCommands(requireGame(), sessionId, revision).options(request))
            if (action == "religionOptions") return reply("data" to ReligionCommands(requireGame(), sessionId, revision).options())
            if (action == "greatPersonOptions") return reply("data" to GreatPersonCommands(requireGame(), sessionId, revision).options(request))
            if (action == "diplomaticVoteOptions") return reply("data" to DiplomaticVoteCommands(requireGame(), sessionId, revision).options(request))
            if (action == "unitOptions" || action == "cityOptions") {
                val snapshot = PlayerSnapshot(requireGame())
                val data = if (action == "unitOptions") snapshot.unitOptions(unit(snapshot, request.integer("unitId")))
                    else snapshot.cityOptions(city(snapshot, request.text("cityId")))
                return reply("data" to data)
            }
            if (action == "path") {
                val snapshot = PlayerSnapshot(requireGame())
                val unit = unit(snapshot, request.integer("unitId"))
                val target = destination(snapshot, request)
                validateMove(unit, target)
                // 未探索路线仅包含坐标，不包含地形或隐藏对象。
                return reply("data" to dto("path" to unit.getPathToTile(target).map { it.position().dto() }))
            }

            // 新命令先完成无副作用校验；可预判错误不触发 setTransients 回滚。
            val preparedAttack = if (action == "attack" || action == "combatPreview") {
                val current = requireGame()
                validateGame(current)
                val combat = CombatCommands(current)
                if (action == "attack") combat.ensureNoBlockingBattleDecision()
                combat.prepareAttack(request.integer("unitId"), HexCoord(request.integer("x"), request.integer("y")))
            } else null
            if (action == "combatPreview") return reply("data" to preparedAttack!!.preview())
            val preparedGreatPerson = if (action == "greatPersonChoose")
                GreatPersonCommands(requireGame(), sessionId, revision).prepare(request) else null
            val preparedAction: (() -> Unit)? = when (action) {
                "assetDecision" -> AssetDecisionCommands(requireGame(), sessionId, revision).prepare(request)
                "diplomaticVoteCast", "diplomaticVoteAcknowledge" -> DiplomaticVoteCommands(requireGame(), sessionId, revision).prepare(request)
                "diplomacyDeclareWar", "diplomacyProposePeace", "diplomacyRetractPeace", "diplomacyTradeDecision", "diplomacyAlertDecision" ->
                    DiplomacyCommands(requireGame(), sessionId, revision).prepare(request)
                "cityBuyTile" -> CityEconomyCommands(requireGame()).prepareBuyTile(request)
                "cityPurchase" -> CityEconomyCommands(requireGame()).preparePurchase(request)
                "citySellBuilding" -> CityEconomyCommands(requireGame()).prepareSellBuilding(request)
                "unitAction" -> { validateGame(requireGame()); CombatCommands(requireGame()).prepareAction(request.integer("unitId"), request.text("type")) }
                "cityDecision" -> { validateGame(requireGame()); CityCaptureCommands(requireGame()).prepare(request.text("cityId"), request.text("choice")) }
                "workerOrder" -> { validateGame(requireGame()); WorkerCommands(requireGame()).prepareOrder(request.integer("unitId"), request.text("type"), request.text("name")) }
                "cityCitizen" -> { validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareCitizen(request.text("cityId"), request.integer("x"), request.integer("y"), request.text("type")) }
                "cityFocus" -> { validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareFocus(request.text("cityId"), request.text("focus")) }
                "cityAvoidGrowth" -> { validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareAvoidGrowth(request.text("cityId"), request.boolean("enabled")) }
                "cityResetCitizens" -> { validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareReset(request.text("cityId")) }
                "citySpecialists" -> { validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareSpecialists(request.text("cityId"), request.text("type"), request.text("name"), request["enabled"]?.jsonPrimitive?.booleanOrNull) }
                "cityQueue" -> { validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareQueue(request.text("cityId"), request.text("type"), request.text("name"), request["index"]?.jsonPrimitive?.intOrNull) }
                "religionUseProphet", "religionChooseBeliefs", "religionFound" ->
                    ReligionCommands(requireGame(), sessionId, revision).prepare(request)
                else -> null
            }
            if (action == "nextTurn") {
                val pending = PlayerSnapshot(requireGame()).pending()
                ensure(pending.isEmpty(), "PENDING_DECISION", pending.joinToString("；") { it.text("message") })
            }
            if (action in setOf("move", "foundCity")) CombatCommands(requireGame()).ensureNoBlockingBattleDecision()
            if (action == "acknowledge") {
                val alert = requireGame().currentPlayerCiv.popupAlerts.firstOrNull() ?: throw GatewayError("NO_ALERT", "没有待确认消息")
                ensure(alert.type in informationalAlerts, "UNSUPPORTED", "此事件需要选择，不能跳过处置")
            }

            val previous = game
            // 与原客户端调用方式一致（WorldScreen.nextTurn）：只有回合推进在 clone()+setTransients() 的副本上执行，
            // 玩家命令直接作用于当前局。setTransients 会探索地块、结识文明、发现自然奇观，若每条命令都触发，
            // 这些事件会比原客户端提前发生。玩家命令失败时用只复制数据的备份回滚，备份仅在回滚时才 setTransients。
            val backup = if (action in inPlaceCommands) requireGame().clone() else null
            val candidate = when (action) {
                "load" -> load(File(request.text("path")))
                "demo" -> {
                    val demo = File(saveDirectory, "demo.json")
                    if (!demo.exists()) atomicSave(KernelRuntime.createDemo(), demo)
                    load(demo)
                }
                "nextTurn" -> requireGame().clone().also { it.setTransients() }
                else -> requireGame()
            }
            UncivGame.Current.gameInfo = candidate
            val result: JsonObject
            try {
                validateGame(candidate)
                val snapshot = PlayerSnapshot(candidate)
                val civ = candidate.currentPlayerCiv
                var savedPath: String? = null
                var battleResult: JsonObject? = null
                var greatPersonResult: JsonObject? = null
                when (action) {
                    "greatPersonChoose" -> greatPersonResult = preparedGreatPerson!!.invoke()
                    "load", "demo" -> Unit
                    "attack" -> battleResult = preparedAttack!!.execute()
                    "diplomacyDeclareWar", "diplomacyProposePeace", "diplomacyRetractPeace", "diplomacyTradeDecision", "diplomacyAlertDecision",
                    "unitAction", "cityDecision", "assetDecision", "workerOrder",
                    "cityCitizen", "cityFocus", "cityAvoidGrowth", "cityResetCitizens", "citySpecialists", "cityQueue", "cityBuyTile", "cityPurchase", "citySellBuilding",
                    "religionUseProphet", "religionChooseBeliefs", "religionFound",
                    "diplomaticVoteCast", "diplomaticVoteAcknowledge" -> preparedAction!!.invoke()
                    "move" -> {
                        val unit = unit(snapshot, request.integer("unitId"))
                        val tile = destination(snapshot, request)
                        validateMove(unit, tile)
                        unit.tryResetAction()
                        ensure(unit.tryMoveToTile(tile), "MOVE_FAILED", "移动没有完成")
                    }
                    "foundCity" -> {
                        val unit = civ.units.getCivUnits().firstOrNull { it.id == request.integer("unitId") }
                            ?: throw GatewayError("NOT_OWNED", "找不到己方单位")
                        val actionToRun = UnitActionsFromUniques.getFoundCityAction(unit, unit.currentTile) { leaders, commit ->
                            ensure(request["confirmPromise"]?.jsonPrimitive?.booleanOrNull == true,
                                "CONFIRM_PROMISE", "这会违背对 $leaders 的承诺，是否继续？")
                            commit()
                        }?.action ?: throw GatewayError("CANNOT_FOUND", "此单位当前不能建城")
                        actionToRun()
                    }
                    "production" -> {
                        val city = city(snapshot, request.text("cityId"))
                        ensure(!city.isPuppet(), "PUPPET", "不能手动指定傀儡城市生产")
                        val construction = snapshot.constructions().firstOrNull { it.name == request.text("name") }
                            ?: throw GatewayError("CONSTRUCTION", "未知生产项目")
                        ensure(construction !is Building || city.getImprovementToCreate(construction) == null,
                            "UNSUPPORTED", "需要指定改良地块的项目尚未接入")
                        val existing = city.constructions.constructionQueue.indexOf(construction.name)
                        if (existing >= 0) city.tryMoveEntryToTop(existing)
                        else {
                            ensure(city.constructions.canAddToQueue(construction), "CANNOT_BUILD", "当前不能生产该项目")
                            city.tryAddToQueueConstruction(construction, addToTop = true)
                        }
                        // 与 CityScreenConstructionMenu 的“设为当前生产”一致：改变当前生产后重分配人口再刷新统计。
                        city.tryReassignPopulation()
                        city.updateCityStats()
                    }
                    "research" -> {
                        val name = request.text("name")
                        ensure(candidate.ruleset.technologies.containsKey(name) && civ.tech.canBeResearched(name),
                            "CANNOT_RESEARCH", "当前不能研究该科技")
                        if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology(name)
                        else civ.tech.techsToResearch = arrayListOf(name)
                    }
                    "policy" -> {
                        val policy = candidate.ruleset.policies[request.text("name")]
                            ?: throw GatewayError("POLICY", "未知政策")
                        ensure(civ.policies.canAdoptPolicy() && civ.policies.isAdoptable(policy), "CANNOT_ADOPT", "当前不能采用此政策")
                        civ.policies.adopt(policy)
                    }
                    "deferPolicy" -> snapshot.view.civView.tryDismissPolicyPicker()
                    "acknowledge" -> {
                        val alert = civ.popupAlerts.firstOrNull() ?: throw GatewayError("NO_ALERT", "没有待确认消息")
                        ensure(alert.type in informationalAlerts, "UNSUPPORTED", "此事件需要尚未接入的选择界面")
                        civ.popupAlerts.removeAt(0)
                    }
                    "declineTrade" -> {
                        // 与 TradePopup 的“Not this time.”一致；接受与还价需要完整交易界面，首版只接入拒绝。
                        val tradeRequest = civ.tradeRequests.firstOrNull() ?: throw GatewayError("NO_TRADE", "没有待处理交易")
                        val requestingCiv = candidate.getCivilization(tradeRequest.requestingCiv)
                        tradeRequest.decline(civ)
                        civ.tradeRequests.remove(tradeRequest)
                        requestingCiv.addNotification("[${civ.civName}] has denied your trade request",
                            NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
                    }
                    "nextTurn" -> {
                        val pending = snapshot.pending()
                        ensure(pending.isEmpty(), "PENDING_DECISION", pending.joinToString("；") { it.text("message") })
                        candidate.nextTurn()
                    }
                    "save" -> {
                        val name = request.text("name")
                        ensure(name.matches(Regex("[A-Za-z0-9_-]{1,80}")), "SAVE_NAME", "存档名只能包含字母、数字、下划线和连字符")
                        savedPath = File(saveDirectory, "$name.json").absolutePath
                    }
                    else -> throw GatewayError("UNKNOWN_COMMAND", "未实现命令：$action")
                }
                // 先验证快照可构造，再写盘和提交状态；不会破坏用户导入的原始存档。
                val playerSnapshot = PlayerSnapshot(candidate).build()
                if (savedPath != null) atomicSave(candidate, File(savedPath))
                game = candidate
                revision++
                val response = reply("snapshot" to playerSnapshot, "savedPath" to savedPath, "battleResult" to battleResult)
                result = if (greatPersonResult == null) response else JsonObject(response + ("greatPersonResult" to greatPersonResult))
            } catch (error: Exception) {
                if (backup != null) {
                    // 当前局可能已被半完成的命令修改，换回备份；与原客户端崩溃后重新读档效果相同。
                    backup.setTransients()
                    game = backup
                    UncivGame.Current.gameInfo = backup
                } else UncivGame.Current.gameInfo = previous
                throw error
            }
            responses[requestId] = request to result
            if (responses.size > 32) responses.remove(responses.keys.first())
            return result
        } catch (error: GatewayError) {
            return error(error.code, error.message)
        } catch (error: Exception) {
            error.printStackTrace()
            return error("KERNEL_ERROR", "内核未完成操作：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requireGame(): GameInfo = game ?: throw GatewayError("NO_GAME", "请先读取存档")
    private fun load(file: File): GameInfo {
        ensure(file.isFile && file.length() in 1..32L * 1024 * 1024, "SAVE_FILE", "存档不存在、为空或超过 32 MiB")
        return UncivFiles.gameInfoFromString(file.readText(Charsets.UTF_8))
    }
    private fun unit(snapshot: PlayerSnapshot, id: Int): MapUnitView = snapshot.view.civView.getUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")
    private fun city(snapshot: PlayerSnapshot, id: String): CityView {
        return snapshot.view.civView.cities().firstOrNull { it.id == id }
            ?: throw GatewayError("NOT_OWNED", "找不到己方城市")
    }
    private fun destination(snapshot: PlayerSnapshot, request: JsonObject) =
        snapshot.view.tileMapView.getTile(HexCoord(request.integer("x"), request.integer("y")))
            ?: throw GatewayError("TARGET", "目标不在地图中")
    private fun validateMove(unit: MapUnitView, target: com.unciv.view.TileView) {
        ensure(!unit.isAirUnit() && !unit.isPreparingParadrop(), "UNSUPPORTED", "空军和空降移动尚未接入")
        ensure(unit.hasMovement() && !unit.cannotMove() && unit.canMoveTo(target)
            && unit.getReachableTilesInCurrentTurn().any { it.position() == target.position() },
            "CANNOT_MOVE", "当前回合不能移动到该地块")
    }
    private fun atomicSave(game: GameInfo, target: File) {
        val temporary = Files.createTempFile(saveDirectory.toPath(), "save-", ".tmp")
        try {
            Files.writeString(temporary, UncivFiles.gameInfoToString(game, true))
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    private fun reply(vararg fields: Pair<String, Any?>) = dto("ok" to true, "protocol" to 1, "session" to sessionId, "revision" to revision, *fields)
    private fun error(code: String, message: String) = dto("ok" to false, "protocol" to 1, "session" to sessionId,
        "revision" to revision, "error" to dto("code" to code, "message" to message))
    private fun ensure(condition: Boolean, code: String, message: String) {
        if (!condition) throw GatewayError(code, message)
    }

    companion object {
        internal fun validateGame(game: GameInfo) {
            fun ensure(condition: Boolean, code: String, message: String) {
                if (!condition) throw GatewayError(code, message)
            }
            ensure(!game.gameParameters.isOnlineMultiplayer, "UNSUPPORTED_GAME", "首版只接入离线存档，不修改联机对局")
            ensure(game.civilizations.count { it.isHuman() } == 1 && game.currentPlayerCiv.isHuman()
                && !game.currentPlayerCiv.isSpectator() && !game.isSimulation(), "UNSUPPORTED_GAME", "首版需要单个人类玩家且轮到该玩家的存档")
            ensure(game.gameParameters.mods.isEmpty(), "UNSUPPORTED_MOD", "首版尚未验证 Mod，请使用基础规则存档")
        }

        // 仅对白类提示允许“已阅”：原 AlertPopup 中这些类型的全部按钮都只关闭弹窗、不改变状态；
        // 有玩法选择的事件（占领城市、要求、宣布友好、谴责、事件等）必须留给完整界面处理。
        val informationalAlerts = setOf(AlertType.StartIntro, AlertType.FirstContact, AlertType.TechResearched,
            AlertType.WonderBuilt, AlertType.GoldenAge, AlertType.WarDeclaration, AlertType.BorderConflict,
            AlertType.TilesStolen, AlertType.Defeated, AlertType.GameHasBeenWon, AlertType.AcceptingDemand,
            AlertType.RejectingDemand, AlertType.CitySettledNearOtherCivDespiteOurPromise,
            AlertType.ReligionSpreadDespiteOurPromise, AlertType.AttackedUsDespitePromise)
        /** 原客户端直接作用于当前局、会修改状态的玩家命令；save 只读不改，nextTurn 在副本上执行。 */
        val inPlaceCommands = setOf("move", "foundCity", "production", "research", "policy", "deferPolicy", "acknowledge", "declineTrade",
                    "diplomacyDeclareWar", "diplomacyProposePeace", "diplomacyRetractPeace", "diplomacyTradeDecision", "diplomacyAlertDecision",
                    "attack", "unitAction", "cityDecision", "assetDecision", "workerOrder",
                    "cityCitizen", "cityFocus", "cityAvoidGrowth", "cityResetCitizens", "citySpecialists", "cityQueue", "cityBuyTile", "cityPurchase", "citySellBuilding",
                    "religionUseProphet", "religionChooseBeliefs", "religionFound", "greatPersonChoose",
                    "diplomaticVoteCast", "diplomaticVoteAcknowledge")
    }
}
