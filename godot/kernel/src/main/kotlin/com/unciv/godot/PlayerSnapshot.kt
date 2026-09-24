package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.IConstruction
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import com.unciv.view.CityView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import kotlinx.serialization.json.*

/** 只接受显式选出的基础值，绝不反射序列化 GameInfo 或 View。 */
internal fun value(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map(::value))
    else -> error("不支持的 DTO 类型：${value.javaClass.name}")
}
internal fun dto(vararg entries: Pair<String, Any?>) = JsonObject(entries.associate { it.first to value(it.second) })
internal fun HexCoord.dto() = dto("x" to x, "y" to y)
internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
internal fun JsonObject.integer(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: throw GatewayError("INVALID_ARGUMENT", "缺少整数参数：$key")
internal fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: throw GatewayError("INVALID_ARGUMENT", "缺少布尔参数：$key")

internal class PlayerSnapshot(private val game: GameInfo) {
    val view = GameView(game, game.currentPlayerCiv)
    private val civ = game.currentPlayerCiv

    fun build(): JsonObject = dto(
        "gameId" to game.gameId,
        "turn" to game.turns,
        "player" to civ.civID,
        "nation" to civ.civName,
        "gold" to civ.gold,
        "research" to view.civView.currentTechnologyName(),
        "worldWrap" to game.tileMap.mapParameters.worldWrap,
        "tiles" to game.tileMap.tileList.map { tile ->
            val t = view.getTile(tile)
            if (!t.isExplored()) dto("x" to tile.position.x, "y" to tile.position.y, "visibility" to "unknown")
            else {
                // 施工进度只暴露给“地块属于己方”或“己方施工单位在场”的可见地块；
                // 其他可见敌方地块即使有工程也不回传名称与工期，避免泄漏敌方工程进度。
                val seesConstruction = t.isVisible() && (tile.getOwner() == civ ||
                    t.getVisibleUnits().any { view.civView.isOwnerOf(it) && it.getUnit().cache.hasUniqueToBuildImprovements })
                dto(
                    "x" to tile.position.x, "y" to tile.position.y,
                    "visibility" to if (t.isVisible()) "visible" else "explored",
                    "terrain" to t.baseTerrain, "features" to t.terrainFeatures,
                    "resource" to t.getViewableResource(view.civView)?.name,
                    "improvement" to t.getShownImprovement(),
                    // 领土、道路与受损态的当前变化只在可见区域发送；已探索但不可见的地块不泄漏这些变化。
                    "owner" to if (t.isVisible()) tile.getOwner()?.civID else null,
                    "road" to if (t.isVisible()) t.roadStatus.name else null,
                    "pillaged" to if (t.isVisible()) t.isPillaged() else null,
                    "improvementInProgress" to if (seesConstruction) t.improvementInProgress else null,
                    "turnsToImprovement" to if (seesConstruction && t.improvementInProgress != null) t.turnsToImprovement else null,
                    "riverBottom" to t.hasBottomRiver,
                    "riverLeft" to t.hasBottomLeftRiver,
                    "riverRight" to t.hasBottomRightRiver
                )
            }
        },
        "units" to game.tileMap.tileList.asSequence().map(view::getTile)
            .filter { it.isVisible() }.flatMap { it.getVisibleUnits().asSequence() }.map { unit ->
                val own = view.civView.isOwnerOf(unit)
                val pos = unit.getTile().position()
                dto("id" to unit.id, "name" to unit.name, "x" to pos.x, "y" to pos.y,
                    "own" to own, "civilian" to unit.isCivilian(), "health" to unit.unitHealth,
                    "movement" to if (own) unit.currentMovement else null,
                    "action" to if (own) unit.getUnit().action else null,
                    "due" to if (own) unit.getUnit().due else null,
                    "attacksThisTurn" to if (own) unit.attacksThisTurn else null,
                    "maxHealth" to unit.asCombatant().getMaxHealth(),
                    "nation" to unit.civName,
                    "innerColor" to ("#" + unit.civ().getInnerColor().toString()),
                    "outerColor" to ("#" + unit.civ().getOuterColor().toString()))
            }.toList(),
        "cities" to game.civilizations.flatMap { it.cities }.filter {
            it.civ == civ || view.getTile(it.getCenterTile()).isVisible()
        }.map { city ->
            val own = city.civ == civ
            val cv = view.getCityView(city)
            dto("id" to city.id, "name" to cv.name, "x" to cv.location.x, "y" to cv.location.y,
                "own" to own, "nation" to city.civ.civName,
                "health" to cv.getHealth(), "maxHealth" to cv.getMaxHealth(),
                "capturePending" to civ.popupAlerts.any { it.type == com.unciv.logic.civilization.AlertType.CityConquered && it.value == city.id },
                "population" to if (own) cv.getPopulationCount() else null,
                "production" to if (own) cv.currentConstructionName() else null,
                "queue" to if (own) cv.constructions.constructionQueue else null)
        },
        "technologies" to game.ruleset.technologies.keys.filter { civ.tech.canBeResearched(it) },
        "policies" to if (civ.policies.canAdoptPolicy())
            game.ruleset.policies.values.filter { civ.policies.isAdoptable(it) }.map { it.name } else emptyList<String>(),
        "pending" to pending(),
        "religion" to ReligionCommands(game).snapshotSummary(),
        "diplomaticVote" to DiplomaticVoteCommands(game).snapshotSummary(),
        "notifications" to civ.notifications.map { it.text }
    )

    fun pending(): List<JsonObject> = buildList {
        fun add(kind: String, message: String, supported: Boolean = true, target: String = "") {
            add(dto("kind" to kind, "message" to message, "supported" to supported, "target" to target))
        }
        val vote = DiplomaticVoteCommands(game).pending()
        if (vote?.text("kind") == "voteResult") add(vote)
        for (city in view.civView.cities())
            if (!city.isPuppet() && city.currentConstructionName().isEmpty())
                add("production", "${city.name} 需要选择生产")
        if (view.civView.shouldOpenTechPicker()) add("research", "请选择科技")
        if (view.civView.shouldShowPolicyPicker()) add("policy", "请选择政策，或明确暂缓")
        GreatPersonCommands(game).pending()?.let { add(it) }
        if (view.civView.canFoundPantheon() || view.civView.canExpandPantheon() || view.civView.isFoundingReligion()
            || view.civView.isEnhancingReligion() || view.civView.hasFreeBeliefs())
            ReligionCommands(game).pending()?.let { add(it) }
        if (vote?.text("kind") == "vote") add(vote)
        val diplomacy = DiplomacyCommands(game)
        diplomacy.pendingTrade()?.let { add(it) }
        if (civ.popupAlerts.isNotEmpty()) {
            val alert = civ.popupAlerts.first()
            if (alert.type == com.unciv.logic.civilization.AlertType.CityConquered)
                add(CityCaptureCommands(game).pending(alert))
            else if (alert.type in DiplomacyCommands.alertTypes) add(diplomacy.pendingAlert(alert))
            else add("alert", "${alert.type}：${alert.value}" +
                (if (alert.type in GameSession.informationalAlerts) "" else "；此选择尚未接入，请保存后用原客户端处理"),
                alert.type in GameSession.informationalAlerts)
        }
    }

    fun unitOptions(unit: MapUnitView): JsonObject {
        val raw = civ.units.getCivUnits().first { it.id == unit.id }
        val founding = UnitActionsFromUniques.getFoundCityAction(raw, raw.currentTile) { _, _ -> }
        val combat = CombatCommands(game)
        return dto("unitId" to unit.id,
            "actions" to combat.actions(unit), "attackTargets" to combat.attackTargets(unit),
            "worker" to WorkerCommands(game).workerDto(raw),
            "religion" to ReligionCommands(game).unitDto(raw),
            "canFound" to (founding?.action != null),
            "foundReason" to if (founding?.action != null) "" else "此单位不能在当前地块建城，或行动力不足",
            "reachable" to if (!unit.hasMovement() || unit.cannotMove() || unit.isAirUnit() || unit.isPreparingParadrop()) emptyList<JsonObject>()
                else unit.getReachableTilesInCurrentTurn().filter { unit.canMoveTo(it) }.map { it.position().dto() })
    }

    /** 城市管理只读查询：人口、焦点、专家、工作地块与生产队列，全部复用原生状态与只读估算。 */
    fun cityOptions(city: CityView): JsonObject = JsonObject(CityDevelopmentCommands(game).cityDto(city) +
            ("economy" to CityEconomyCommands(game).economyDto(city)))

    fun constructions(): List<IConstruction> = game.ruleset.units.values + game.ruleset.buildings.values
}
