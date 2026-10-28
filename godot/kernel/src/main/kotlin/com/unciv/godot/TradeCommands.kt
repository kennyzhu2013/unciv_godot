package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.*
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.GameView
import com.unciv.view.TradeView
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 外交选择项：只描述可见选项和禁用原因，不含隐藏对局状态。 */
internal data class Choice(val id: String, val label: String, val problem: GatewayError?, val description: String = "") {
    fun dto() = dto("id" to id, "label" to label, "enabled" to (problem == null),
        "reason" to (problem?.message ?: ""), "description" to description)
}

/**
 * 接收交易的唯一实现：条款解析、白名单、票据和处置命令都在这里。
 * 只接受基础规则原生交易窗口能产生的条款；接受统一委托 [TradeLogic.acceptTrade]，
 * 拒绝保留原生 [TradeRequest.decline] 冷却，清理只用于已经失效的提案。
 */
internal class TradeCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private val choiceIds = setOf("accept", "decline", "dismiss")
    private val treatyNames = setOf(Constants.peaceTreaty, Constants.researchAgreement, Constants.defensivePact)

    private fun problem(code: String, text: String) = GatewayError(code, text)
    private fun ensure(ok: Boolean, code: String, text: String) { if (!ok) throw problem(code, text) }
    private fun checked(check: () -> Unit): GatewayError? = try { check(); null } catch (e: GatewayError) { e }

    private fun known(id: String): Civilization? = game.civilizations.firstOrNull {
        it.civID == id && it != player && !it.isBarbarian && !it.isSpectator() && player.knows(it)
            && it.getDiplomacyManager(player) != null
    }
    private fun major(id: String): Civilization {
        val other = known(id) ?: throw problem("DIPLOMACY_TARGET", "外交对象不可用或尚未接触")
        ensure(other.isMajorCiv() && other.isAI(), "UNSUPPORTED", "此命令仅面向主要文明；城邦请使用城邦命令")
        return other
    }
    private fun third(id: String): Civilization? = game.civilizations.firstOrNull {
        it.civID == id && it != player && !it.isBarbarian && !it.isSpectator() && !it.isDefeated() && player.knows(it)
    }
    private fun thirdName(id: String) = third(id)?.civName ?: "未知文明"
    private fun cityName(id: String) = game.getCities().firstOrNull { it.id == id }?.name ?: "未知城市"
    private fun relationshipsCanChange() = !game.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)

    /** 结构、方向与白名单；不调用原生校验，因此对任意外部内容都安全。 */
    private fun clauseProblem(offer: TradeOffer, owner: Civilization, partner: Civilization): String? {
        val resource = game.ruleset.tileResources[offer.name]
        val third = third(offer.name)
        fun need(condition: Boolean, text: String): String? = if (condition) null else text
        fun thirdUsable(): String? = need(
            third != null && third != owner && third != partner && owner.knows(third) && partner.knows(third),
            "第三方文明不可用或双方未同时接触")
        return when (offer.type) {
            TradeOfferType.Embassy -> need(offer.name == Constants.acceptEmbassy && offer.amount == 1 && offer.duration == -1,
                "使馆条款结构无效")
                ?: need(owner.getCapital(false) != null, "首都缺失，不能交换使馆")
            TradeOfferType.Gold -> need(offer.name == Constants.flatGold && offer.amount > 0 && offer.duration == -1,
                "一次性金币条款结构无效")
            TradeOfferType.Gold_Per_Turn -> need(offer.name == Constants.goldPerTurn && offer.amount > 0 && offer.duration > 0,
                "每回合金条款结构无效")
            TradeOfferType.Treaty -> need(offer.name in treatyNames && offer.duration > 0, "条约条款结构无效")
                ?: need(offer.name == Constants.researchAgreement || offer.amount == 1, "该条约数量必须为 1")
                ?: need(offer.name != Constants.researchAgreement || offer.amount > 0, "科研协议缺少费用")
            TradeOfferType.Agreement -> need(offer.name == Constants.openBorders && offer.amount == 1 && offer.duration > 0,
                "开放边界条款结构无效")
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> {
                val expected = if (offer.type == TradeOfferType.Luxury_Resource) ResourceType.Luxury else ResourceType.Strategic
                need(resource != null && resource.resourceType == expected, "资源类型或名称不匹配")
                    ?: need(offer.amount > 0 && offer.duration > 0, "资源条款数量或期限无效")
                    ?: need(owner.getResourceAmount(offer.name) >= offer.amount, "本方资源数量不足")
            }
            TradeOfferType.City -> need(owner.cities.any { it.id == offer.name }, "城市不存在或不属于提案方")
                ?: need(offer.amount == 1 && offer.duration == -1, "城市条款结构无效")
            TradeOfferType.WarDeclaration -> need(offer.amount == 1 && offer.duration == -1, "宣战条款结构无效")
                ?: thirdUsable()
                ?: need(relationshipsCanChange(), "当前规则不允许第三方战争条款")
                ?: need(owner.getDiplomacyManager(third!!)?.canDeclareWar() == true, "提案方当前不能对该第三方宣战")
            TradeOfferType.PeaceProposal -> need(offer.amount == 1 && offer.duration == -1, "议和条款结构无效")
                ?: thirdUsable()
                ?: need(relationshipsCanChange(), "当前规则不允许第三方议和条款")
                ?: need(owner.isAtWarWith(third!!), "提案方并未与该第三方交战")
            TradeOfferType.Technology -> "基础规则不提供科技交易"
            TradeOfferType.Introduction -> "基础规则不提供介绍文明交易"
            TradeOfferType.Stockpiled_Resource -> "基础规则不提供储备资源交易"
        }
    }

    /** 条约由原生只执行一侧，必须双边完全镜像；其余条款允许单向。 */
    fun shapeProblems(trade: Trade, other: Civilization): List<String> {
        val problems = ArrayList<String>()
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) problems.add("提案没有任何条款")
        fun side(name: String, offers: List<TradeOffer>, owner: Civilization, partner: Civilization) {
            val seen = HashSet<String>()
            for (offer in offers) {
                clauseProblem(offer, owner, partner)?.let { problems.add("$name：$it（${brief(offer)}）") }
                if (!seen.add("${offer.type.name}|${offer.name}")) problems.add("$name：重复条款（${brief(offer)}）")
            }
        }
        side("我方", trade.ourOffers, player, other)
        side("对方", trade.theirOffers, other, player)
        for ((name, offers, counterpart) in listOf(
            Triple("我方", trade.ourOffers, trade.theirOffers),
            Triple("对方", trade.theirOffers, trade.ourOffers))) {
            for (offer in offers.filter { it.type == TradeOfferType.Treaty }) {
                if (counterpart.none { it.type == TradeOfferType.Treaty && it.name == offer.name
                        && it.amount == offer.amount && it.duration == offer.duration })
                    problems.add("$name：条约未在双方以相同条件镜像（${brief(offer)}）；原生只会执行一侧")
            }
        }
        return problems
    }

    fun supported(trade: Trade, other: Civilization): Boolean = shapeProblems(trade, other).isEmpty()

    private fun nativeValid(trade: Trade, other: Civilization): Boolean =
        runCatching { TradeEvaluation().isTradeValid(trade, player, other) }.getOrDefault(false)

    fun expired(request: TradeRequest, other: Civilization): Boolean =
        other.isDefeated() || !nativeValid(request.trade, other)

    private fun fingerprint(kind: String, owner: String, index: Int, source: String, contents: JsonObject): String {
        val text = dto("session" to session, "revision" to revision, "gameId" to game.gameId, "player" to player.civID,
            "kind" to kind, "owner" to owner, "index" to index, "source" to source, "contents" to contents).toString()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun rawOffers(offers: List<TradeOffer>) = offers.map {
        dto("type" to it.type.name, "name" to it.name, "amount" to it.amount, "duration" to it.duration)
    }
    fun token(request: TradeRequest, owner: Civilization, index: Int, outgoing: Boolean) = fingerprint(
        if (outgoing) "outgoing" else "incoming", owner.civID, index, request.requestingCiv,
        dto("ourOffers" to rawOffers(request.trade.ourOffers), "theirOffers" to rawOffers(request.trade.theirOffers)))

    private fun label(offer: TradeOffer): String = when (offer.type) {
        TradeOfferType.Embassy -> "使馆"
        TradeOfferType.Gold -> "一次性金币"
        TradeOfferType.Gold_Per_Turn -> "每回合金币"
        TradeOfferType.Treaty -> when (offer.name) {
            Constants.peaceTreaty -> "和平条约"
            Constants.researchAgreement -> "科研协议"
            Constants.defensivePact -> "共同防御条约"
            else -> "条约 ${offer.name}"
        }
        TradeOfferType.Agreement -> if (offer.name == Constants.openBorders) "开放边界" else "协议 ${offer.name}"
        TradeOfferType.Luxury_Resource -> "奢侈资源 ${offer.name}"
        TradeOfferType.Strategic_Resource -> "战略资源 ${offer.name}"
        TradeOfferType.Stockpiled_Resource -> "储备资源 ${offer.name}"
        TradeOfferType.Technology -> "科技 ${offer.name}"
        TradeOfferType.Introduction -> "介绍文明 ${thirdName(offer.name)}"
        TradeOfferType.WarDeclaration -> "对 ${thirdName(offer.name)} 宣战"
        TradeOfferType.PeaceProposal -> "与 ${thirdName(offer.name)} 议和"
        TradeOfferType.City -> "城市 ${cityName(offer.name)}"
    }

    private fun amountText(offer: TradeOffer): String = when (offer.type) {
        TradeOfferType.Gold, TradeOfferType.Gold_Per_Turn,
        TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource,
        TradeOfferType.Stockpiled_Resource, TradeOfferType.Technology -> " ×${offer.amount}"
        TradeOfferType.Treaty -> if (offer.name == Constants.researchAgreement) "（费用 ${offer.amount} 金币）" else ""
        else -> ""
    }

    private fun brief(offer: TradeOffer): String {
        val duration = if (offer.duration > 0) "${offer.duration} 回合" else "立即"
        return "${label(offer)} ×${offer.amount}，$duration"
    }

    /** 条款引用只按外交界面可见的名字输出；城市 id 与未接触文明 id 不进入 DTO。 */
    private fun displayName(offer: TradeOffer): String = when (offer.type) {
        TradeOfferType.City -> game.getCities().firstOrNull { it.id == offer.name }?.name ?: "未知城市"
        TradeOfferType.WarDeclaration, TradeOfferType.PeaceProposal, TradeOfferType.Introduction -> thirdName(offer.name)
        else -> offer.name
    }

    private fun effects(offer: TradeOffer, owner: Civilization, partner: Civilization): List<String> = when (offer.type) {
        TradeOfferType.Embassy -> listOf(
            "立即向 ${partner.civName} 公开 ${owner.civName} 首都周围 2 格地图",
            "建立使馆外交修正（由原生结算，不可撤销）")
        TradeOfferType.Gold -> listOf("立即从 ${owner.civName} 转移 ${offer.amount} 金币给 ${partner.civName}")
        TradeOfferType.Gold_Per_Turn -> listOf(
            "${offer.duration} 回合内每回合从 ${owner.civName} 向 ${partner.civName} 支付 ${offer.amount} 金币",
            "到期后由原生回合逻辑自动停止")
        TradeOfferType.Treaty -> when (offer.name) {
            Constants.peaceTreaty -> listOf("双方立即停战", "和平条约持续 ${offer.duration} 回合")
            Constants.researchAgreement -> listOf(
                "双方各支付 ${offer.amount} 金币（只结算一次）",
                "${offer.duration} 回合内科研协议生效")
            Constants.defensivePact -> listOf("${offer.duration} 回合内共同防御；一方被宣战另一方按原生规则参战")
            else -> listOf("签署 ${offer.name}")
        }
        TradeOfferType.Agreement -> listOf("${offer.duration} 回合内 ${partner.civName} 单位可以进入 ${owner.civName} 领土")
        TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> listOf(
            "${offer.duration} 回合内 ${partner.civName} 获得 ${offer.amount} 份 ${offer.name}",
            "到期由原生回合逻辑收回")
        TradeOfferType.City -> listOf(
            "城市 ${cityName(offer.name)} 立即归属 ${partner.civName}",
            "城内单位按原生规则传送到最近可移动地块")
        TradeOfferType.WarDeclaration -> listOf("${owner.civName} 立即对 ${thirdName(offer.name)} 宣战（直接/加入/团队战争由原生判定）")
        TradeOfferType.PeaceProposal -> listOf("${owner.civName} 与 ${thirdName(offer.name)} 立即停战")
        TradeOfferType.Technology -> emptyList()
        TradeOfferType.Introduction -> emptyList()
        TradeOfferType.Stockpiled_Resource -> emptyList()
    }

    private fun risks(offer: TradeOffer, owner: Civilization, partner: Civilization): List<String> = when (offer.type) {
        TradeOfferType.Embassy -> listOf("使馆建立后不可撤销，会永久改变双方外交修正")
        TradeOfferType.Gold -> listOf("确认前余额变化会使整笔提案失效")
        TradeOfferType.Gold_Per_Turn -> listOf("付款方每回合金不足时原生判定整笔无效；战争会中断并清除该协议")
        TradeOfferType.Treaty -> when (offer.name) {
            Constants.peaceTreaty -> listOf("和平后宣战冷却按原生规则重新计算；条约到期前不能再次宣战")
            Constants.researchAgreement -> listOf("任一方金币不足时原生判定整笔无效；战争会中断协议")
            Constants.defensivePact -> listOf("会触发原生宣战连锁：盟友可能被卷入战争")
            else -> emptyList()
        }
        TradeOfferType.Agreement -> listOf("协议到期或战争爆发时，境外单位按原生规则撤离")
        TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> listOf("资源来源被战争或交易中断时，原生规则会提前终止并回收")
        TradeOfferType.City -> listOf("接收后可能出现解放待决（原生 CityTraded）", "城内单位会被传送，城市建筑与人口原样接收")
        TradeOfferType.WarDeclaration -> listOf("宣战会取消相关交易；对方盟友可能连带参战")
        TradeOfferType.PeaceProposal -> listOf("只影响提案方与该第三方，不改变我方与任一方的关系")
        else -> emptyList()
    }

    private fun offerDto(offer: TradeOffer, owner: Civilization, partner: Civilization): JsonObject {
        val clause = clauseProblem(offer, owner, partner)
        val fields = linkedMapOf<String, Any?>(
            "type" to offer.type.name, "name" to displayName(offer), "label" to label(offer),
            "amount" to offer.amount,
            "duration" to offer.duration.takeIf { it > 0 },
            "immediate" to offer.type.isImmediate,
            "text" to (label(offer) + amountText(offer) + if (offer.duration > 0) "（${offer.duration} 回合）" else "（立即）"),
            "supported" to (clause == null), "problem" to (clause ?: ""),
            "effects" to effects(offer, owner, partner), "risks" to risks(offer, owner, partner))
        when (offer.type) {
            TradeOfferType.City -> {
                val city = owner.cities.firstOrNull { it.id == offer.name }
                fields["city"] = city?.let { dto("name" to it.name, "population" to it.population.population) }
            }
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> fields["resource"] = dto(
                "name" to offer.name, "owned" to owner.getResourceAmount(offer.name),
                "kind" to if (offer.type == TradeOfferType.Luxury_Resource) "Luxury" else "Strategic")
            TradeOfferType.WarDeclaration, TradeOfferType.PeaceProposal -> {
                val civ = third(offer.name)
                fields["thirdCiv"] = civ?.let { dto("civId" to it.civID, "name" to it.civName) }
            }
            else -> Unit
        }
        return dto(*fields.toList().toTypedArray())
    }

    private fun offersDto(offers: List<TradeOffer>, owner: Civilization, partner: Civilization) =
        offers.map { offerDto(it, owner, partner) }

    fun tradeDto(request: TradeRequest, other: Civilization?, index: Int, outgoing: Boolean): JsonObject {
        val token = token(request, if (outgoing) other ?: player else player, index, outgoing)
        if (other == null) return dto("civId" to null, "name" to "不可用的外交对象", "tradeToken" to token,
            "supported" to false, "problems" to listOf("外交对象不可用或尚未接触"),
            "ourOffers" to emptyList<Any>(), "theirOffers" to emptyList<Any>(),
            "effects" to emptyList<Any>(), "risks" to emptyList<Any>(), "expired" to true,
            "choices" to choices(request).map { it.dto() })
        val trade = if (outgoing) request.trade.reverse() else request.trade
        val shape = shapeProblems(trade, other)
        val expired = other.isDefeated() || !nativeValid(trade, other)
        val effects = (trade.ourOffers.flatMap { effects(it, player, other) }.map { "我方：$it" } +
            trade.theirOffers.flatMap { effects(it, other, player) }.map { "对方：$it" }).distinct()
        val risks = (trade.ourOffers.flatMap { risks(it, player, other) } +
            trade.theirOffers.flatMap { risks(it, other, player) }).distinct()
        return dto("civId" to other.civID, "name" to other.civName, "tradeToken" to token,
            "supported" to shape.isEmpty(), "problems" to shape, "expired" to expired,
            "ourOffers" to offersDto(trade.ourOffers, player, other),
            "theirOffers" to offersDto(trade.theirOffers, other, player),
            "effects" to effects, "risks" to risks,
            "choices" to if (outgoing) emptyList<Any>() else choices(request).map { it.dto() })
    }

    fun choices(request: TradeRequest): List<Choice> {
        val other = known(request.requestingCiv)
        val base = checked {
            GameSession.validateGame(game)
            ensure(other != null, "DIPLOMACY_TARGET", "外交对象不可用或尚未接触")
            ensure(other!!.isMajorCiv() && other.isAI(), "UNSUPPORTED", "此命令仅面向主要文明；城邦请使用城邦命令")
            ensure(PendingQueue(game).openableHead() == null, "PENDING_DECISION", "请先处理当前弹窗事件，再处理交易")
        }
        if (other == null) return listOf(
            Choice("accept", "接受整笔提案", base ?: problem("DIPLOMACY_TARGET", "外交对象不可用或尚未接触")),
            Choice("decline", "拒绝提案", base),
            Choice("dismiss", "清理过期提案", base))
        val shape = shapeProblems(request.trade, other)
        val expired = expired(request, other)
        val expiryReason = if (other.isDefeated()) "提案方已灭亡" else "提案已失效：余额、资源、城市归属或外交状态已变化"
        return listOf(
            Choice("accept", "接受整笔提案", base ?: when {
                shape.isNotEmpty() -> problem("UNSUPPORTED", "包含目前不支持的条款：${shape.joinToString("；")}")
                expired -> problem("CANNOT_ACCEPT_TRADE", expiryReason)
                !relationshipsCanChange() -> problem("CANNOT_ACCEPT_TRADE", "当前规则不允许改变外交关系")
                else -> null
            }),
            // 原生始终允许拒绝，过期提案也一样：拒绝会写入原生冷却，不能用清理冒充。
            Choice("decline", "拒绝提案（原生拒绝冷却）", base),
            Choice("dismiss", "清理过期提案", base ?: when {
                !expired -> problem("DIPLOMACY_REQUEST", "提案尚未过期，请选择接受或拒绝")
                else -> null
            }))
    }

    // --- 第 5 批：主动报价 ---

    /** 只列基础规则原生交易窗口能拖动、且第 4 批白名单已实现的条款。 */
    private val tradeableTypes = setOf(
        TradeOfferType.Embassy, TradeOfferType.Gold, TradeOfferType.Gold_Per_Turn,
        TradeOfferType.Treaty, TradeOfferType.Agreement,
        TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource,
        TradeOfferType.City, TradeOfferType.WarDeclaration, TradeOfferType.PeaceProposal)

    private fun candidateDto(offer: TradeOffer, owner: Civilization, partner: Civilization): JsonObject {
        val fields = offerDto(offer, owner, partner)
        val reason = when {
            offer.type == TradeOfferType.PeaceProposal && offer.amount <= 0 -> "原生规则当前不允许与该第三方议和"
            else -> fields.text("problem")
        }
        val tradable = offer.isTradable() && reason.isEmpty()
        return JsonObject(fields + mapOf(
            "rawName" to JsonPrimitive(offer.name),
            "tradable" to JsonPrimitive(tradable),
            "maxAmount" to JsonPrimitive(if (offer.type in setOf(TradeOfferType.Gold, TradeOfferType.Gold_Per_Turn,
                    TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource)) offer.amount else 1),
            "defaultDuration" to JsonPrimitive(offer.duration),
            "cost" to JsonPrimitive(if (offer.type == TradeOfferType.Treaty && offer.name == Constants.researchAgreement) offer.amount else 0),
            "reason" to JsonPrimitive(if (tradable) "" else reason)))
    }

    private fun candidates(offers: List<TradeOffer>, owner: Civilization, partner: Civilization) =
        offers.filter { it.type in tradeableTypes }.map { candidateDto(it, owner, partner) }

    /** 报价候选：金额上限、期限与费用全部来自原生 TradeLogic 的可用条款。 */
    fun options(request: JsonObject): JsonObject {
        ensure((request.keys - TradeWire.envelope - setOf("civId")).isEmpty(), "INVALID_ARGUMENT", "含未支持的交易参数")
        val other = major(request.requiredTradeText("civId"))
        val view = TradeView(player, other, GameView(game, player))
        val pending = other.tradeRequests.firstOrNull { it.requestingCiv == player.civID }
        val incoming = player.tradeRequests.firstOrNull { it.requestingCiv == other.civID }
        return dto("civId" to other.civID, "name" to other.civName,
            "canSend" to (pending == null),
            "pending" to pending?.let { token(it, other, other.tradeRequests.indexOf(it), true) },
            // 还价预填：来件按接收方视角原样返回，前端直接作为新草稿的起点。
            "incoming" to incoming?.let { dto("tradeToken" to token(it, player, 0, false),
                "draft" to rawDraft(it.trade)) },
            "ourOffers" to candidates(view.ourAvailableOffers(), player, other),
            "theirOffers" to candidates(view.theirAvailableOffers(), other, player))
    }

    /** 客户端 JSON 往返会把整数读成浮点；金额只接受整值数值，缺失才回退到原生值，绝不静默按上限发送。 */
    private fun clauseAmount(clause: JsonObject, fallback: Int): Int {
        val raw = clause["amount"] ?: return fallback
        val primitive = raw as? JsonPrimitive
        ensure(primitive != null && !primitive.isString, "INVALID_ARGUMENT", "交易金额必须是数值")
        val number = primitive!!.content.toDoubleOrNull()
            ?: throw problem("INVALID_ARGUMENT", "交易金额必须是数值")
        ensure(number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE,
            "INVALID_ARGUMENT", "交易金额必须是整数")
        return number.toInt()
    }

    private fun draftOffer(clause: JsonObject, available: List<TradeOffer>, owner: Civilization,
                           label: String, problems: MutableList<String>): TradeOffer? {
        ensure((clause.keys - setOf("type", "name", "amount", "duration")).isEmpty(), "INVALID_ARGUMENT", "草稿条款含未支持字段")
        val type = TradeOfferType.entries.firstOrNull { it.name == clause.text("type") }
            ?: throw problem("INVALID_ARGUMENT", "未知条款类型：${clause.text("type")}")
        val name = clause.text("name")
        val source = available.firstOrNull { it.type == type && it.name == name }
        if (source == null) {
            problems.add("$label：条款不可交易（${type.name} $name）")
            return null
        }
        if (!source.isTradable()) {
            problems.add("$label：原生规则当前不允许该条款（${label(source)}）")
            return null
        }
        val amount = clauseAmount(clause, source.amount)
        fun range(max: Int): Boolean {
            if (amount in 1..max) return true
            problems.add("$label：金额越界（${label(source)} 允许 1—$max，收到 $amount）")
            return false
        }
        return when (type) {
            TradeOfferType.Gold -> if (range(source.amount)) TradeOffer(source.name, type, amount, -1) else null
            TradeOfferType.Gold_Per_Turn -> if (range(source.amount)) TradeOffer(source.name, type, amount, source.duration) else null
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource ->
                if (range(source.amount)) TradeOffer(source.name, type, amount, source.duration) else null
            TradeOfferType.Embassy -> TradeOffer(source.name, type, 1, -1)
            TradeOfferType.Agreement -> TradeOffer(source.name, type, 1, source.duration)
            TradeOfferType.Treaty -> TradeOffer(source.name, type,
                if (source.name == Constants.researchAgreement) source.amount else 1, source.duration)
            TradeOfferType.City, TradeOfferType.WarDeclaration, TradeOfferType.PeaceProposal ->
                TradeOffer(source.name, type, 1, -1)
            else -> {
                problems.add("$label：基础规则不提供该条款（${type.name}）")
                null
            }
        }
    }

    private fun rawDraft(trade: Trade) = dto(
        "ourOffers" to trade.ourOffers.map { dto("type" to it.type.name, "name" to it.name, "amount" to it.amount, "duration" to it.duration) },
        "theirOffers" to trade.theirOffers.map { dto("type" to it.type.name, "name" to it.name, "amount" to it.amount, "duration" to it.duration) })

    /** 把草稿规范化为原生报价：期限与费用取原生值，条约自动双边镜像；不改对局。 */
    private fun normalizeDraft(request: JsonObject, other: Civilization): Pair<Trade, List<String>> {
        val draft = request["draft"] as? JsonObject ?: throw problem("INVALID_ARGUMENT", "缺少交易草稿")
        ensure((draft.keys - setOf("ourOffers", "theirOffers")).isEmpty(), "INVALID_ARGUMENT", "交易草稿含未支持字段")
        val view = TradeView(player, other, GameView(game, player))
        val problems = ArrayList<String>()
        fun side(key: String, available: List<TradeOffer>, owner: Civilization, label: String): List<TradeOffer> {
            val array = draft[key] ?: return emptyList()
            val clauses = array as? JsonArray ?: throw problem("INVALID_ARGUMENT", "交易草稿格式无效：$key")
            return clauses.mapNotNull { element ->
                val clause = element as? JsonObject ?: throw problem("INVALID_ARGUMENT", "交易草稿条款格式无效")
                draftOffer(clause, available, owner, label, problems)
            }
        }
        val ours = side("ourOffers", view.ourAvailableOffers(), player, "我方").toMutableList()
        val theirs = side("theirOffers", view.theirAvailableOffers(), other, "对方").toMutableList()
        // 条约由原生只执行一侧，必须双边同条件；缺失的一侧按对方原生可用条款补齐。
        fun mirror(from: List<TradeOffer>, into: MutableList<TradeOffer>, available: List<TradeOffer>, label: String) {
            for (offer in from.filter { it.type == TradeOfferType.Treaty }) {
                if (into.any { it.type == TradeOfferType.Treaty && it.name == offer.name }) continue
                val source = available.firstOrNull { it.type == TradeOfferType.Treaty && it.name == offer.name }
                if (source == null || !source.isTradable()) {
                    problems.add("$label：缺少可镜像的条约（${offer.name}）")
                    continue
                }
                into.add(TradeOffer(source.name, TradeOfferType.Treaty,
                    if (source.name == Constants.researchAgreement) source.amount else 1, source.duration))
            }
        }
        mirror(ours, theirs, view.theirAvailableOffers(), "对方")
        mirror(theirs, ours, view.ourAvailableOffers(), "我方")
        val trade = Trade().apply { ourOffers.addAll(ours); theirOffers.addAll(theirs) }
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) problems.add("提案没有任何条款")
        else problems.addAll(shapeProblems(trade, other))
        if (problems.isEmpty() && !nativeValid(trade, other)) problems.add("原生规则判定提案无效")
        return trade to problems
    }

    private fun proposalToken(trade: Trade, other: Civilization) = fingerprint("proposal", other.civID, 0, player.civID,
        dto("ourOffers" to rawOffers(trade.ourOffers), "theirOffers" to rawOffers(trade.theirOffers)))

    /** 报价预览：返回规范化后的最终条款、即时效果与风险，并给出绑定的票据。 */
    fun preview(request: JsonObject): JsonObject {
        ensure((request.keys - TradeWire.envelope - setOf("civId", "draft")).isEmpty(), "INVALID_ARGUMENT", "含未支持的交易参数")
        val other = major(request.requiredTradeText("civId"))
        val (trade, problems) = normalizeDraft(request, other)
        val effects = (trade.ourOffers.flatMap { effects(it, player, other) }.map { "我方：$it" } +
            trade.theirOffers.flatMap { effects(it, other, player) }.map { "对方：$it" }).distinct()
        val risks = (trade.ourOffers.flatMap { risks(it, player, other) } +
            trade.theirOffers.flatMap { risks(it, other, player) }).distinct()
        return dto("civId" to other.civID, "name" to other.civName,
            "tradeToken" to proposalToken(trade, other), "supported" to problems.isEmpty(), "problems" to problems,
            "draft" to rawDraft(trade),
            "ourOffers" to offersDto(trade.ourOffers, player, other),
            "theirOffers" to offersDto(trade.theirOffers, other, player),
            "effects" to effects, "risks" to risks)
    }

    /** 发送：新提案进入对方 tradeRequests，等 AI 在其回合决定；发送成功不代表成交。 */
    fun send(request: JsonObject): () -> Unit {
        ensure((request.keys - TradeWire.envelope - setOf("civId", "draft", "tradeToken")).isEmpty(), "INVALID_ARGUMENT", "含未支持的交易参数")
        val other = major(request.requiredTradeText("civId"))
        ensure(other.tradeRequests.none { it.requestingCiv == player.civID }, "CANNOT_PROPOSE_TRADE", "已有发给此文明的未决提案，请先撤回")
        ensure(PendingQueue(game).openableHead() == null, "PENDING_DECISION", "请先处理当前弹窗事件，再发送交易")
        val (trade, problems) = normalizeDraft(request, other)
        ensure(problems.isEmpty(), "UNSUPPORTED", "提案无法发送：${problems.joinToString("；")}")
        ensure(request.requiredTradeText("tradeToken") == proposalToken(trade, other), "DIPLOMACY_REQUEST", "报价已变化，请重新确认")
        return {
            val view = TradeView(player, other, GameView(game, player))
            view.setStagedTrade(trade)
            ensure(view.tryProposeStagedTrade(), "DIPLOMACY_REQUEST", "未能创建提案")
        }
    }

    /** 撤回：只移除本方的未决提案并恢复资源可用性，不写拒绝冷却。 */
    fun withdraw(request: JsonObject): () -> Unit {
        ensure((request.keys - TradeWire.envelope - setOf("civId", "tradeToken")).isEmpty(), "INVALID_ARGUMENT", "含未支持的交易参数")
        val other = major(request.requiredTradeText("civId"))
        val entry = other.tradeRequests.withIndex().firstOrNull { it.value.requestingCiv == player.civID }
            ?: throw problem("DIPLOMACY_REQUEST", "没有对应的已发提案")
        ensure(request.requiredTradeText("tradeToken") == token(entry.value, other, entry.index, true),
            "DIPLOMACY_REQUEST", "提案已变化，请重新确认")
        ensure(supported(entry.value.trade.reverse(), other), "UNSUPPORTED", "该提案不能安全撤回，请使用原客户端处理")
        return {
            ensure(TradeView(player, other, GameView(game, player)).tryRetractOffer(),
                "DIPLOMACY_REQUEST", "提案已不存在")
        }
    }

    /** 还价：移除入站提案但不施加拒绝冷却；原条款由界面预填为本地草稿。 */
    fun renegotiate(request: JsonObject): () -> Unit {
        ensure((request.keys - TradeWire.envelope - setOf("tradeToken")).isEmpty(), "INVALID_ARGUMENT", "含未支持的交易参数")
        val pending = player.tradeRequests.firstOrNull() ?: throw problem("DIPLOMACY_REQUEST", "没有当前交易提案")
        ensure(request.requiredTradeText("tradeToken") == token(pending, player, 0, false),
            "DIPLOMACY_REQUEST", "提案已变化，请重新确认")
        ensure(PendingQueue(game).openableHead() == null, "PENDING_DECISION", "请先处理当前弹窗事件，再进入还价")
        return {
            player.tradeRequests.remove(pending)
            player.cache.updateCivResources()
        }
    }

    fun prepare(request: JsonObject): () -> Unit {
        GameSession.validateGame(game)
        val action = TradeWire.fromWire(request.text("action")) ?: throw problem("UNSUPPORTED", "未接入的交易命令")
        ensure((request.keys - TradeWire.envelope - action.keys).isEmpty(), "INVALID_ARGUMENT", "含未支持的交易参数")
        val token = request.requiredTradeText("tradeToken")
        val choice = request.requiredTradeText("choice")
        ensure(choice in choiceIds, "INVALID_ARGUMENT", "不支持的交易处置选项")
        val pending = player.tradeRequests.firstOrNull() ?: throw problem("DIPLOMACY_REQUEST", "没有当前交易提案")
        ensure(token == token(pending, player, 0, false), "DIPLOMACY_REQUEST", "提案已变化，请重新确认")
        val option = choices(pending).firstOrNull { it.id == choice }
            ?: throw problem("INVALID_ARGUMENT", "不支持的交易处置选项")
        option.problem?.let { throw it }
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
}

