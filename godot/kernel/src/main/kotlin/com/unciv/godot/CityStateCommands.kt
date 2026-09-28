package com.unciv.godot

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.CityStateFunctions
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 玩家可见城邦边界；报价／可行性只读，所有玩法效果委托原生 CityStateFunctions／DiplomacyManager。 */
internal class CityStateCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private data class Choice(val id: String, val label: String, val problem: GatewayError?, val description: String = "") {
        fun dto() = dto("id" to id, "label" to label, "enabled" to (problem == null),
            "reason" to (problem?.message ?: ""), "description" to description)
    }
    private fun problem(code: String, text: String) = GatewayError(code, text)
    private fun ensure(ok: Boolean, code: String, text: String) { if (!ok) throw problem(code, text) }
    private fun checked(check: () -> Unit): GatewayError? = try { check(); null } catch (e: GatewayError) { e }
    private fun ordinary() { GameSession.validateGame(game); CombatCommands(game).ensureNoBlockingBattleDecision() }
    private fun relationshipsCanChange() = !game.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)

    /** 只接受已接触的存活城邦；未接触或已消灭的对象不向 DTO 暴露、也不能作为命令目标。 */
    private fun cityState(id: String): Civilization = game.civilizations.firstOrNull {
        it.civID == id && it != player && it.isCityState && !it.isDefeated() && player.knows(it)
            && it.getDiplomacyManager(player) != null
    } ?: throw problem("DIPLOMACY_TARGET", "城邦不可用或尚未接触")

    private fun displayName(civ: Civilization) = if (civ == player || player.knows(civ)) civ.civName else "未知文明"

    private fun fingerprint(kind: String, target: Civilization, contents: JsonObject): String {
        val text = dto("session" to session, "revision" to revision, "gameId" to game.gameId, "player" to player.civID,
            "kind" to kind, "target" to target.civID, "contents" to contents).toString()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // 报价与执行同源：预览值全部来自原生只读函数，token 绑定报价内容，状态变化后旧票据失效。
    private fun giftInfluence(other: Civilization, amount: Int) = other.cityStateFunctions.influenceGainedByGift(player, amount)
    private fun giftToken(other: Civilization, amount: Int) = fingerprint("cityStateGift", other,
        dto("amount" to amount, "influence" to giftInfluence(other, amount)))
    private fun tributeModifiers(other: Civilization, worker: Boolean) =
        other.cityStateFunctions.getTributeModifiers(player, worker, requireWholeList = true)
    private fun tributeWillingness(other: Civilization, worker: Boolean) =
        other.cityStateFunctions.getTributeWillingness(player, worker)
    private fun tributeToken(other: Civilization, kind: String) = fingerprint("cityStateTribute", other,
        dto("kind" to kind, "goldAmount" to other.cityStateFunctions.goldGainedByTribute(),
            "willingness" to tributeWillingness(other, kind == "worker")))
    private fun marriageAvailable() = player.getMatchingUniques(UniqueType.CityStateCanBeBoughtForGold).any()
    private fun marriageCost(other: Civilization) = other.cityStateFunctions.getDiplomaticMarriageCost()
    private fun marriageToken(other: Civilization) = fingerprint("cityStateMarriage", other, dto("cost" to marriageCost(other)))

    private fun giftProblem(other: Civilization, amount: Int): GatewayError? = checked {
        ordinary()
        ensure(!player.isAtWarWith(other), "CANNOT_GIFT", "与城邦交战中不能赠送金币")
        ensure(player.gold >= amount, "CANNOT_GIFT", "金币不足，无法赠送 $amount")
    }
    private fun tributeProblem(other: Civilization, worker: Boolean): GatewayError? = checked {
        ordinary()
        ensure(!player.isAtWarWith(other), "CANNOT_TRIBUTE", "与城邦交战中不能索取贡品")
        ensure(tributeWillingness(other, worker) >= 0, "CANNOT_TRIBUTE",
            if (worker) "贡品意愿不足，无法索取工人（需不低于 0，且小城邦不提供工人）" else "贡品意愿不足（需不低于 0）；请在城邦附近部署军事力量")
    }
    private fun pledgeProblem(other: Civilization): GatewayError? = checked {
        ordinary()
        ensure(other.cityStateFunctions.otherCivCanPledgeProtection(player), "CANNOT_PLEDGE",
            "当前不能承诺保护：需影响力不低于 0、非交战、未处于撤销冷却且尚未保护")
    }
    private fun revokeProblem(other: Civilization): GatewayError? = checked {
        ordinary()
        ensure(other.cityStateFunctions.otherCivCanWithdrawProtection(player), "CANNOT_REVOKE",
            "当前不能撤销保护：未处于保护状态，或承诺后的 10 回合冷却未结束")
    }
    private fun declareWarProblem(other: Civilization): GatewayError? = checked {
        ordinary()
        ensure(relationshipsCanChange() && player.getDiplomacyManager(other)!!.canDeclareWar(),
            "CANNOT_DECLARE_WAR", "当前不能宣战：请检查战争状态、和平条约及规则限制")
    }
    /** 对齐原生 getNegotiatePeaceCityStateButton：交战中、无宣战冷却、未与其盟友交战时可立即议和。 */
    private fun peaceProblem(other: Civilization): GatewayError? = checked {
        ordinary()
        ensure(relationshipsCanChange() && player.isAtWarWith(other), "CANNOT_NEGOTIATE_PEACE", "当前未与该城邦交战")
        ensure(!other.getDiplomacyManager(player)!!.hasFlag(DiplomacyFlags.DeclaredWar),
            "CANNOT_NEGOTIATE_PEACE", "宣战后的议和冷却尚未结束")
        val ally = other.allyCiv
        ensure(ally == null || !player.isAtWarWith(ally), "CANNOT_NEGOTIATE_PEACE", "正与该城邦的盟友交战，需先结束那场战争")
    }
    private fun marriageProblem(other: Civilization): GatewayError? = checked {
        ordinary()
        ensure(marriageAvailable(), "UNSUPPORTED", "当前文明不具备外交联姻能力")
        ensure(other.cityStateFunctions.canBeMarriedBy(player), "CANNOT_MARRY",
            "不满足联姻条件：需为该城邦盟友、联姻冷却已结束且金币足够")
    }

    private fun warWarnings(other: Civilization) = buildList {
        add("宣战会大幅降低该城邦影响力，攻击后可能受到其他文明的战争狂热惩罚。")
        for (protector in other.cityStateFunctions.getProtectorCivs().filter { it != player })
            add("保护者 ${displayName(protector)} 将收到事件，可能对我方宣战。")
        other.allyCiv?.takeIf { it != player }?.let { add("其盟友 ${displayName(it)} 对我方评价将下降，并可能加入战争。") }
    }

    /** 与原生 CityStateDiplomacyTable.getImprovableResourceTiles 同口径的只读判断；用于显示 UNSUPPORTED 入口。 */
    private fun hasImprovableTiles(other: Civilization): Boolean {
        if (other.cities.isEmpty()) return false
        val improvements = game.ruleset.tileImprovements.values.filter { it.turnsToBuild != -1 }
        return other.cities.flatMap { it.getTiles() }.any { tile ->
            val resource = tile.tileResource
            other.canSeeResource(resource) && resource.resourceType != ResourceType.Bonus
                && (tile.improvement == null || !resource.isImprovedBy(tile.improvement!!))
                && improvements.any { resource.isImprovedBy(it.name) && tile.improvementFunctions.canBuildImprovement(it, other.state) }
        }
    }

    private fun bonusTexts(other: Civilization, level: RelationshipLevel) =
        CityStateFunctions.getCityStateBonuses(other.cityStateType, level)
            .filterNot { it.isHiddenToUsers() }.map { it.getDisplayText() }.toList()

    /** 只读城邦节点；不得调用 updateAllyCivForCityState 等有副作用的原生函数。 */
    fun cityStateDto(other: Civilization): JsonObject {
        val theirs = other.getDiplomacyManager(player)!!
        val level = theirs.relationshipIgnoreAfraid()
        val goldAmount = other.cityStateFunctions.goldGainedByTribute()
        return dto(
            "cityStateType" to other.cityStateType.name,
            "personality" to other.cityStatePersonality.name,
            "influence" to theirs.getInfluence(),
            "relationship" to theirs.relationshipLevel().name,
            "turnsToRelationshipChange" to if (level >= RelationshipLevel.Friend) theirs.getTurnsToRelationshipChange().takeIf { it != 0 } else null,
            "ally" to other.allyCiv?.let { ally ->
                dto("name" to displayName(ally), "influence" to other.getDiplomacyManager(ally)?.getInfluence())
            },
            "protectors" to other.cityStateFunctions.getProtectorCivs().map { displayName(it) },
            "friendBonuses" to bonusTexts(other, RelationshipLevel.Friend),
            "allyBonuses" to bonusTexts(other, RelationshipLevel.Ally),
            "resources" to other.cityStateFunctions.getCityStateResourcesForAlly()
                .filter { it.resource.resourceType != ResourceType.Bonus }
                .map { dto("name" to it.resource.name, "amount" to it.amount) },
            "uniqueUnit" to other.cityStateUniqueUnit,
            "quests" to other.questManager.getAssignedQuestsFor(player).map { quest ->
                dto("name" to quest.quest.name, "influence" to quest.quest.influence,
                    "description" to quest.getDescription(),
                    "remainingTurns" to if (quest.doesExpire()) quest.getRemainingTurns() else null,
                    "score" to if (quest.isGlobal())
                        quest.assignerCiv.questManager.getScoreStringForGlobalQuest(quest).takeIf { it.isNotEmpty() } else null)
            }.toList(),
            "wars" to other.getKnownCivs().filter { it != player && other.questManager.isWarWithMajorActive(it) }.map { target ->
                dto("name" to displayName(target), "unitsToKill" to other.questManager.unitsToKill(target),
                    "killed" to if (player.knows(target)) other.questManager.unitsKilledSoFar(target, player) else null)
            }.toList(),
            "gifts" to giftAmounts.map { amount ->
                val influence = giftInfluence(other, amount)
                dto("amount" to amount, "influence" to influence, "token" to giftToken(other, amount),
                    "choice" to Choice("gift$amount", "赠送 $amount 金币（+$influence 影响力）", giftProblem(other, amount),
                        "支付 $amount 金币，城邦获得同额金币；按原生规则获得约 $influence 点影响力，并可能推进相关任务。").dto())
            },
            "tribute" to dto(
                "goldAmount" to goldAmount,
                "goldModifiers" to tributeModifiers(other, false).map { dto("name" to it.key, "value" to it.value) },
                "workerModifiers" to tributeModifiers(other, true).map { dto("name" to it.key, "value" to it.value) },
                "goldWillingness" to tributeWillingness(other, false),
                "workerWillingness" to tributeWillingness(other, true),
                "goldToken" to tributeToken(other, "gold"), "workerToken" to tributeToken(other, "worker"),
                "gold" to Choice("tributeGold", "索取 $goldAmount 金币贡品（-15 影响力）", tributeProblem(other, false),
                    "恐吓城邦交出 $goldAmount 金币；影响力 -15，20 回合内再次索取意愿大幅降低，保护者将收到事件。").dto(),
                "worker" to Choice("tributeWorker", "索取工人（-50 影响力）", tributeProblem(other, true),
                    "恐吓城邦交出一名工人（在其首都附近出现）；影响力 -50，保护者将收到事件。").dto()),
            "marriage" to if (!marriageAvailable()) null else {
                val cost = marriageCost(other)
                dto("cost" to cost, "token" to marriageToken(other),
                    "choice" to Choice("marriage", "外交联姻（$cost 金币）", marriageProblem(other),
                        "支付 $cost 金币将该城邦和平纳入我方：城市转为傀儡并逐城弹窗处置（吞并／保留傀儡），其全部单位归我方，城邦文明消亡且不可再被解放。").dto())
            },
            "actions" to dto(
                "pledge" to Choice("pledge", "承诺保护", pledgeProblem(other),
                    "承诺保护该城邦：10 回合内不能撤销；其被攻击或被索取贡品时我方会收到事件并可选择回应。").dto(),
                "revoke" to Choice("revoke", "撤销保护", revokeProblem(other),
                    "撤销保护承诺：影响力 -20，20 回合内不能重新承诺。").dto(),
                "declareWar" to Choice("cityStateDeclareWar", "宣战", declareWarProblem(other),
                    "对该城邦宣战。\n" + warWarnings(other).joinToString("\n")).dto(),
                "negotiatePeace" to Choice("negotiatePeace", "议和", peaceProblem(other),
                    "与该城邦签署和平条约；与主要文明不同，城邦议和经确认后立即生效。").dto(),
                "warWarnings" to warWarnings(other),
                "giftImprovement" to if (hasImprovableTiles(other))
                    Choice("giftImprovement", "馈赠改良", problem("UNSUPPORTED", "改良馈赠尚未接入，请保存后使用原客户端处理"), "").dto() else null))
    }

    fun prepare(request: JsonObject): () -> Unit {
        GameSession.validateGame(game)
        val action = request.text("action")
        val keys = when (action) {
            "cityStateGiftGold" -> setOf("civId", "amount", "cityStateToken")
            "cityStatePledgeProtection", "cityStateRevokeProtection", "cityStateDeclareWar", "cityStateNegotiatePeace" -> setOf("civId")
            "cityStateDemandTribute" -> setOf("civId", "kind", "cityStateToken")
            "cityStateMarriage" -> setOf("civId", "cityStateToken")
            else -> throw problem("UNSUPPORTED", "未接入的城邦命令")
        }
        ensure((request.keys - envelope - keys).isEmpty(), "INVALID_ARGUMENT", "含未支持的城邦参数")
        val other = cityState(request.requiredCityStateText("civId"))
        when (action) {
            "cityStateGiftGold" -> {
                val amount = request.requiredCityStateInt("amount")
                ensure(amount in giftAmounts, "INVALID_ARGUMENT", "赠金金额仅支持 250／500／1000")
                ensure(request.requiredCityStateText("cityStateToken") == giftToken(other, amount),
                    "CITY_STATE_TOKEN", "报价已变化，请刷新后重新确认")
                giftProblem(other, amount)?.let { throw it }
                return { other.cityStateFunctions.receiveGoldGift(player, amount) }
            }
            "cityStatePledgeProtection" -> {
                pledgeProblem(other)?.let { throw it }
                return { other.cityStateFunctions.addProtectorCiv(player) }
            }
            "cityStateRevokeProtection" -> {
                revokeProblem(other)?.let { throw it }
                return { other.cityStateFunctions.removeProtectorCiv(player) }
            }
            "cityStateDemandTribute" -> {
                val kind = request.requiredCityStateText("kind")
                val worker = when (kind) {
                    "gold" -> false
                    "worker" -> true
                    else -> throw problem("INVALID_ARGUMENT", "贡品类型仅支持 gold 或 worker")
                }
                ensure(request.requiredCityStateText("cityStateToken") == tributeToken(other, kind),
                    "CITY_STATE_TOKEN", "报价已变化，请刷新后重新确认")
                tributeProblem(other, worker)?.let { throw it }
                return { if (worker) other.cityStateFunctions.tributeWorker(player) else other.cityStateFunctions.tributeGold(player) }
            }
            "cityStateDeclareWar" -> {
                declareWarProblem(other)?.let { throw it }
                return { player.getDiplomacyManager(other)!!.declareWar() }
            }
            "cityStateNegotiatePeace" -> {
                peaceProblem(other)?.let { throw it }
                return {
                    // 与原生 Negotiate Peace 按钮一致：纯和平条约经 TradeLogic 立即成交，不发送提案。
                    val logic = TradeLogic(player, other)
                    logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
                    logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
                    logic.acceptTrade()
                }
            }
            "cityStateMarriage" -> {
                marriageProblem(other)?.let { throw it }
                ensure(request.requiredCityStateText("cityStateToken") == marriageToken(other),
                    "CITY_STATE_TOKEN", "报价已变化，请刷新后重新确认")
                return {
                    // 原客户端先取城市列表再执行联姻，随后逐城弹出吞并／傀儡处置。
                    val cities = other.cities.toList()
                    other.cityStateFunctions.diplomaticMarriage(player)
                    cities.forEach { player.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, it.id)) }
                }
            }
            else -> throw problem("UNSUPPORTED", "未接入的城邦命令")
        }
    }

    companion object {
        val giftAmounts = listOf(250, 500, 1000)
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
    }
}

private fun JsonObject.requiredCityStateText(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotBlank() }?.content
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少字符串参数或类型错误：$key")
private fun JsonObject.requiredCityStateInt(key: String): Int = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString }?.intOrNull
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少 JSON 整数参数：$key")
