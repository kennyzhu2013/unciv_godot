package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityFocus
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import com.unciv.view.CityView
import com.unciv.view.GameView
import com.unciv.view.TileView
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * 城市发展命令与只读查询：人口／工作地块、焦点、专家、生产队列。
 * 全部写操作复用 [CityView] 的原生 tryXxx 方法，并按原 CityScreen 的时序补齐人口重分配与统计刷新；
 * 校验独立于方法名，绝不把 tryXxx 返回 true 当作规则允许。查询仅读取原生状态与只读估算，不改真实城市统计。
 * 边界：不接入买地、购买生产、出售建筑、CreatesOneImprovement 目标地块选择。
 */
internal class CityDevelopmentCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private val view by lazy { GameView(game, player) }

    private fun cityView(id: String): CityView = view.civView.cities().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方城市")

    private fun editable(cv: CityView): Boolean =
        cv.isOwnedByViewer() && player.isCurrentPlayer() && !cv.isPuppet()

    private fun editReason(cv: CityView): String = when {
        !cv.isOwnedByViewer() -> "只能管理己方城市"
        !player.isCurrentPlayer() -> "只能在自己的回合管理城市"
        cv.isPuppet() -> "傀儡城市不能手动管理，请先吞并"
        else -> ""
    }

    /** 与 CityScreen.canCityBeChanged 一致的可写前置；写命令统一先做无副作用校验。 */
    private fun requireEditable(cv: CityView) {
        CombatCommands(game).ensureNoBlockingBattleDecision()
        if (!editable(cv)) throw GatewayError("CANNOT_MANAGE_CITY", editReason(cv))
    }

    // region 工作地块

    /**
     * 复现 CityTileGroup 判定 WORKABLE 的条件；返回 `null` 表示可在此地块管理工作人口，否则返回禁用原因。
     * 与原生一致：非本文明拥有、超出工作范围、被其他城市工作、城中心、无产出、被封锁、
     * 以及“无需人口即可提供产出且未被工作”的地块都不可切换。
     */
    private fun workableReason(cv: CityView, tv: TileView): String? {
        val owner = tv.owningCity()
        if (owner == null || !owner.isSameCivAs(cv)) return "此地块不属于该城市的文明"
        if (!cv.isInRange(tv)) return "此地块超出城市工作范围"
        if (tv.isWorked() && tv.getWorkingCity() != cv) return "此地块已被其他城市工作"
        if (tv.isCityCenter()) return "城市中心地块无需分配人口"
        if (tv.getTileStats(cv.viewingCiv(), cv).isEmpty()) return "此地块没有产出"
        if (tv.isBlockaded()) return "此地块被封锁，无法工作"
        if (!tv.isWorked() && tv.providesYield()) return "此地块无需人口即可提供产出"
        return null
    }

    fun prepareCitizen(id: String, x: Int, y: Int, type: String): () -> Unit {
        val cv = cityView(id)
        requireEditable(cv)
        val tv = view.tileMapView.getTile(HexCoord(x, y))
            ?: throw GatewayError("INVALID_ARGUMENT", "目标地块不在地图中")
        val notWorkableReason = workableReason(cv, tv)
        val workable = notWorkableReason == null
        val worked = cv.isWorked(tv)
        val locked = tv.isLocked()
        return when (type) {
            "work" -> {
                ensure(workable, "CANNOT_ASSIGN", notWorkableReason ?: "此地块无法分配人口")
                ensure(!worked, "CANNOT_ASSIGN", "此地块已在工作")
                ensure(cv.getFreePopulation() > 0, "CANNOT_ASSIGN", "没有剩余人口，请先撤回其他地块或专家")
                ({ cv.tryWorkTile(tv); cv.updateCityStats() })
            }
            "unwork" -> {
                ensure(workable, "CANNOT_ASSIGN", notWorkableReason ?: "此地块无法分配人口")
                ensure(worked, "CANNOT_ASSIGN", "此地块尚未工作")
                ({ cv.tryStopWorkingTile(tv); cv.updateCityStats() })
            }
            "lock" -> {
                ensure(workable, "CANNOT_ASSIGN", notWorkableReason ?: "此地块无法分配人口")
                ensure(worked, "CANNOT_ASSIGN", "只能锁定已工作的地块")
                ensure(!locked, "CANNOT_ASSIGN", "此地块已锁定")
                ({ cv.tryLockTile(tv); cv.updateCityStats() })
            }
            "unlock" -> {
                ensure(workable, "CANNOT_ASSIGN", notWorkableReason ?: "此地块无法分配人口")
                ensure(locked, "CANNOT_ASSIGN", "此地块未锁定")
                ({ cv.tryUnlockTile(tv); cv.updateCityStats() })
            }
            else -> throw GatewayError("INVALID_ARGUMENT", "未知地块命令类型：$type")
        }
    }

    // endregion

    // region 焦点／增长／专家

    private fun focusOptions(cv: CityView): List<CityFocus> {
        val religion = game.isReligionEnabled()
        return CityFocus.entries.filter { it.tableEnabled && !(it == CityFocus.FaithFocus && !religion) }
    }

    fun prepareFocus(id: String, focus: String): () -> Unit {
        val cv = cityView(id)
        requireEditable(cv)
        val target = focusOptions(cv).firstOrNull { it.name == focus }
            ?: throw GatewayError("INVALID_ARGUMENT", "未知或不可用的城市焦点：$focus")
        return { cv.trySetCityFocus(target); cv.updateCityStats() }
    }

    fun prepareAvoidGrowth(id: String, enabled: Boolean): () -> Unit {
        val cv = cityView(id)
        requireEditable(cv)
        // tryToggleAvoidGrowth 取反并重分配人口；已达目标值时保持无操作，使命令按值幂等。
        return if (cv.avoidGrowth == enabled) ({ cv.updateCityStats() })
            else ({ cv.tryToggleAvoidGrowth(); cv.updateCityStats() })
    }

    fun prepareReset(id: String): () -> Unit {
        val cv = cityView(id)
        requireEditable(cv)
        return { cv.tryReassignPopulation(resetLocked = true); cv.updateCityStats() }
    }

    fun prepareSpecialists(id: String, type: String, name: String, enabled: Boolean?): () -> Unit {
        val cv = cityView(id)
        requireEditable(cv)
        return when (type) {
            "assign" -> {
                val (max, assigned) = specialistBounds(cv, name)
                ensure(assigned < max, "CANNOT_ASSIGN", "该专家槽位已满")
                ensure(cv.getFreePopulation() > 0, "CANNOT_ASSIGN", "没有剩余人口可分配为专家")
                ({ cv.tryAssignSpecialist(name); cv.updateCityStats() })
            }
            "unassign" -> {
                val (_, assigned) = specialistBounds(cv, name)
                ensure(assigned > 0, "CANNOT_ASSIGN", "没有可撤回的该专家")
                ({ cv.tryUnassignSpecialist(name); cv.updateCityStats() })
            }
            "setManual" -> {
                val target = enabled ?: throw GatewayError("INVALID_ARGUMENT", "缺少 enabled 参数")
                when {
                    cv.manualSpecialists == target -> ({ cv.updateCityStats() })
                    target -> ({ cv.tryEnableManualSpecialists(); cv.updateCityStats() })
                    else -> ({ cv.tryDisableManualSpecialists(); cv.updateCityStats() })
                }
            }
            else -> throw GatewayError("INVALID_ARGUMENT", "未知专家命令类型：$type")
        }
    }

    /** 校验专家存在并返回（上限，已分配）；上限来自建筑槽位，已分配来自当前人口分配。 */
    private fun specialistBounds(cv: CityView, name: String): Pair<Int, Int> {
        ensure(game.ruleset.specialists.containsKey(name), "INVALID_ARGUMENT", "未知专家：$name")
        val max = cv.getMaxSpecialists()[name]
        ensure(max > 0, "CANNOT_ASSIGN", "此城市没有该专家槽位")
        return max to cv.getNewSpecialists()[name]
    }

    // endregion

    // region 生产队列

    private fun resolveConstruction(name: String): IConstruction? =
        game.ruleset.buildings[name] ?: game.ruleset.units[name]
            ?: PerpetualConstruction.perpetualConstructionsMap[name]

    fun prepareQueue(id: String, type: String, name: String, index: Int?): () -> Unit {
        val cv = cityView(id)
        requireEditable(cv)
        val queue = cv.constructions.constructionQueue
        return when (type) {
            "add" -> {
                val construction = resolveConstruction(name)
                    ?: throw GatewayError("INVALID_ARGUMENT", "未知生产项目：$name")
                val needsTile = construction is Building && cv.getImprovementToCreate(construction) != null &&
                    cv.constructions.getTileForImprovement(cv.getImprovementToCreate(construction)!!.name) == null
                ensure(!needsTile, "UNSUPPORTED", "需要指定改良地块的项目尚未接入")
                ensure(cv.constructions.canAddToQueue(construction), "CANNOT_EDIT_QUEUE", addRejection(cv, construction))
                if (construction is PerpetualConstruction)
                    ensure(!cv.constructions.isBeingConstructedOrEnqueued(name), "CANNOT_EDIT_QUEUE", "已在生产该持续项目")
                ({
                    cv.tryAddToQueue(name)
                    // 与 CityConstructionsTable.addConstructionToQueue 一致：仅当加入项成为当前生产时重分配人口。
                    val updated = cv.constructions.constructionQueue
                    if (updated.isNotEmpty() && updated.first() == name) cv.tryReassignPopulation()
                    cv.updateCityStats()
                })
            }
            "remove" -> {
                val at = requireIndex(index, queue.size)
                ({ cv.tryRemoveFromQueue(at, false); cv.tryReassignPopulation(); cv.updateCityStats() })
            }
            "raise" -> {
                val at = requireIndex(index, queue.size)
                ensure(at > 0, "CANNOT_EDIT_QUEUE", "已是队列首位")
                ({ cv.tryRaisePriority(at); cv.tryReassignPopulation(); cv.updateCityStats() })
            }
            "lower" -> {
                val at = requireIndex(index, queue.size)
                ensure(at < queue.size - 1, "CANNOT_EDIT_QUEUE", "已是队列末位")
                ({ cv.tryLowerPriority(at); cv.tryReassignPopulation(); cv.updateCityStats() })
            }
            else -> throw GatewayError("INVALID_ARGUMENT", "未知队列命令类型：$type")
        }
    }

    private fun requireIndex(index: Int?, size: Int): Int {
        val at = index ?: throw GatewayError("INVALID_ARGUMENT", "缺少 index 参数")
        ensure(at in 0 until size, "CANNOT_EDIT_QUEUE", "队列索引超出范围")
        return at
    }

    private fun addRejection(cv: CityView, construction: IConstruction): String = when {
        cv.constructions.isQueueFull() -> "生产队列已满"
        construction is Building && cv.constructions.isBeingConstructedOrEnqueued(construction.name) -> "该建筑已在队列中"
        construction is PerpetualConstruction && cv.constructions.isBeingConstructedOrEnqueued(construction.name) -> "已在生产该持续项目"
        else -> "当前不可生产该项目"
    }

    // endregion

    // region 只读查询 DTO

    fun cityDto(cv: CityView): JsonObject {
        val canEdit = editable(cv)
        val reason = editReason(cv)
        return dto(
            "cityId" to cv.id,
            "name" to cv.name,
            "editable" to canEdit,
            "reason" to reason,
            "population" to cv.getPopulationCount(),
            "growth" to dto(
                "foodStored" to cv.getFoodStored(),
                "foodToNext" to cv.getFoodToNextPopulation(),
                "foodPerTurn" to cv.foodForNextTurn(),
                "turnsToNewPopulation" to cv.getNumTurnsToNewPopulation(),
                "turnsToStarvation" to cv.getNumTurnsToStarvation(),
                "isGrowing" to cv.isGrowing(),
                "isStarving" to cv.isStarving()),
            "focus" to cv.getCityFocus().name,
            "focusOptions" to focusOptions(cv).map {
                dto("id" to it.name, "label" to it.label, "current" to (cv.getCityFocus() == it))
            },
            "avoidGrowth" to cv.avoidGrowth,
            "specialists" to dto(
                "manual" to cv.manualSpecialists,
                "freePopulation" to cv.getFreePopulation(),
                "slots" to cv.getMaxSpecialists().asSequence().sortedBy { it.key }
                    .filter { game.ruleset.specialists.containsKey(it.key) }
                    .map { (name, max) ->
                        val assigned = cv.getNewSpecialists()[name]
                        dto("name" to name, "max" to max, "assigned" to assigned,
                            "stats" to statsDto(cv.getStatsOfSpecialist(name)),
                            "canAssign" to (canEdit && assigned < max && cv.getFreePopulation() > 0),
                            "canUnassign" to (canEdit && assigned > 0))
                    }.toList()),
            "stats" to statsDto(cv.getCurrentCityStats()),
            "statsBreakdown" to dto(*cv.getFinalStatList().map { (k, v) -> k to statsDto(v) }.toTypedArray()),
            "citizenTiles" to citizenTiles(cv, canEdit),
            "queue" to cv.constructions.constructionQueue,
            "queueEntries" to queueEntries(cv, canEdit),
            "constructions" to availableConstructions(cv, canEdit, reason)
        )
    }

    private fun citizenTiles(cv: CityView, canEdit: Boolean): List<JsonObject> {
        val freePopulation = cv.getFreePopulation()
        return game.tileMap.tileList.mapNotNull { tile ->
            val tv = cv.tileView(tile)
            // 只输出工作范围内且已探索的地块；未探索地块不泄漏地形、资源或状态。
            if (!cv.isInRange(tv) || !tv.isExplored()) return@mapNotNull null
            val workable = workableReason(cv, tv) == null
            val owned = tv.owningCity()?.isSameCivAs(cv) == true
            // worked／locked 属于城市内部人口分配信息；非己方地块一律归 false，
            // 不在网关侧泄漏他文明的内部工作状态，也不能只依赖 Godot 前端隐藏。
            val worked = owned && cv.isWorked(tv)
            val locked = owned && tv.isLocked()
            dto("x" to tile.position.x, "y" to tile.position.y,
                "resource" to tv.getViewableResource(cv.viewingCiv())?.name,
                "yields" to statsDto(tv.getTileStats(cv.viewingCiv(), cv)),
                "owned" to owned,
                "worked" to worked, "locked" to locked,
                "blockaded" to tv.isBlockaded(), "cityCenter" to tv.isCityCenter(),
                "canWork" to (canEdit && workable && !worked && freePopulation > 0),
                "canUnwork" to (canEdit && workable && worked),
                "canLock" to (canEdit && workable && worked && !locked),
                "canUnlock" to (canEdit && workable && locked))
        }
    }

    private fun queueEntries(cv: CityView, canEdit: Boolean): List<JsonObject> {
        val queue = cv.constructions.constructionQueue
        return queue.mapIndexed { index, name ->
            val construction = resolveConstruction(name)
            val perpetual = construction is PerpetualConstruction
            val firstOfKind = cv.constructions.isFirstConstructionOfItsKind(index, name)
            val nonPerpetual = construction as? INonPerpetualConstruction
            val turns = if (perpetual) null
                else cv.constructions.turnsToConstruction(name, firstOfKind).takeIf { it in 0..100000 }
            dto("index" to index, "name" to name,
                "type" to constructionType(construction),
                "current" to (index == 0),
                "perpetual" to perpetual,
                "firstOfKind" to firstOfKind,
                "cost" to if (nonPerpetual == null) null else cv.getConstructionProductionCost(nonPerpetual),
                "workDone" to if (perpetual || !firstOfKind) null else cv.constructions.getWorkDone(name),
                "turns" to turns,
                "canRemove" to canEdit,
                "canRaise" to (canEdit && index > 0),
                "canLower" to (canEdit && index < queue.size - 1))
        }
    }

    private fun availableConstructions(cv: CityView, canEdit: Boolean, editReason: String): List<JsonObject> {
        val displayed = (game.ruleset.units.values.asSequence() + game.ruleset.buildings.values.asSequence())
            .filter { cv.constructions.shouldBeDisplayed(it) }.toList() +
            PerpetualConstruction.perpetualConstructionsMap.values.filter { cv.constructions.shouldBeDisplayed(it) }
        return displayed.map { construction ->
            val improvement = (construction as? Building)?.let { cv.getImprovementToCreate(it) }
            val needsTile = improvement != null && cv.constructions.getTileForImprovement(improvement.name) == null
            val duplicatePerpetual = construction is PerpetualConstruction &&
                cv.constructions.isBeingConstructedOrEnqueued(construction.name)
            val canAdd = cv.constructions.canAddToQueue(construction) && !duplicatePerpetual && !needsTile
            val enabled = canEdit && canAdd
            val reason = when {
                enabled -> ""
                !canEdit -> editReason
                needsTile -> "需要指定改良地块，首版尚未接入"
                cv.constructions.isQueueFull() -> "生产队列已满"
                duplicatePerpetual || (construction is Building && cv.constructions.isBeingConstructedOrEnqueued(construction.name)) -> "该项目已在队列中"
                else -> "当前不可生产该项目"
            }
            dto("name" to construction.name, "type" to constructionType(construction),
                "enabled" to enabled, "reason" to reason)
        }
    }

    private fun constructionType(construction: IConstruction?): String = when (construction) {
        is Building -> "building"
        is BaseUnit -> "unit"
        is PerpetualConstruction -> "perpetual"
        else -> "other"
    }

    // endregion

    private fun ensure(condition: Boolean, code: String, message: String) {
        if (!condition) throw GatewayError(code, message)
    }
}

/** 只发送非零项，保留一位小数；不写 NaN/Infinity。 */
private fun statsDto(stats: Stats): JsonObject {
    val entries = ArrayList<Pair<String, Any?>>()
    for ((key, value) in stats) {
        val rounded = (value * 10).roundToInt() * 0.1f
        if (rounded != 0f && rounded.isFinite()) entries.add(key.name to rounded)
    }
    return dto(*entries.toTypedArray())
}