private fun JsonObject.requiredTradeText(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotBlank() }?.content
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少字符串参数或类型错误：$key")

/** 接收与主动报价的单一命令名单；宣战与外交事件仍在 [DiplomacyWire]。 */
internal enum class TradeWire(val wireName: String, val keys: Set<String>) {
    Decision("diplomacyTradeDecision", setOf("tradeToken", "choice")),
    Send("diplomacyTradeSend", setOf("civId", "draft", "tradeToken")),
    Withdraw("diplomacyTradeWithdraw", setOf("civId", "tradeToken")),
    Renegotiate("diplomacyTradeRenegotiate", setOf("tradeToken")),
    ;

    companion object {
        val envelope = setOf("protocol", "session", "revision", "requestId", "action")
        fun fromWire(name: String) = entries.firstOrNull { it.wireName == name }
        fun contribute(registry: CommandRegistry, session: GameSession) {
            fun commands(ctx: CommandContext) = TradeCommands(session.requireGame(), ctx.sessionId, ctx.revision)
            registry.query("diplomacyTradeOptions") { commands(it).options(it.request) }
            registry.query("diplomacyTradePreview") { commands(it).preview(it.request) }
            for (action in entries) {
                registry.effect(action.wireName) { ctx ->
                    val trade = commands(ctx)
                    when (action) {
                        Decision -> trade.prepare(ctx.request)
                        Send -> trade.send(ctx.request)
                        Withdraw -> trade.withdraw(ctx.request)
                        Renegotiate -> trade.renegotiate(ctx.request)
                    }
                }
            }
        }
    }
}
