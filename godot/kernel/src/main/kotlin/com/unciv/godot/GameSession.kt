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
    /** 仅冒烟运行时（run.ps1 -Smoke 设置 UNCIV_SMOKE=1）开启：允许 debugInjectEvent 向实时会话注入合成事件；真实游玩不设该变量即完全不存在此命令。 */
    private val smokeEnabled: Boolean = System.getenv("UNCIV_SMOKE") == "1"

    /** 命令注册表：每个命令单点声明其查询/效果语义、候选来源、是否就地备份与 prepare/execute。未登记的命令视为未实现。 */
    private val commands: Map<String, GatewayCommand> = mapOf(
        "eventOptions" to query { EventCommands(requireGame(), it.sessionId, it.revision).options(it.request) },
        "eventChoose" to effect(inPlace = true,
            prepareFn = { EventCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "debugInjectEvent" to effect(inPlace = true, prepareFn = { ctx ->
            {
                // 门控测试命令：基础规则集无 Alert 型事件且 ruleset 不序列化，端到端前端验收只能由内核注入。
                ensure(smokeEnabled, "UNKNOWN_COMMAND", "未实现命令：debugInjectEvent")
                EventCommands(ctx.candidate).injectSmokeEvent(ctx.request.text("kind"))
            }
        }),
        "greatPersonOptions" to query { GreatPersonCommands(requireGame(), it.sessionId, it.revision).options(it.request) },
        "greatPersonChoose" to effect(inPlace = true, prepareFn = { ctx ->
            val prepared = GreatPersonCommands(requireGame(), ctx.sessionId, ctx.revision).prepare(ctx.request)
            val thunk: () -> Unit = { ctx.greatPersonResult = prepared.invoke() }
            thunk
        }),
        "assetDecisionOptions" to query { AssetDecisionCommands(requireGame(), it.sessionId, it.revision).options(it.request) },
        "assetDecision" to effect(inPlace = true,
            prepareFn = { AssetDecisionCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomaticVoteOptions" to query { DiplomaticVoteCommands(requireGame(), it.sessionId, it.revision).options(it.request) },
        "diplomaticVoteCast" to effect(inPlace = true,
            prepareFn = { DiplomaticVoteCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomaticVoteAcknowledge" to effect(inPlace = true,
            prepareFn = { DiplomaticVoteCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "religionOptions" to query { ReligionCommands(requireGame(), it.sessionId, it.revision).options() },
        "religionUseProphet" to effect(inPlace = true,
            prepareFn = { ReligionCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "religionChooseBeliefs" to effect(inPlace = true,
            prepareFn = { ReligionCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "religionFound" to effect(inPlace = true,
            prepareFn = { ReligionCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "combatPreview" to query {
            val current = requireGame()
            validateGame(current)
            CombatCommands(current).prepareAttack(it.request.integer("unitId"), HexCoord(it.request.integer("x"), it.request.integer("y"))).preview()
        },
        "attack" to effect(inPlace = true, prepareFn = { ctx ->
            val current = requireGame()
            validateGame(current)
            val combat = CombatCommands(current)
            combat.ensureNoBlockingBattleDecision()
            val preparedAttack = combat.prepareAttack(ctx.request.integer("unitId"), HexCoord(ctx.request.integer("x"), ctx.request.integer("y")))
            val thunk: () -> Unit = { ctx.battleResult = preparedAttack.execute() }
            thunk
        }),
        "move" to effect(inPlace = true, prepareFn = { ctx ->
            CombatCommands(requireGame()).ensureNoBlockingBattleDecision()
            val thunk: () -> Unit = {
                val unit = unit(ctx.snapshot, ctx.request.integer("unitId"))
                val tile = destination(ctx.snapshot, ctx.request)
                validateMove(unit, tile)
                unit.tryResetAction()
                ensure(unit.tryMoveToTile(tile), "MOVE_FAILED", "移动没有完成")
            }
            thunk
        }),
        "foundCity" to effect(inPlace = true, prepareFn = { ctx ->
            CombatCommands(requireGame()).ensureNoBlockingBattleDecision()
            val thunk: () -> Unit = {
                val request = ctx.request
                val civ = ctx.candidate.currentPlayerCiv
                val unit = civ.units.getCivUnits().firstOrNull { it.id == request.integer("unitId") }
                    ?: throw GatewayError("NOT_OWNED", "找不到己方单位")
                val actionToRun = UnitActionsFromUniques.getFoundCityAction(unit, unit.currentTile) { leaders, commit ->
                    ensure(request["confirmPromise"]?.jsonPrimitive?.booleanOrNull == true,
                        "CONFIRM_PROMISE", "这会违背对 $leaders 的承诺，是否继续？")
                    commit()
                }?.action ?: throw GatewayError("CANNOT_FOUND", "此单位当前不能建城")
                actionToRun()
            }
            thunk
        }),
        "unitAction" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CombatCommands(requireGame()).prepareAction(it.request.integer("unitId"), it.request.text("type"))
        }),
        "cityDecision" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityCaptureCommands(requireGame()).prepare(it.request.text("cityId"), it.request.text("choice"))
        }),
        "workerOrder" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); WorkerCommands(requireGame()).prepareOrder(it.request.integer("unitId"), it.request.text("type"), it.request.text("name"))
        }),
        "cityCitizen" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareCitizen(it.request.text("cityId"), it.request.integer("x"), it.request.integer("y"), it.request.text("type"))
        }),
        "cityFocus" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareFocus(it.request.text("cityId"), it.request.text("focus"))
        }),
        "cityAvoidGrowth" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareAvoidGrowth(it.request.text("cityId"), it.request.boolean("enabled"))
        }),
        "cityResetCitizens" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareReset(it.request.text("cityId"))
        }),
        "citySpecialists" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareSpecialists(it.request.text("cityId"), it.request.text("type"), it.request.text("name"), it.request["enabled"]?.jsonPrimitive?.booleanOrNull)
        }),
        "cityQueue" to effect(inPlace = true, prepareFn = {
            validateGame(requireGame()); CityDevelopmentCommands(requireGame()).prepareQueue(it.request.text("cityId"), it.request.text("type"), it.request.text("name"), it.request["index"]?.jsonPrimitive?.intOrNull)
        }),
        "cityBuyTile" to effect(inPlace = true, prepareFn = { CityEconomyCommands(requireGame()).prepareBuyTile(it.request) }),
        "cityPurchase" to effect(inPlace = true, prepareFn = { CityEconomyCommands(requireGame()).preparePurchase(it.request) }),
        "citySellBuilding" to effect(inPlace = true, prepareFn = { CityEconomyCommands(requireGame()).prepareSellBuilding(it.request) }),
        "production" to effect(inPlace = true, executeFn = { ctx, _ ->
            val request = ctx.request
            val city = city(ctx.snapshot, request.text("cityId"))
            ensure(!city.isPuppet(), "PUPPET", "不能手动指定傀儡城市生产")
            val construction = ctx.snapshot.constructions().firstOrNull { it.name == request.text("name") }
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
        }),
        "research" to effect(inPlace = true, executeFn = { ctx, _ ->
            val civ = ctx.candidate.currentPlayerCiv
            val name = ctx.request.text("name")
            ensure(ctx.candidate.ruleset.technologies.containsKey(name) && civ.tech.canBeResearched(name),
                "CANNOT_RESEARCH", "当前不能研究该科技")
            if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology(name)
            else civ.tech.techsToResearch = arrayListOf(name)
        }),
        "policy" to effect(inPlace = true, executeFn = { ctx, _ ->
            val civ = ctx.candidate.currentPlayerCiv
            val policy = ctx.candidate.ruleset.policies[ctx.request.text("name")]
                ?: throw GatewayError("POLICY", "未知政策")
            ensure(civ.policies.canAdoptPolicy() && civ.policies.isAdoptable(policy), "CANNOT_ADOPT", "当前不能采用此政策")
            civ.policies.adopt(policy)
        }),
        "deferPolicy" to effect(inPlace = true, executeFn = { ctx, _ -> ctx.snapshot.view.civView.tryDismissPolicyPicker() }),
        "cityStateGiftGold" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "cityStatePledgeProtection" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "cityStateRevokeProtection" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "cityStateDemandTribute" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "cityStateDeclareWar" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "cityStateNegotiatePeace" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "cityStateMarriage" to effect(inPlace = true, prepareFn = { CityStateCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomacyOptions" to query { DiplomacyCommands(requireGame(), it.sessionId, it.revision).options(it.request) },
        "diplomacyDeclareWar" to effect(inPlace = true, prepareFn = { DiplomacyCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomacyProposePeace" to effect(inPlace = true, prepareFn = { DiplomacyCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomacyRetractPeace" to effect(inPlace = true, prepareFn = { DiplomacyCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomacyTradeDecision" to effect(inPlace = true, prepareFn = { DiplomacyCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "diplomacyAlertDecision" to effect(inPlace = true, prepareFn = { DiplomacyCommands(requireGame(), it.sessionId, it.revision).prepare(it.request) }),
        "declineTrade" to effect(inPlace = true, executeFn = { ctx, _ ->
            val civ = ctx.candidate.currentPlayerCiv
            // 与 TradePopup 的“Not this time.”一致；接受与还价需要完整交易界面，首版只接入拒绝。
            val tradeRequest = civ.tradeRequests.firstOrNull() ?: throw GatewayError("NO_TRADE", "没有待处理交易")
            val requestingCiv = ctx.candidate.getCivilization(tradeRequest.requestingCiv)
            tradeRequest.decline(civ)
            civ.tradeRequests.remove(tradeRequest)
            requestingCiv.addNotification("[${civ.civName}] has denied your trade request",
                NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
        }),
        "unitOptions" to query {
            val snapshot = PlayerSnapshot(requireGame())
            snapshot.unitOptions(unit(snapshot, it.request.integer("unitId")))
        },
        "cityOptions" to query {
            val snapshot = PlayerSnapshot(requireGame())
            snapshot.cityOptions(city(snapshot, it.request.text("cityId")))
        },
        "path" to query {
            val snapshot = PlayerSnapshot(requireGame())
            val unit = unit(snapshot, it.request.integer("unitId"))
            val target = destination(snapshot, it.request)
            validateMove(unit, target)
            // 未探索路线仅包含坐标，不包含地形或隐藏对象。
            dto("path" to unit.getPathToTile(target).map { node -> node.position().dto() })
        },
        "load" to effect(source = CandidateSource.LOADED, inPlace = false),
        "demo" to effect(source = CandidateSource.LOADED, inPlace = false),
        "save" to effect(source = CandidateSource.CURRENT, inPlace = false, executeFn = { ctx, _ ->
            val name = ctx.request.text("name")
            ensure(name.matches(Regex("[A-Za-z0-9_-]{1,80}")), "SAVE_NAME", "存档名只能包含字母、数字、下划线和连字符")
            ctx.savedPath = File(saveDirectory, "$name.json").absolutePath
        }),
        "nextTurn" to effect(source = CandidateSource.CLONE_TURN, inPlace = false,
            prepareFn = { _ ->
                val pending = PlayerSnapshot(requireGame()).pending()
                ensure(pending.isEmpty(), "PENDING_DECISION", pending.joinToString("；") { entry -> entry.text("message") })
                val thunk: () -> Unit = { }
                thunk
            },
            executeFn = { ctx, _ ->
                val pending = ctx.snapshot.pending()
                ensure(pending.isEmpty(), "PENDING_DECISION", pending.joinToString("；") { entry -> entry.text("message") })
                ctx.candidate.nextTurn()
                // 复刻 AlertPopup：无效事件（shouldOpen==false）在原生 update 中被直接移除，这里在回合推进后清理队首无效事件。
                EventCommands(ctx.candidate).drainInvalidEventAlerts()
            }),
        "acknowledge" to effect(inPlace = true,
            prepareFn = { _ ->
                val alert = requireGame().currentPlayerCiv.popupAlerts.firstOrNull() ?: throw GatewayError("NO_ALERT", "没有待确认消息")
                ensure(alert.type in informationalAlerts, "UNSUPPORTED", "此事件需要选择，不能跳过处置")
                val thunk: () -> Unit = { }
                thunk
            },
            executeFn = { ctx, _ ->
                val civ = ctx.candidate.currentPlayerCiv
                val alert = civ.popupAlerts.firstOrNull() ?: throw GatewayError("NO_ALERT", "没有待确认消息")
                ensure(alert.type in informationalAlerts, "UNSUPPORTED", "此事件需要尚未接入的选择界面")
                civ.popupAlerts.removeAt(0)
            }),
    )

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

            // 命令单点声明于注册表：查询直接产出 data；效果命令走 prepare→candidate→execute 编排；未登记命令视为未实现。
            val ctx = CommandContext(request, sessionId, revision)
            val registered = commands[action]
            if (registered is QueryCommand) return reply("data" to registered.data(ctx))
            val effect = registered as? EffectCommand
                ?: throw GatewayError("UNKNOWN_COMMAND", "未实现命令：$action")
            // 新命令先完成无副作用校验（prepare 作用于 current、备份前）；可预判错误不触发 setTransients 回滚。
            val prepared = effect.prepare(ctx)

            val previous = game
            // 与原客户端调用方式一致（WorldScreen.nextTurn）：只有回合推进在 clone()+setTransients() 的副本上执行，
            // 玩家命令直接作用于当前局。setTransients 会探索地块、结识文明、发现自然奇观，若每条命令都触发，
            // 这些事件会比原客户端提前发生。玩家命令失败时用只复制数据的备份回滚，备份仅在回滚时才 setTransients。
            val backup = if (effect.inPlace) requireGame().clone() else null
            val candidate = when (effect.source) {
                CandidateSource.LOADED -> loadOrDemo(action, request)
                CandidateSource.CLONE_TURN -> requireGame().clone().also { it.setTransients() }
                CandidateSource.CURRENT -> requireGame()
            }
            UncivGame.Current.gameInfo = candidate
            val result: JsonObject
            try {
                validateGame(candidate)
                ctx.candidate = candidate
                ctx.snapshot = PlayerSnapshot(candidate)
                effect.execute(ctx, prepared)
                // 先验证快照可构造，再写盘和提交状态；不会破坏用户导入的原始存档。
                val playerSnapshot = PlayerSnapshot(candidate).build()
                if (ctx.savedPath != null) atomicSave(candidate, File(ctx.savedPath!!))
                game = candidate
                revision++
                val response = reply("snapshot" to playerSnapshot, "savedPath" to ctx.savedPath, "battleResult" to ctx.battleResult)
                result = if (ctx.greatPersonResult == null) response else JsonObject(response + ("greatPersonResult" to ctx.greatPersonResult!!))
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
    private fun loadOrDemo(action: String, request: JsonObject): GameInfo = when (action) {
        "load" -> load(File(request.text("path")))
        "demo" -> {
            val demo = File(saveDirectory, "demo.json")
            if (!demo.exists()) atomicSave(KernelRuntime.createDemo(), demo)
            load(demo)
        }
        else -> throw GatewayError("UNKNOWN_COMMAND", "未实现命令：$action")
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

    /** 构造只读查询命令：直接产出 data，不推进 revision、不回传快照、不备份。 */
    private fun query(data: (CommandContext) -> JsonObject): QueryCommand = object : QueryCommand {
        override fun data(ctx: CommandContext) = data(ctx)
    }

    /** 构造效果命令：source 决定候选来源，inPlace 决定是否备份，prepare 无副作用校验并返回执行闭包，execute 默认调用该闭包。 */
    private fun effect(
        source: CandidateSource = CandidateSource.CURRENT,
        inPlace: Boolean = true,
        prepareFn: (CommandContext) -> (() -> Unit) = { { } },
        executeFn: (CommandContext, () -> Unit) -> Unit = { _, prepared -> prepared() },
    ): EffectCommand = object : EffectCommand {
        override val source: CandidateSource = source
        override val inPlace: Boolean = inPlace
        override fun prepare(ctx: CommandContext): () -> Unit = prepareFn(ctx)
        override fun execute(ctx: CommandContext, prepared: () -> Unit) = executeFn(ctx, prepared)
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
    }
}
