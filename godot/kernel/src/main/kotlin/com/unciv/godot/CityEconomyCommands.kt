package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.view.CityView
import com.unciv.view.GameView
import com.unciv.view.TileView
import kotlinx.serialization.json.*

/** 城市经济边界：只读报价与写前校验共用规则，实际效果完全交由原生 View 执行。 */
internal class CityEconomyCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val view by lazy { GameView(game, player) }
    private fun city(id: String) = view.civView.cities().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方城市")
    /** 只读查询原生 City 状 flags（如是否沿海），不用于执行。 */
    private fun logicCity(id: String) = player.cities.firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方城市")

    private fun requireWritable() {
        GameSession.validateGame(game)
        CombatCommands(game).ensureNoBlockingBattleDecision()
    }
    private fun blockedReason(): String = try { requireWritable(); "" } catch (e: GatewayError) { e.message }
    private fun construction(name: String): IConstruction = game.ruleset.buildings[name] ?: game.ruleset.units[name]
        ?: PerpetualConstruction.perpetualConstructionsMap[name]
        ?: throw GatewayError("INVALID_ARGUMENT", "未知生产项目：$name")

    /**
     * 本期可购买货币：金币与信仰。购买资格、价格递增、伟人／宗教单位写入后的移动力等全部由原生
     * `canBePurchasedWithStat`／`getStatBuyCost`／`isConstructionPurchaseAllowed`／`purchaseConstruction` 决定。
     */
    private val purchaseStats = listOf(Stat.Gold, Stat.Faith)
    private fun statLabel(stat: Stat) = when (stat) {
        Stat.Gold -> "金币"
        Stat.Faith -> "信仰"
        else -> stat.name
    }

    /** 不通过创建 MapUnit 试探能力，避免只读查询分配单位 ID。 */
    private fun unsupported(c: IConstruction): String = when (c) {
        is PerpetualConstruction -> "持续生产项目不能购买"
        is Building -> if (c.hasUnique(UniqueType.CreatesOneImprovement, GameContext.IgnoreConditionals))
            "需要指定改良地块的建筑购买尚未接入" else ""
        is BaseUnit -> when {
            c.isAirUnit() -> "空军购买尚未接入"
            c.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals) -> "核武购买尚未接入"
            else -> ""
        }
        else -> "尚未接入该项目购买"
    }

    private data class Quote(val cost: Int?, val reason: String) {
        val enabled get() = reason.isEmpty()
    }
    private fun tileQuote(cv: CityView, tv: TileView?, blocked: String): Quote {
        // 先遮蔽不可见坐标，后读取归属／价格，避免把错误信息变成隐藏状态探针。
        if (tv == null || !tv.isExplored() || !tv.isVisible()) return Quote(null, "目标不可购买或当前不可见")
        if (!cv.canBuyTile(tv)) return Quote(null, when {
            cv.isPuppet() -> "傀儡城市不能购买地块"
            cv.isBeingRazed() -> "焚城期间不能购买地块"
            cv.isInResistance() -> "抵抗期间不能购买地块"
            !cv.isInRange(tv) -> "地块超出城市工作范围"
            tv.owningCity() != null -> "地块已有归属"
            else -> "地块必须与本城领土相邻"
        })
        val cost = cv.getGoldCostOfTile(tv)
        return Quote(cost, when {
            blocked.isNotEmpty() -> blocked
            !cv.viewingCiv().hasStatToBuy(Stat.Gold, cost) -> "金币不足"
            else -> ""
        })
    }
    private fun purchaseQuote(cv: CityView, c: IConstruction, blocked: String, stat: Stat): Quote {
        val unsupported = unsupported(c)
        if (unsupported.isNotEmpty()) return Quote(null, unsupported)
        c as INonPerpetualConstruction
        if (!cv.canBePurchasedWithStat(c, stat)) return Quote(null, "该项目不能用${statLabel(stat)}购买")
        val cost = cv.constructions.getStatBuyCost(c, stat) ?: return Quote(null, "该项目没有${statLabel(stat)}报价")
        val allowed = cv.constructions.isConstructionPurchaseAllowed(c, stat, cost)
        return Quote(cost, when {
            blocked.isNotEmpty() -> blocked
            allowed -> ""
            cv.isPuppet() && !cv.hasMatchingUnique(UniqueType.MayBuyConstructionsInPuppets) -> "此傀儡城市不允许购买生产"
            cv.isInResistance() -> "抵抗期间不能购买生产"
            c is BaseUnit && c.isWaterUnit && !logicCity(cv.id).isNaval() -> "内陆城市不能购买海军单位"
            cv.constructions.isConstructionPurchaseBlockedByUnit(c) -> "城市对应单位槽位已占用"
            !cv.isGodModeEnabled() && cost > cv.getStatReserve(stat) -> "${statLabel(stat)}不足"
            else -> "原生规则不允许购买：请检查科技、资源及已有建筑"
        })
    }
    /** 统一报价：每种可用货币一项，前端不自行换算价格或推断资格。 */
    private fun quotesDto(cv: CityView, c: IConstruction, blocked: String): JsonArray = JsonArray(purchaseStats.map { stat ->
        val quote = purchaseQuote(cv, c, blocked, stat)
        dto("stat" to stat.name, "cost" to quote.cost, "enabled" to quote.enabled, "reason" to quote.reason)
    })
    private fun saleQuote(cv: CityView, b: Building, blocked: String): Quote {
        val cost = if (b.isSellable()) cv.getGoldForSellingBuilding(b.name) else null
        return Quote(cost, when {
            !cv.constructions.isBuilt(b.name) -> "本城尚未建成该建筑"
            !b.isSellable() -> "奇观或不可出售建筑不能出售"
            cv.hasFreeBuilding(b) -> "免费建筑不能出售"
            cv.isPuppet() -> "傀儡城市不能出售建筑"
            cv.hasSoldBuildingThisTurn() && !cv.isGodModeEnabled() -> "本城本回合已经出售过建筑"
            blocked.isNotEmpty() -> blocked
            else -> ""
        })
    }

    fun prepareBuyTile(request: JsonObject): () -> Unit {
        val id = request.requiredText("cityId")
        val at = HexCoord(request.requiredInt("x"), request.requiredInt("y"))
        val cv = city(id)
        requireWritable()
        val tv = game.tileMap.tileList.firstOrNull { it.position == at }?.let { view.getTile(it) }
        val quote = tileQuote(cv, tv, "")
        ensure(quote.enabled, "CANNOT_BUY_TILE", quote.reason)
        return {
            ensure(cv.tryBuyTile(tv!!), "CANNOT_BUY_TILE", "购买地块失败，已回滚")
            cv.updateCityStats()
        }
    }
    fun preparePurchase(request: JsonObject): () -> Unit {
        val id = request.requiredText("cityId")
        val name = request.requiredText("name")
        val stat = Stat.entries.firstOrNull { it.name == request.requiredText("stat") }
            ?: throw GatewayError("INVALID_ARGUMENT", "未知购买货币")
        val index = request.requiredInt("queueIndex")
        val cv = city(id)
        requireWritable()
        ensure(stat in purchaseStats, "UNSUPPORTED", "本期仅接入金币与信仰购买")
        val c = construction(name)
        val queue = cv.constructions.constructionQueue
        ensure(index == -1 || index in queue.indices && queue[index] == name,
            "INVALID_ARGUMENT", "队列索引越界或项目名称不匹配，请重新获取报价")
        val unsupported = unsupported(c)
        ensure(unsupported.isEmpty(), "UNSUPPORTED", unsupported)
        val quote = purchaseQuote(cv, c, "", stat)
        ensure(quote.enabled, "CANNOT_PURCHASE", quote.reason)
        return {
            ensure(cv.constructions.purchaseConstruction(c as INonPerpetualConstruction, index, stat, null),
                "CANNOT_PURCHASE", "购买生产失败，已回滚")
            cv.updateCityStats()
        }
    }
    fun prepareSellBuilding(request: JsonObject): () -> Unit {
        val id = request.requiredText("cityId")
        val name = request.requiredText("name")
        val cv = city(id)
        requireWritable()
        val b = game.ruleset.buildings[name] ?: throw GatewayError("INVALID_ARGUMENT", "未知建筑：$name")
        val quote = saleQuote(cv, b, "")
        ensure(quote.enabled, "CANNOT_SELL_BUILDING", quote.reason)
        return {
            ensure(cv.trySellBuilding(b), "CANNOT_SELL_BUILDING", "出售建筑失败，已回滚")
            cv.updateCityStats()
        }
    }

    fun economyDto(cv: CityView): JsonObject {
        val blocked = blockedReason()
        fun purchaseDto(c: IConstruction, index: Int? = null): JsonObject = dto("index" to index, "name" to c.name,
            "type" to if (c is BaseUnit) "unit" else if (c is Building) "building" else "perpetual",
            "supported" to unsupported(c).isEmpty(), "quotes" to quotesDto(cv, c, blocked))
        return dto("gold" to cv.getStatReserve(Stat.Gold), "faith" to cv.getStatReserve(Stat.Faith),
            "godMode" to cv.isGodModeEnabled(), "blockedReason" to blocked,
            "hasSoldBuildingThisTurn" to cv.hasSoldBuildingThisTurn(),
            "buyTiles" to game.tileMap.tileList.mapNotNull {
                val tv = cv.tileView(it)
                if (!cv.isInRange(tv) || !tv.isExplored() || !tv.isVisible()) return@mapNotNull null
                val quote = tileQuote(cv, tv, blocked)
                dto("x" to it.position.x, "y" to it.position.y, "cost" to quote.cost,
                    "enabled" to quote.enabled, "reason" to quote.reason)
            },
            "purchaseOptions" to (game.ruleset.units.values + game.ruleset.buildings.values)
                .filter { cv.constructions.shouldBeDisplayed(it) }.map { purchaseDto(it) },
            "queuePurchases" to cv.constructions.constructionQueue.mapIndexed { index, name -> purchaseDto(construction(name), index) },
            "buildings" to cv.getBuiltBuildings().sortedBy { it.name }.map { b ->
                val quote = saleQuote(cv, b, blocked)
                dto("name" to b.name, "sellGold" to quote.cost, "isFree" to cv.hasFreeBuilding(b),
                    "sellable" to b.isSellable(), "canSell" to quote.enabled, "reason" to quote.reason)
            }.toList())
    }

    private fun ensure(condition: Boolean, code: String, message: String) {
        if (!condition) throw GatewayError(code, message)
    }
}

internal fun contributeCityEconomy(registry: CommandRegistry, session: GameSession) {
    registry.effect("cityBuyTile") { CityEconomyCommands(session.requireGame()).prepareBuyTile(it.request) }
    registry.effect("cityPurchase") { CityEconomyCommands(session.requireGame()).preparePurchase(it.request) }
    registry.effect("citySellBuilding") { CityEconomyCommands(session.requireGame()).prepareSellBuilding(it.request) }
}

/** 经济业务参数严格区分 JSON 字符串与数字，数组／null／小数在备份前拒绝。 */
private fun JsonObject.requiredText(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.isNotEmpty() }?.content
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少字符串参数或类型错误：$key")
private fun JsonObject.requiredInt(key: String): Int = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString }?.intOrNull
    ?: throw GatewayError("INVALID_ARGUMENT", "缺少整数参数或类型错误：$key")
