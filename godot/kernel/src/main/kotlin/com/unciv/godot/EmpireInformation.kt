package com.unciv.godot

import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Notification
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.stats.Stats
import com.unciv.view.GameView
import kotlinx.serialization.json.*

/** 帝国信息只读投影。统计、费用、可用性和说明取自原内核，不更新真实统计或触发通知动作。 */
internal class EmpireInformation(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val view = GameView(game, player)

    fun overview(): JsonObject = dto(
        "nation" to player.civName, "turn" to game.turns, "era" to player.tech.era.name,
        "gold" to player.gold, "culture" to player.policies.storedCulture,
        "faith" to player.religionManager.storedFaith, "happiness" to player.getHappiness(),
        "rates" to stats(player.stats.statsForNextTurn),
        "breakdown" to player.stats.getStatMapForNextTurn().map { (source, values) ->
            dto("source" to source, "stats" to stats(values))
        },
        "happinessBreakdown" to player.stats.getHappinessBreakdown().toSortedMap().map { (source, amount) ->
            dto("source" to source, "amount" to amount)
        },
        "unitSupply" to player.stats.getUnitSupply(), "unitCount" to player.units.getCivUnitsSize(),
        "supplyDeficit" to player.stats.getUnitSupplyDeficit(),
        "supplyPenaltyPercent" to player.stats.getUnitSupplyProductionPenalty(),
        "cities" to view.civView.cities().sortedBy { it.name }.map { city ->
            val production = city.currentConstructionName()
            dto("id" to city.id, "name" to city.name, "x" to city.location.x, "y" to city.location.y,
                "population" to city.getPopulationCount(), "puppet" to city.isPuppet(),
                "beingRazed" to city.isBeingRazed(),
                "production" to production,
                "turns" to if (production.isEmpty() || PerpetualConstruction.perpetualConstructionsMap.containsKey(production)) null
                    else city.constructions.turnsToConstruction(production).takeIf { it in 0..100000 },
                "stats" to stats(city.getCurrentCityStats()))
        },
        "units" to player.units.getCivUnits().sortedBy { it.id }.map { unit ->
            dto("id" to unit.id, "name" to unit.name, "x" to unit.currentTile.position.x,
                "y" to unit.currentTile.position.y, "health" to unit.health,
                "movement" to unit.currentMovement, "action" to unit.action, "due" to unit.due)
        }.toList(),
        "resources" to game.ruleset.tileResources.values.filter {
            player.tech.isRevealed(it) && (it.resourceType != ResourceType.Bonus || player.getResourceAmount(it) != 0)
        }.map { resource ->
            dto("name" to resource.name, "type" to resource.resourceType.name,
                "amount" to player.getResourceAmount(resource),
                "sources" to player.detailedCivResources.filter { it.resource == resource }.map {
                    dto("source" to it.origin, "amount" to it.amount)
                })
        }
    )

    fun research(): JsonObject = dto(
        "current" to player.tech.currentTechnologyName(), "queue" to player.tech.techsToResearch,
        "science" to player.stats.statsForNextTurn.science, "free" to player.tech.freeTechs,
        "overflow" to player.tech.getOverflowScience(),
        "entries" to game.ruleset.technologies.values.sortedWith(compareBy({ it.column?.columnNumber ?: 0 }, { it.row }, { it.name })).map { tech ->
            val researched = player.tech.isResearched(tech.name)
            val available = player.tech.canBeResearched(tech.name)
            val missing = tech.prerequisites.filterNot { player.tech.isResearched(it) }.sorted()
            val reason = when {
                VictoryCommands(game).requiresAcknowledgement() -> "请先处理胜负结算"
                researched && !tech.isContinuallyResearchable() -> "已研究"
                player.tech.isUnresearchable(tech) -> "原生规则当前不允许研究"
                missing.isNotEmpty() -> "缺少前置科技：${missing.joinToString()}"
                else -> ""
            }
            dto("name" to tech.name, "group" to tech.era(),
                "researched" to researched, "current" to (tech.name == player.tech.currentTechnologyName()),
                "repeatable" to tech.isContinuallyResearchable(),
                "enabled" to (available && reason.isEmpty()), "reason" to reason,
                "prerequisites" to tech.prerequisites.sorted(), "missing" to missing,
                "leadsTo" to game.ruleset.technologies.values.filter { tech.name in it.prerequisites }.map { it.name },
                "cost" to player.tech.costOfTech(tech.name), "progress" to player.tech.researchOfTech(tech.name),
                "turns" to if (available) player.tech.turnsToTech(tech.name) else null,
                "description" to tech.getDescription(player))
        }
    )

    fun policies(): JsonObject = dto(
        "culture" to player.policies.storedCulture, "rate" to player.stats.statsForNextTurn.culture,
        "cost" to player.policies.getCultureNeededForNextPolicy(), "free" to player.policies.freePolicies,
        "canDefer" to (player.policies.shouldOpenPolicyPicker && player.policies.freePolicies == 0
            && !VictoryCommands(game).requiresAcknowledgement()),
        "deferReason" to if (player.policies.freePolicies > 0) "免费政策仍须选择，暂缓不能解除此待办" else "仅关闭政策待办，不消耗文化",
        "entries" to game.ruleset.policies.values.sortedBy { it.getSortGroup(game.ruleset) }.map { policy ->
            val adopted = player.policies.isAdopted(policy.name)
            val missing = policy.requires.orEmpty().filterNot { player.policies.isAdopted(it) }
            val reason = when {
                VictoryCommands(game).requiresAcknowledgement() -> "请先处理胜负结算"
                adopted -> "已采用"
                policy.policyBranchType == PolicyBranchType.BranchComplete -> "完成分支后由原生规则自动获得"
                missing.isNotEmpty() -> "缺少前置政策：${missing.joinToString()}"
                game.ruleset.eras[policy.branch.era]!!.eraNumber > player.getEraNumber() -> "需要时代：${policy.branch.era}"
                !player.policies.isAdoptable(policy) -> "原生规则当前不允许采用"
                !player.policies.canAdoptPolicy() -> "文化不足且没有免费政策额度"
                else -> ""
            }
            dto("name" to policy.name, "group" to policy.branch.name, "era" to policy.branch.era,
                "kind" to policy.policyBranchType.name, "adopted" to adopted,
                "enabled" to reason.isEmpty(), "reason" to reason,
                "prerequisites" to policy.requires.orEmpty(), "missing" to missing,
                "leadsTo" to game.ruleset.policies.values.filter { policy.name in it.requires.orEmpty() }.map { it.name },
                "description" to policy.getDescription())
        }
    )

    fun notifications(): JsonObject {
        fun entries(turn: Int, current: Boolean, notifications: List<Notification>) = notifications.withIndex().reversed().map { (index, notification) ->
            dto("id" to "${if (current) "current" else "history"}:$turn:$index", "turn" to turn,
                "current" to current, "category" to notification.category.name, "text" to notification.text,
                "targets" to notificationTargets(notification))
        }
        return dto("entries" to (entries(game.turns, true, player.notifications) +
            player.notificationsLog.sortedByDescending { it.turn }.flatMap { entries(it.turn, false, it.notifications) }))
    }

    /** 原生动作参数为私有字段：只序列化单条通知，再按白名单解析；绝不发送原始动作或任意 URL。 */
    private fun notificationTargets(notification: Notification): List<JsonObject> {
        val encoded = Json.parseToJsonElement(json().apply { setUsePrototypes(false) }.toJson(notification)).jsonObject
        return encoded["actions"]?.jsonArray.orEmpty().map { action ->
            val (kind, value) = action.jsonObject.entries.single()
            val args = value.jsonObject
            fun unavailable(reason: String) = dto("kind" to "unsupported", "enabled" to false, "reason" to reason)
            fun location(key: String): JsonObject {
                val point = args[key] as? JsonObject ?: return unavailable("目标已失效")
                val x = point["x"]?.jsonPrimitive?.intOrNull ?: 0
                val y = point["y"]?.jsonPrimitive?.intOrNull ?: 0
                val tile = game.tileMap.getOrNull(x, y) ?: return unavailable("目标不在地图中")
                if (!view.getTile(tile).isExplored()) return unavailable("目标地块尚未探索")
                return dto("kind" to "location", "enabled" to true, "x" to x, "y" to y, "label" to "定位地块")
            }
            when (kind) {
                "LocationAction" -> location("location")
                "CityAction" -> {
                    val point = args["city"] as? JsonObject
                    // HexCoord 的自定义序列化始终省略零分量，与 usePrototypes 无关。
                    val x = point?.get("x")?.jsonPrimitive?.intOrNull ?: 0
                    val y = point?.get("y")?.jsonPrimitive?.intOrNull ?: 0
                    val city = if (point == null) null else player.cities.firstOrNull { it.location.x == x && it.location.y == y }
                    if (city == null) unavailable("城市已不存在或不再属于己方")
                    else dto("kind" to "city", "enabled" to true, "id" to city.id, "label" to "查看城市：${city.name}")
                }
                "MapUnitAction", "PromoteUnitAction" -> {
                    val unit = player.units.getCivUnits().firstOrNull { it.id == args["id"]?.jsonPrimitive?.intOrNull }
                    if (unit == null) location("location")
                    else dto("kind" to "unit", "enabled" to true, "id" to unit.id, "label" to "查看单位：${unit.name}")
                }
                "TechAction" -> dto("kind" to "research", "enabled" to true,
                    "name" to args.text("techName").takeIf { it in game.ruleset.technologies }, "label" to "查看科技")
                "PolicyAction" -> dto("kind" to "policy", "enabled" to true,
                    "name" to args.text("select").takeIf { it in game.ruleset.policies }, "label" to "查看政策")
                "OverviewAction" -> dto("kind" to "overview", "enabled" to true, "label" to "查看帝国概况")
                else -> unavailable("此通知的 $kind 跳转尚未接入；通知内容已保留")
            }
        }
    }

    companion object {
        internal fun stats(values: Stats) = dto(*values.mapNotNull { (stat, amount) ->
            if (!amount.isFinite() || amount == 0f) null else stat.name to amount
        }.toTypedArray())

        private fun validate(request: JsonObject, named: Boolean = false): String {
            val allowed = setOf("protocol", "session", "revision", "requestId", "action") + if (named) setOf("name") else emptySet()
            if (request.keys.any { it !in allowed }) throw GatewayError("INVALID_ARGUMENT", "此命令不接受额外参数")
            if (!named) return ""
            val name = request["name"] as? JsonPrimitive
            if (name == null || !name.isString || name.content.isBlank())
                throw GatewayError("INVALID_ARGUMENT", "缺少有效名称")
            return name.content
        }

        fun contribute(registry: CommandRegistry, session: GameSession) {
            for ((name, query) in listOf(
                "empireOverview" to EmpireInformation::overview, "notificationOptions" to EmpireInformation::notifications,
                "researchOptions" to EmpireInformation::research, "policyOptions" to EmpireInformation::policies
            )) registry.query(name) { ctx ->
                validate(ctx.request)
                query(EmpireInformation(session.requireGame()))
            }
            // 保留原 research／policy／deferPolicy 协议和效果；校验前移，非法选择不触发读档式回滚。
            registry.effect("research") { ctx ->
                val name = validate(ctx.request, named = true)
                val civ = session.requireGame().currentPlayerCiv
                if (name !in session.requireGame().ruleset.technologies || !civ.tech.canBeResearched(name))
                    throw GatewayError("CANNOT_RESEARCH", "当前不能研究该科技")
                ({ if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology(name) else civ.tech.techsToResearch = arrayListOf(name) })
            }
            registry.effect("policy") { ctx ->
                val name = validate(ctx.request, named = true)
                val civ = session.requireGame().currentPlayerCiv
                val policy = session.requireGame().ruleset.policies[name] ?: throw GatewayError("POLICY", "未知政策")
                if (!civ.policies.canAdoptPolicy() || !civ.policies.isAdoptable(policy))
                    throw GatewayError("CANNOT_ADOPT", "当前不能采用此政策")
                ({ civ.policies.adopt(policy) })
            }
            registry.effect("deferPolicy") { ctx ->
                validate(ctx.request)
                val civView = GameView(session.requireGame(), session.requireGame().currentPlayerCiv).civView
                ({ civView.tryDismissPolicyPicker() })
            }
        }
    }
}
