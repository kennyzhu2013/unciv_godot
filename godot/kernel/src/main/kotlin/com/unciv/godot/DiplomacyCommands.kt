package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.*
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.GameView
import com.unciv.view.TradeView
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 玩家可见外交边界；报价／选择只读，所有玩法效果委托原生内核。 */
internal class DiplomacyCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private data class Choice(val id: String, val label: String, val problem: GatewayError?, val description: String = "") {
        fun dto() = dto("id" to id, "label" to label, "enabled" to (problem == null),
            "reason" to (problem?.message ?: ""), "description" to description)
    }
    private data class AlertParties(val other: Civilization?, val cityState: Civilization?)

    /** 复合引用只按原生 civID 解析；格式错误或未接触的对象不向 DTO 暴露。 */
    private fun alertParties(alert: PopupAlert): AlertParties {
        if (alert.type !in cityStateAlertTypes) return AlertParties(known(alert.value), null)
        val ids = alert.value.split('@')
        if (ids.size != 2 || ids.any { it.isEmpty() }) return AlertParties(null, null)
        return AlertParties(known(ids[0]), known(ids[1])?.takeIf { it.isCityState })
    }
    private fun problem(code: String, text: String) = GatewayError(code, text)
    private fun ensure(ok: Boolean, code: String, text: String) { if (!ok) throw problem(code, text) }
    private fun checked(check: () -> Unit): GatewayError? = try { check(); null } catch (e: GatewayError) { e }
    private fun known(id: String): Civilization? = game.civilizations.firstOrNull {
        it.civID == id && it != player && !it.isBarbarian && !it.isSpectator() && player.knows(it)
            && it.getDiplomacyManager(player) != null
    }
    private fun major(id: String): Civilization {
        val other = known(id) ?: throw problem("DIPLOMACY_TARGET", "外交对象不可用或尚未接触")
        ensure(other.isMajorCiv() && other.isAI(), "UNSUPPORTED", "本阶段仅支持主要 AI 文明操作；城邦只读")
        return other
    }
    private fun ordinary() { GameSession.validateGame(game); CombatCommands(game).ensureNoBlockingBattleDecision() }
    private fun relationshipsCanChange() = !game.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
    private fun warCheck(other: Civilization) {
        ensure(relationshipsCanChange() && player.getDiplomacyManager(other)!!.canDeclareWar(),
            "CANNOT_DECLARE_WAR", "当前不能宣战：请检查战争状态、和平条约及规则限制")
    }
    private fun peace(ourGold: Int, theirGold: Int): Trade = Trade().apply {
        ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        if (ourGold > 0) ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, ourGold, game.speed))
        if (theirGold > 0) theirOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, theirGold, game.speed))
    }
    private fun proposeCheck(other: Civilization, ourGold: Int, theirGold: Int) {
        ordinary()
        ensure(!player.isDefeated() && !other.isDefeated() && player.isAtWarWith(other) && relationshipsCanChange(),
            "CANNOT_PROPOSE_PEACE", "当前不处于可议和的战争状态")
        ensure(!other.getDiplomacyManager(player)!!.hasFlag(DiplomacyFlags.DeclaredWar),
            "CANNOT_PROPOSE_PEACE", "宣战后的议和冷却尚未结束")
        ensure(other.tradeRequests.none { it.requestingCiv == player.civID }, "CANNOT_PROPOSE_PEACE", "已有发给此文明的未决提案")
        ensure(ourGold <= player.gold.coerceAtLeast(0) && theirGold <= other.gold.coerceAtLeast(0),
            "CANNOT_PROPOSE_PEACE", "金币报价超过当前可用余额")
        ensure(TradeEvaluation().isTradeValid(peace(ourGold, theirGold), player, other), "CANNOT_PROPOSE_PEACE", "原生规则判定提案无效")
    }

    /** 严格白名单；不能只看 isPeaceTreaty，否则会接受夹带的城市或第三方战争。 */
    private fun supported(trade: Trade): Boolean {
        fun side(offers: List<TradeOffer>): Boolean = offers.count { it.type == TradeOfferType.Treaty } == 1
            && offers.count { it.type == TradeOfferType.Gold } <= 1
            && offers.all {
                when (it.type) {
                    TradeOfferType.Treaty -> it.name == Constants.peaceTreaty && it.amount == 1 && it.duration > 0
                    TradeOfferType.Gold -> it.name == Constants.flatGold && it.amount > 0 && it.duration == -1
                    else -> false
                }
            }
        return side(trade.ourOffers) && side(trade.theirOffers)
            && trade.ourOffers.first { it.type == TradeOfferType.Treaty }.duration == trade.theirOffers.first { it.type == TradeOfferType.Treaty }.duration
    }
    private fun expired(request: TradeRequest, other: Civilization) = other.isDefeated()
        || !TradeEvaluation().isTradeValid(request.trade, player, other)

    private fun fingerprint(kind: String, owner: String, index: Int, source: String, contents: JsonObject): String {
        val text = dto("session" to session, "revision" to revision, "gameId" to game.gameId, "player" to player.civID,
            "kind" to kind, "owner" to owner, "index" to index, "source" to source, "contents" to contents).toString()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun rawOffers(offers: List<TradeOffer>) = offers.map { dto("type" to it.type.name, "name" to it.name, "amount" to it.amount, "duration" to it.duration) }
    private fun tradeToken(request: TradeRequest, owner: Civilization, index: Int, outgoing: Boolean) = fingerprint(
        if (outgoing) "outgoing" else "incoming", owner.civID, index, request.requestingCiv,
        dto("ourOffers" to rawOffers(request.trade.ourOffers), "theirOffers" to rawOffers(request.trade.theirOffers)))
    private fun alertToken(alert: PopupAlert) = fingerprint("alert", player.civID, 0, alert.value, dto("type" to alert.type.name, "value" to alert.value))

    private fun safeOffers(offers: List<TradeOffer>) = offers.map {
        val label = when {
            it.type == TradeOfferType.Treaty && it.name == Constants.peaceTreaty -> "和平条约"
            it.type == TradeOfferType.Gold && it.name == Constants.flatGold -> "一次性金币"
            else -> "未支持条件（${it.type.name}，需原客户端接受）"
        }
        dto("type" to it.type.name, "label" to label, "amount" to it.amount.takeIf { amount -> amount >= 0 },
            "duration" to it.duration.takeIf { duration -> duration > 0 })
    }

    private fun tradeChoices(request: TradeRequest): List<Choice> {
        val other = known(request.requestingCiv)
        val base = checked {
            GameSession.validateGame(game)
            major(request.requestingCiv)
            ensure(player.popupAlerts.isEmpty(), "PENDING_DECISION", "请先处理当前弹窗事件，再处理交易")
        }
        val supported = supported(request.trade)
        val expired = supported && other != null && expired(request, other)
        return listOf(
            Choice("accept", "接受和平提案", base ?: when {
                !supported -> problem("UNSUPPORTED", "包含未支持或不一致的条款，不能接受部分交易")
                expired || !relationshipsCanChange() -> problem("CANNOT_ACCEPT_TRADE", "提案已失效或规则不允许议和")
                else -> null
            }),
            Choice("decline", "拒绝提案", base ?: if (expired) problem("CANNOT_ACCEPT_TRADE", "提案已过期，请清理") else null),
            Choice("dismiss", "清理过期提案", base ?: when {
                !supported -> problem("UNSUPPORTED", "此交易不能作为受支持的过期和平提案清理")
                !expired -> problem("DIPLOMACY_REQUEST", "提案尚未过期，请选择接受或拒绝")
                else -> null
            }))
    }
    private fun alertChoices(alert: PopupAlert): List<Choice> {
        if (alert.type !in alertTypes) return emptyList()
        val (other, cityState) = alertParties(alert)
        val expired = other != null && (other.isDefeated()
            || alert.type == AlertType.DeclarationOfFriendship && player.isAtWarWith(other))
        val base = checked {
            GameSession.validateGame(game)
            major(other?.civID ?: "")
            if (!expired && alert.type in cityStateAlertTypes)
                ensure(cityState != null, "DIPLOMACY_TARGET", "事件涉及的城邦不可用或尚未接触")
        }
        if (expired) return listOf(Choice("dismiss", "清理过期事件", base))
        fun choice(id: String, label: String, war: Boolean = false, description: String = "") = Choice(id, label,
            base ?: if (war) checked { warCheck(other!!) } else null, description)
        if (alert.type in cityStateAlertTypes) return buildList {
            if (other == null || !player.isAtWarWith(other)) add(choice("declareWar", "为城邦宣战", true,
                "支持城邦并向施压方宣战；在原生宣战效果之外，额外获得该城邦影响力 +20。"))
            add(choice("support", "支持城邦", description = "向施压方表示反对，不另行宣战；不会增加城邦影响力。"))
            add(choice("withdrawProtection", "接受现状并撤销保护", description =
                "按原生规则撤销保护并降低城邦影响力（-20），20 回合内不能重新承诺保护；盟友事件也按此规则处理。"))
        }
        return when (alert.type) {
            AlertType.DeclarationOfFriendship -> listOf(choice("accept", "接受友好声明"), choice("decline", "拒绝友好声明"))
            AlertType.DemandToStopSettlingCitiesNear -> listOf(choice("agree", "承诺不在附近定居"), choice("refuse", "拒绝定居要求"))
            AlertType.DemandToNotAttackUs -> listOf(choice("agree", "承诺不攻击"), choice("refuseAndDeclareWar", "拒绝并宣战", true))
            AlertType.DemandToStopSpreadingReligion -> listOf(
                choice("agree", "承诺停止传播宗教", description = "承诺停止向其城市传播宗教；期限按游戏速度计算（标准速度 100 回合），由原生回合逻辑检查履约。"),
                choice("refuse", "拒绝停止传教", description = "拒绝要求会影响双方外交评价，但不会因此宣战。"))
            AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise -> listOf(
                choice("agree", "承诺停止间谍活动", description = "承诺停止向对方派遣间谍；期限按游戏速度计算（标准速度 100 回合）。我方对其评价降低 10；不会自动撤回或改变现有间谍任务。违约惩罚若已结算，不会重复施加。"),
                choice("refuse", "拒绝停止间谍活动", description = "我方对其评价降低 20，对方对我方的拒绝要求评价降低 15，并在标准速度 100 回合内忽略此类活动（期限随游戏速度调整）。不会宣战或改变现有间谍任务，也不会重复结算已发生的违约惩罚。"))
            AlertType.Denounced -> listOf(choice("dismiss", "知道了"), choice("declareWar", "回应并宣战", true))
            else -> emptyList()
        }
    }
    private fun requireChoice(choices: List<Choice>, id: String) {
        val choice = choices.firstOrNull { it.id == id } ?: throw problem("INVALID_ARGUMENT", "不支持的处置选项")
        choice.problem?.let { throw it }
    }

    private fun retractProblem(other: Civilization, request: TradeRequest): GatewayError? = checked {
        ordinary()
        major(other.civID)
        ensure(other.tradeRequests.count { it.requestingCiv == player.civID } == 1 && supported(request.trade),
            "UNSUPPORTED", "重复或混合提案不能安全撤回，请使用原客户端处理")
    }

    fun prepare(request: JsonObject): () -> Unit {
        GameSession.validateGame(game)
        val action = request.text("action")
        val keys = when (action) {
            "diplomacyDeclareWar" -> setOf("civId")
            "diplomacyProposePeace" -> setOf("civId", "ourGold", "theirGold")
            "diplomacyRetractPeace" -> setOf("civId", "tradeToken")
            "diplomacyTradeDecision" -> setOf("tradeToken", "choice")
            "diplomacyAlertDecision" -> setOf("alertToken", "choice")
            else -> throw problem("UNSUPPORTED", "未接入的外交命令")
        }
        ensure((request.keys - envelope - keys).isEmpty(), "INVALID_ARGUMENT", "含未支持的外交参数")
        when (action) {
            "diplomacyDeclareWar" -> {
                val other = major(request.requiredDiplomacyText("civId"))
                ordinary()
                warCheck(other)
                return { player.getDiplomacyManager(other)!!.declareWar() }
            }
            "diplomacyProposePeace" -> {
                val id = request.requiredDiplomacyText("civId")
                val ours = request.requiredGold("ourGold")
                val theirs = request.requiredGold("theirGold")
                val other = major(id)
                proposeCheck(other, ours, theirs)
                val trade = peace(ours, theirs)
                return {
                    val view = TradeView(player, other, GameView(game, player))
                    view.setStagedTrade(trade)
                    check(view.tryProposeStagedTrade())
                }
            }
            "diplomacyRetractPeace" -> {
                val id = request.requiredDiplomacyText("civId")
                val token = request.requiredDiplomacyText("tradeToken")
                val other = major(id)
                val entry = other.tradeRequests.withIndex().firstOrNull { it.value.requestingCiv == player.civID }
                    ?: throw problem("DIPLOMACY_REQUEST", "没有对应的已发提案")
                ensure(token == tradeToken(entry.value, other, entry.index, true), "DIPLOMACY_REQUEST", "提案已变化，请重新确认")
                retractProblem(other, entry.value)?.let { throw it }
                return { check(TradeView(player, other, GameView(game, player)).tryRetractOffer()) }
            }
            "diplomacyTradeDecision" -> {
                val token = request.requiredDiplomacyText("tradeToken")
                val choice = request.requiredDiplomacyText("choice")
                val pending = player.tradeRequests.firstOrNull() ?: throw problem("DIPLOMACY_REQUEST", "没有当前交易提案")
                ensure(token == tradeToken(pending, player, 0, false), "DIPLOMACY_REQUEST", "提案已变化，请重新确认")
                requireChoice(tradeChoices(pending), choice)
                val other = major(pending.requestingCiv)
                return {
                    when (choice) {
                        "accept" -> TradeLogic(player, other).apply { currentTrade.set(pending.trade) }.acceptTrade()
                        "decline" -> pending.decline(player)
                    }
                    player.tradeRequests.remove(pending)
                    if (choice != "dismiss") other.addNotification(
                        "[${player.civName}] has ${if (choice == "accept") "accepted" else "denied"} your trade request",
                        NotificationCategory.Trade, player.civName, NotificationIcon.Trade)
                }
            }
            else -> {
                val token = request.requiredDiplomacyText("alertToken")
                val choice = request.requiredDiplomacyText("choice")
                val alert = player.popupAlerts.firstOrNull() ?: throw problem("DIPLOMACY_REQUEST", "没有当前外交事件")
                ensure(token == alertToken(alert), "DIPLOMACY_REQUEST", "事件已变化，请重新确认")
                ensure(alert.type in alertTypes, "UNSUPPORTED", "此事件尚未接入")
                requireChoice(alertChoices(alert), choice)
                val (other, cityState) = alertParties(alert)
                val diplo = player.getDiplomacyManager(other!!)!!
                return {
                    when (alert.type) {
                        AlertType.DeclarationOfFriendship -> when (choice) {
                            "accept" -> diplo.signDeclarationOfFriendship()
                            "decline" -> diplo.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
                        }
                        AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToNotAttackUs, AlertType.DemandToStopSpreadingReligion,
                        AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise -> {
                            val demand = when (alert.type) {
                                AlertType.DemandToNotAttackUs -> Demand.DoNotAttackUs
                                AlertType.DemandToStopSpreadingReligion -> Demand.DoNotSpreadReligion
                                AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise -> Demand.DontSpyOnUs
                                else -> Demand.DoNotSettleNearUs
                            }
                            if (choice == "agree") diplo.agreeToDemand(demand)
                            // refuseDemand 已执行宣战，不复制旧 UI 的第二次 declareWar。
                            else if (choice != "dismiss") diplo.refuseDemand(demand)
                        }
                        AlertType.Denounced -> if (choice == "declareWar") diplo.declareWar()
                        in cityStateAlertTypes -> when (choice) {
                            "declareWar" -> {
                                diplo.sideWithCityState()
                                val reason = if (alert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
                                diplo.declareWar(DeclareWarReason(reason, cityState!!))
                                val influence = cityState.getDiplomacyManager(player)!!
                                NativeDiplomacyBridge.setRawInfluence(influence, NativeDiplomacyBridge.rawInfluence(influence) + 20f)
                            }
                            "support" -> diplo.sideWithCityState()
                            "withdrawProtection" -> {
                                player.addNotification("You have broken your Pledge to Protect [${cityState!!.civName}]!",
                                    cityState.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cityState.civName)
                                cityState.cityStateFunctions.removeProtectorCiv(player, forced = true)
                            }
                        }
                        else -> Unit
                    }
                    player.popupAlerts.remove(alert)
                }
            }
        }
    }

    private fun warWarnings(other: Civilization): List<String> = buildList {
        add("宣战会取消相关交易，盟友可能连带参战。")
        if (other.getDiplomacyManager(player)!!.hasFlag(DiplomacyFlags.AgreedToNotAttackUs)) add("这将违背不攻击对方的承诺。")
        val allies = other.diplomacy.values.filter { it.otherCiv != player && it.diplomaticStatus == DiplomaticStatus.DefensivePact && !it.otherCiv.isAtWarWith(player) }.map { it.otherCiv }
        for (ally in allies) add("${if (player.knows(ally)) ally.civName else "未知文明"} 将加入对方参战。")
        for (diplo in player.diplomacy.values) if (diplo.otherCiv != other && diplo.diplomaticStatus == DiplomaticStatus.DefensivePact && diplo.otherCiv !in allies)
            add("与 ${diplo.otherCiv.civName} 的共同防御条约将取消。")
    }

    fun options(request: JsonObject = dto()): JsonObject {
        GameSession.validateGame(game)
        ensure((request.keys - envelope).isEmpty(), "INVALID_ARGUMENT", "外交查询不接受业务参数")
        return dto("playerGold" to player.gold, "civilizations" to player.diplomacyFunctions.getKnownCivsSorted().map { other ->
            val ours = player.getDiplomacyManager(other)!!
            val theirs = other.getDiplomacyManager(player)!!
            val major = other.isMajorCiv() && other.isAI()
            val fields = linkedMapOf<String, Any?>("civId" to other.civID, "name" to other.civName,
                "type" to if (other.isCityState) "cityState" else "major", "status" to ours.diplomaticStatus.name,
                "relationship" to theirs.relationshipLevel().name, "peaceTreatyTurns" to ours.turnsToPeaceTreaty(),
                "peaceNegotiationBlockedTurns" to theirs.getFlag(DiplomacyFlags.DeclaredWar))
            if (!major) fields["influence"] = if (other.isCityState) theirs.getInfluence() else null
            else {
                fields["opinion"] = theirs.opinionOfOtherCiv()
                fields["modifiers"] = listOf("us" to ours, "them" to theirs).flatMap { (observer, manager) ->
                    manager.diplomaticModifiers.toSortedMap().map { dto("observer" to observer, "name" to it.key, "value" to it.value) }
                }
                fields["proposedTreatyTurns"] = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed).duration
                fields["friendshipTurns"] = ours.getFlag(DiplomacyFlags.DeclarationOfFriendship)
                fields["gold"] = dto("ourAvailable" to player.gold.coerceAtLeast(0), "theirAvailable" to other.gold.coerceAtLeast(0))
                fields["promises"] = listOf(Demand.DoNotAttackUs, Demand.DoNotSettleNearUs, Demand.DoNotSpreadReligion, Demand.DontSpyOnUs).flatMap { demand ->
                    listOf("us" to theirs, "them" to ours).mapNotNull { (giver, manager) ->
                        if (!manager.hasFlag(demand.agreedToDemand)) null
                        else dto("type" to demand.name, "giver" to giver, "turns" to manager.getFlag(demand.agreedToDemand))
                    }
                }
                fields["actions"] = dto("declareWar" to Choice("declareWar", "宣战", checked { ordinary(); warCheck(other) }).dto(),
                    "proposePeace" to Choice("proposePeace", "发送和平提案", checked { proposeCheck(other, 0, 0) }).dto())
                fields["warWarnings"] = warWarnings(other)
                fields["outgoingTrades"] = other.tradeRequests.withIndex().filter { it.value.requestingCiv == player.civID }.map { (index, request) ->
                    val trade = request.trade.reverse()
                    dto("tradeToken" to tradeToken(request, other, index, true), "ourOffers" to safeOffers(trade.ourOffers),
                        "theirOffers" to safeOffers(trade.theirOffers), "supported" to supported(trade),
                        "retract" to Choice("retract", "撤回提案", retractProblem(other, request)).dto())
                }
            }
            dto(*fields.toList().toTypedArray())
        }.toList(), "incomingTrade" to player.tradeRequests.firstOrNull()?.let { request ->
            val other = known(request.requestingCiv)
            dto("civId" to other?.civID, "name" to (other?.civName ?: "不可用的外交对象"),
                "tradeToken" to tradeToken(request, player, 0, false), "supported" to supported(request.trade),
                "ourOffers" to safeOffers(request.trade.ourOffers), "theirOffers" to safeOffers(request.trade.theirOffers),
                "choices" to tradeChoices(request).map { it.dto() })
        }, "pendingAlert" to player.popupAlerts.firstOrNull()?.takeIf { it.type in alertTypes }?.let { alert ->
            val (other, cityState) = alertParties(alert)
            dto("civId" to other?.civID, "name" to (other?.civName ?: "不可用的外交对象"), "type" to alert.type.name,
                "cityState" to cityState?.let { dto("civId" to it.civID, "name" to it.civName) },
                "message" to alertMessage(alert), "alertToken" to alertToken(alert), "choices" to alertChoices(alert).map { it.dto() },
                "warWarnings" to if (other != null) warWarnings(other) else emptyList<String>())
        })
    }

    private fun alertMessage(alert: PopupAlert): String = "${alertParties(alert).other?.civName ?: "不可用的外交对象"}：" + when (alert.type) {
        AlertType.DeclarationOfFriendship -> "提议发表友好声明"
        AlertType.DemandToStopSettlingCitiesNear -> "要求停止在附近定居"
        AlertType.DemandToNotAttackUs -> "要求承诺不攻击；拒绝将宣战"
        AlertType.DemandToStopSpreadingReligion -> "要求停止向其城市传播宗教"
        AlertType.DemandToStopSpyingOnUs -> "要求停止间谍活动"
        AlertType.SpyingOnUsDespiteOurPromise -> "发现我方违背停止间谍活动的承诺；请选择重新承诺或拒绝"
        in cityStateAlertTypes -> {
            val cityState = alertParties(alert).cityState?.civName ?: "不可用的城邦"
            when (alert.type) {
                AlertType.BulliedProtectedMinor -> "向我们保护的城邦 $cityState 征收了贡品"
                AlertType.AttackedProtectedMinor -> "攻击了我们保护的城邦 $cityState"
                else -> "攻击了我们的盟友城邦 $cityState"
            }
        }
        AlertType.Denounced -> "谴责了我们，可以知道了或选择宣战回应"
        else -> "尚未接入的事件"
    }
    fun pendingTrade(): JsonObject? = player.tradeRequests.firstOrNull()?.let {
        val other = known(it.requestingCiv)
        dto("kind" to "trade", "message" to "${other?.civName ?: "不可用的外交对象"} 提议交易；请到外交页查看完整条件并处理",
            "target" to (other?.civID ?: ""), "supported" to (other?.isMajorCiv() == true && other.isAI()), "diplomacy" to true)
    }
    fun pendingAlert(alert: PopupAlert): JsonObject = dto("kind" to "diplomacyAlert", "message" to alertMessage(alert),
        "target" to (alertParties(alert).other?.civID ?: ""), "supported" to alertChoices(alert).any { it.problem == null }, "diplomacy" to true)

    companion object {
        private val cityStateAlertTypes = setOf(AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor)
        val alertTypes = setOf(AlertType.DeclarationOfFriendship, AlertType.DemandToStopSettlingCitiesNear,
            AlertType.DemandToNotAttackUs, AlertType.DemandToStopSpreadingReligion, AlertType.Denounced,
            AlertType.DemandToStopSpyingOnUs, AlertType.SpyingOnUsDespiteOurPromise) + cityStateAlertTypes
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
    }
}

private fun JsonObject.requiredDiplomacyText(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotBlank() }?.content
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少字符串参数或类型错误：$key")
private fun JsonObject.requiredGold(key: String): Int = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
    ?: throw GatewayError("INVALID_ARGUMENT", "金额必须为非负 JSON 整数：$key")
